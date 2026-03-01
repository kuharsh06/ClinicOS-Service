package com.clinicos.service.service;

import com.clinicos.service.dto.request.SyncPushRequest;
import com.clinicos.service.dto.response.SyncPushResponse;
import com.clinicos.service.entity.*;
import com.clinicos.service.enums.EventStatus;
import com.clinicos.service.enums.Gender;
import com.clinicos.service.enums.QueueEntryState;
import com.clinicos.service.enums.QueueStatus;
import com.clinicos.service.exception.ResourceNotFoundException;
import com.clinicos.service.repository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Processes individual sync events in their own transaction.
 * Each call to processSingleEvent() runs in REQUIRES_NEW — commits immediately.
 * This prevents Hibernate session poison across events and enables
 * in-memory queue locks to serialize concurrent access.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SyncEventProcessor {

    private final EventStoreRepository eventStoreRepository;
    private final QueueRepository queueRepository;
    private final QueueEntryRepository queueEntryRepository;
    private final PatientRepository patientRepository;
    private final VisitRepository visitRepository;
    private final BillRepository billRepository;
    private final BillItemRepository billItemRepository;
    private final VisitImageRepository visitImageRepository;
    private final OrgMemberRepository orgMemberRepository;
    private final ObjectMapper objectMapper;
    private final jakarta.persistence.EntityManager entityManager;

    // Valid state transitions per API contract v3.1
    private static final Map<QueueEntryState, Set<QueueEntryState>> VALID_TRANSITIONS = Map.of(
            QueueEntryState.WAITING, Set.of(QueueEntryState.CALLED, QueueEntryState.REMOVED, QueueEntryState.STASHED),
            QueueEntryState.CALLED, Set.of(QueueEntryState.COMPLETED, QueueEntryState.WAITING),
            QueueEntryState.STASHED, Set.of(QueueEntryState.WAITING, QueueEntryState.REMOVED),
            QueueEntryState.COMPLETED, Set.of(),
            QueueEntryState.REMOVED, Set.of()
    );

    // Event type to allowed roles
    private static final Map<String, List<String>> EVENT_ALLOWED_ROLES = Map.ofEntries(
            Map.entry("patient_added", List.of("assistant", "doctor")),
            Map.entry("patient_removed", List.of("assistant", "doctor")),
            Map.entry("call_now", List.of("assistant", "doctor")),
            Map.entry("step_out", List.of("assistant", "doctor")),
            Map.entry("mark_complete", List.of("assistant", "doctor")),
            Map.entry("queue_paused", List.of("assistant", "doctor")),
            Map.entry("queue_resumed", List.of("assistant", "doctor")),
            Map.entry("queue_ended", List.of("assistant", "doctor")),
            Map.entry("stash_imported", List.of("assistant", "doctor")),
            Map.entry("visit_saved", List.of("doctor")),
            Map.entry("bill_created", List.of("assistant", "doctor")),
            Map.entry("bill_updated", List.of("assistant", "doctor")),
            Map.entry("bill_paid", List.of("assistant", "doctor"))
    );

    /**
     * Result of processing a single event.
     */
    public record EventResult(
            boolean accepted,
            SyncPushResponse.AcceptedEvent acceptedEvent,
            SyncPushResponse.RejectedEvent rejectedEvent
    ) {
        public static EventResult accepted(String eventId, long serverTimestamp) {
            return new EventResult(true,
                    SyncPushResponse.AcceptedEvent.builder()
                            .eventId(eventId)
                            .serverReceivedAt(serverTimestamp)
                            .build(),
                    null);
        }

        public static EventResult rejected(String eventId, String code, String reason) {
            return new EventResult(false, null,
                    SyncPushResponse.RejectedEvent.builder()
                            .eventId(eventId)
                            .code(code)
                            .reason(reason)
                            .build());
        }
    }

    /**
     * Process a single sync event in its own transaction.
     * REQUIRES_NEW ensures each event commits independently.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EventResult processSingleEvent(SyncPushRequest.SyncEvent event, User user, Organization org,
                                          Set<String> actualRoles, long serverTimestamp) {
        // Clear first-level cache to avoid stale entities from open-in-view
        // (resolveQueueId may have loaded entities before the lock was acquired)
        entityManager.clear();

        try {
            // 1. DEDUP by eventId
            if (eventStoreRepository.findByUuid(event.getEventId()).isPresent()) {
                return EventResult.rejected(event.getEventId(), "DUPLICATE_IGNORED", "Event already processed");
            }

            // 2. ROLE CHECK
            List<String> allowedRoles = EVENT_ALLOWED_ROLES.get(event.getEventType());
            if (allowedRoles == null) {
                return EventResult.rejected(event.getEventId(), "INVALID_EVENT_TYPE",
                        "Unknown event type: " + event.getEventType());
            }
            boolean hasAllowedRole = actualRoles.stream().anyMatch(allowedRoles::contains);
            if (!hasAllowedRole) {
                return EventResult.rejected(event.getEventId(), "UNAUTHORIZED_ROLE",
                        "User roles " + actualRoles + " cannot perform " + event.getEventType());
            }

            // 3. SCHEMA CHECK
            if (event.getSchemaVersion() == null || event.getSchemaVersion() < 1) {
                return EventResult.rejected(event.getEventId(), "SCHEMA_MISMATCH", "Invalid schema version");
            }

            // 4. STORE as PENDING
            EventStore eventStore = EventStore.builder()
                    .deviceId(event.getDeviceId())
                    .user(user)
                    .organization(org)
                    .eventType(event.getEventType())
                    .targetEntityUuid(event.getTargetEntity())
                    .targetTable(event.getTargetTable())
                    .payload(toJson(event.getPayload()))
                    .schemaVersion(event.getSchemaVersion())
                    .deviceTimestamp(event.getDeviceTimestamp())
                    .serverReceivedAt(serverTimestamp)
                    .status(EventStatus.PENDING)
                    .build();
            eventStore.setUuid(event.getEventId());
            eventStoreRepository.save(eventStore);

            // 5. Process event
            try {
                processEvent(event, user, org);

                eventStore.setStatus(EventStatus.APPLIED);
                eventStoreRepository.save(eventStore);

                log.info("Event {} processed: {} on {}", event.getEventId(), event.getEventType(), event.getTargetEntity());
                return EventResult.accepted(event.getEventId(), serverTimestamp);

            } catch (Exception processingEx) {
                eventStore.setStatus(EventStatus.REJECTED);
                eventStore.setRejectionCode("PROCESSING_ERROR");
                eventStore.setRejectionReason(processingEx.getMessage());
                eventStoreRepository.save(eventStore);

                log.error("Event {} failed processing: {}", event.getEventId(), processingEx.getMessage());
                return EventResult.rejected(event.getEventId(), "PROCESSING_ERROR", processingEx.getMessage());
            }

        } catch (Exception e) {
            log.error("Error processing event {}: {}", event.getEventId(), e.getMessage());
            return EventResult.rejected(event.getEventId(), "PROCESSING_ERROR", e.getMessage());
        }
    }

    // ==================== Event Dispatcher ====================

    private void processEvent(SyncPushRequest.SyncEvent event, User user, Organization org) {
        Map<String, Object> payload = event.getPayload();

        switch (event.getEventType()) {
            case "patient_added" -> processPatientAdded(event, user, org, payload);
            case "patient_removed" -> processPatientRemoved(event, payload);
            case "call_now" -> processCallNow(event, payload);
            case "step_out" -> processStepOut(event, payload);
            case "mark_complete" -> processMarkComplete(event, payload);
            case "queue_paused" -> processQueuePaused(event, payload);
            case "queue_resumed" -> processQueueResumed(event, payload);
            case "queue_ended" -> processQueueEnded(event, payload);
            case "stash_imported" -> processStashImported(event, user, org, payload);
            case "visit_saved" -> processVisitSaved(event, user, org, payload);
            case "bill_created" -> processBillCreated(event, user, org, payload);
            case "bill_updated" -> processBillUpdated(event, user, org, payload);
            case "bill_paid" -> processBillPaid(event, user, org, payload);
            default -> log.warn("Unknown event type: {}", event.getEventType());
        }
    }

    // ==================== Queue-Mutating Event Handlers ====================

    @SuppressWarnings("unchecked")
    private void processPatientAdded(SyncPushRequest.SyncEvent event, User user, Organization org, Map<String, Object> payload) {
        String queueId = (String) payload.get("queueId");
        String patientId = (String) payload.get("patientId");
        Integer tokenNumber = (Integer) payload.get("tokenNumber");
        String doctorId = (String) payload.get("doctorId");

        Map<String, Object> patientInfo = (Map<String, Object>) payload.get("patient");
        String phone = patientInfo != null ? (String) patientInfo.get("phone") : null;
        String name = patientInfo != null ? (String) patientInfo.get("name") : null;
        Integer age = patientInfo != null ? (Integer) patientInfo.get("age") : null;
        String gender = patientInfo != null ? (String) patientInfo.get("gender") : null;

        List<String> complaintTags = (List<String>) payload.get("complaintTags");
        String complaintText = (String) payload.get("complaintText");

        // Find or create patient
        Patient patient = patientRepository.findByUuid(patientId).orElse(null);
        if (patient == null && phone != null) {
            patient = patientRepository.findByOrganizationIdAndCountryCodeAndPhone(
                    org.getId(), "+91", phone).orElse(null);
        }

        if (patient == null) {
            patient = Patient.builder()
                    .organization(org)
                    .phone(phone)
                    .countryCode("+91")
                    .name(name)
                    .age(age)
                    .gender(gender != null ? Gender.valueOf(gender.toUpperCase()) : null)
                    .totalVisits(0)
                    .isRegular(false)
                    .build();
            patient.setUuid(patientId);
            patientRepository.save(patient);
            log.info("Created new patient: {}", patientId);
        }

        // Find or create queue
        Queue queue = queueRepository.findByUuid(queueId).orElse(null);
        if (queue != null && queue.getStatus() == QueueStatus.ENDED) {
            throw new IllegalStateException("Cannot add patient to an ENDED queue: " + queueId);
        }
        if (queue == null) {
            OrgMember doctor = orgMemberRepository.findByOrganizationIdWithUser(org.getId())
                    .stream()
                    .filter(m -> m.getUser().getUuid().equals(doctorId))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Doctor not found: " + doctorId));

            List<Queue> existingActive = queueRepository.findByDoctorIdAndStatusIn(
                    doctor.getId(), List.of(QueueStatus.ACTIVE, QueueStatus.PAUSED));
            if (!existingActive.isEmpty()) {
                throw new IllegalStateException("Doctor already has an active queue: " + existingActive.get(0).getUuid()
                        + ". End it before starting a new one.");
            }

            queue = Queue.builder()
                    .organization(org)
                    .doctor(doctor)
                    .status(QueueStatus.ACTIVE)
                    .lastToken(0)
                    .totalPausedMs(0L)
                    .startedAt(Instant.now())
                    .build();
            queue.setUuid(queueId);
            queueRepository.save(queue);
            log.info("Created new queue: {} for doctor: {}", queueId, doctorId);
        }

        if (tokenNumber != null && tokenNumber > queue.getLastToken()) {
            queue.setLastToken(tokenNumber);
            queueRepository.save(queue);
        }

        // Count existing entries to determine position (always appends to end)
        int entryCount = queueEntryRepository.findByQueueIdOrderByPositionAsc(queue.getId()).size();

        QueueEntry entry = QueueEntry.builder()
                .queue(queue)
                .patient(patient)
                .tokenNumber(tokenNumber != null ? tokenNumber : queue.getLastToken())
                .state(QueueEntryState.WAITING)
                .position(entryCount + 1)
                .complaintTags(complaintTags != null ? toJson(complaintTags) : null)
                .complaintText(complaintText)
                .isBilled(false)
                .registeredAt(event.getDeviceTimestamp() != null ? event.getDeviceTimestamp() : System.currentTimeMillis())
                .build();
        entry.setUuid(event.getTargetEntity());
        queueEntryRepository.save(entry);

        log.info("Patient added to queue: entry={}, token={}, patient={}",
                event.getTargetEntity(), tokenNumber, patientId);
    }

    private void processPatientRemoved(SyncPushRequest.SyncEvent event, Map<String, Object> payload) {
        String entryId = event.getTargetEntity();
        String reason = (String) payload.get("reason");

        QueueEntry entry = queueEntryRepository.findByUuid(entryId)
                .orElseThrow(() -> new RuntimeException("Queue entry not found: " + entryId));

        guardStateTransition(entry, QueueEntryState.REMOVED);
        entry.setState(QueueEntryState.REMOVED);
        entry.setRemovalReason(reason != null ? reason : "removed");
        queueEntryRepository.save(entry);

        log.info("Patient removed from queue: entry={}", entryId);
    }

    private void processCallNow(SyncPushRequest.SyncEvent event, Map<String, Object> payload) {
        String entryId = event.getTargetEntity();

        QueueEntry entry = queueEntryRepository.findByUuid(entryId)
                .orElseThrow(() -> new RuntimeException("Queue entry not found: " + entryId));

        // Check if someone is already being served in this queue
        List<QueueEntry> queueEntries = queueEntryRepository.findByQueueIdOrderByPositionAsc(entry.getQueue().getId());
        boolean hasNowServing = queueEntries.stream()
                .anyMatch(e -> e.getState() == QueueEntryState.CALLED && !e.getId().equals(entry.getId()));

        if (hasNowServing) {
            throw new IllegalStateException("Cannot call patient — another patient is already being served in this queue");
        }

        guardStateTransition(entry, QueueEntryState.CALLED);
        entry.setState(QueueEntryState.CALLED);
        entry.setCalledAt(event.getDeviceTimestamp() != null ? event.getDeviceTimestamp() : System.currentTimeMillis());
        queueEntryRepository.save(entry);

        log.info("Patient called: entry={}", entryId);
    }

    private void processStepOut(SyncPushRequest.SyncEvent event, Map<String, Object> payload) {
        String entryId = event.getTargetEntity();
        String reason = (String) payload.get("reason");

        QueueEntry entry = queueEntryRepository.findByUuid(entryId)
                .orElseThrow(() -> new RuntimeException("Queue entry not found: " + entryId));

        guardStateTransition(entry, QueueEntryState.WAITING);

        List<QueueEntry> allEntries = queueEntryRepository.findByQueueIdOrderByPositionAsc(entry.getQueue().getId());
        int maxPosition = allEntries.stream()
                .filter(e -> e.getState() == QueueEntryState.WAITING)
                .mapToInt(e -> e.getPosition() != null ? e.getPosition() : 0)
                .max()
                .orElse(0);

        entry.setState(QueueEntryState.WAITING);
        entry.setPosition(maxPosition + 1);
        entry.setStepOutReason(reason);
        entry.setSteppedOutAt(event.getDeviceTimestamp() != null ? event.getDeviceTimestamp() : System.currentTimeMillis());
        entry.setCalledAt(null);
        queueEntryRepository.save(entry);

        log.info("Patient stepped out, moved to end of waiting queue (position {}): entry={}", maxPosition + 1, entryId);
    }

    private void processMarkComplete(SyncPushRequest.SyncEvent event, Map<String, Object> payload) {
        String entryId = event.getTargetEntity();

        QueueEntry entry = queueEntryRepository.findByUuid(entryId)
                .orElseThrow(() -> new RuntimeException("Queue entry not found: " + entryId));

        guardStateTransition(entry, QueueEntryState.COMPLETED);
        entry.setState(QueueEntryState.COMPLETED);
        entry.setCompletedAt(event.getDeviceTimestamp() != null ? event.getDeviceTimestamp() : System.currentTimeMillis());
        queueEntryRepository.save(entry);

        Patient patient = entry.getPatient();
        patient.setTotalVisits(patient.getTotalVisits() + 1);
        long completedMs = event.getDeviceTimestamp() != null ? event.getDeviceTimestamp() : System.currentTimeMillis();
        patient.setLastVisitDate(Instant.ofEpochMilli(completedMs).atZone(java.time.ZoneId.of("Asia/Kolkata")).toLocalDate());
        patient.setLastComplaintTags(entry.getComplaintTags());
        patientRepository.save(patient);

        log.info("Patient consultation completed: entry={}", entryId);
    }

    private void processQueuePaused(SyncPushRequest.SyncEvent event, Map<String, Object> payload) {
        String queueId = event.getTargetEntity();

        Queue queue = queueRepository.findByUuid(queueId)
                .orElseThrow(() -> new RuntimeException("Queue not found: " + queueId));

        if (queue.getStatus() == QueueStatus.ENDED) {
            throw new IllegalStateException("Cannot pause an ENDED queue: " + queueId);
        }
        if (queue.getStatus() == QueueStatus.PAUSED) {
            log.info("Queue {} already paused, ignoring", queueId);
            return;
        }

        queue.setStatus(QueueStatus.PAUSED);
        queue.setPauseStartTime(event.getDeviceTimestamp() != null ? event.getDeviceTimestamp() : System.currentTimeMillis());
        queueRepository.save(queue);

        log.info("Queue paused: {}", queueId);
    }

    private void processQueueResumed(SyncPushRequest.SyncEvent event, Map<String, Object> payload) {
        String queueId = event.getTargetEntity();

        Queue queue = queueRepository.findByUuid(queueId)
                .orElseThrow(() -> new RuntimeException("Queue not found: " + queueId));

        if (queue.getStatus() == QueueStatus.ENDED) {
            throw new IllegalStateException("Cannot resume an ENDED queue: " + queueId);
        }
        if (queue.getStatus() == QueueStatus.ACTIVE) {
            log.info("Queue {} already active, ignoring", queueId);
            return;
        }

        if (queue.getPauseStartTime() != null) {
            long resumedAt = event.getDeviceTimestamp() != null ? event.getDeviceTimestamp() : System.currentTimeMillis();
            long pausedDuration = resumedAt - queue.getPauseStartTime();
            if (pausedDuration > 0) {
                queue.setTotalPausedMs(queue.getTotalPausedMs() + pausedDuration);
            }
        }

        queue.setStatus(QueueStatus.ACTIVE);
        queue.setPauseStartTime(null);
        queueRepository.save(queue);

        log.info("Queue resumed: {}", queueId);
    }

    @SuppressWarnings("unchecked")
    private void processQueueEnded(SyncPushRequest.SyncEvent event, Map<String, Object> payload) {
        String queueId = event.getTargetEntity();
        List<String> stashedEntryIds = (List<String>) payload.get("stashedEntryIds");

        Queue queue = queueRepository.findByUuid(queueId)
                .orElseThrow(() -> new RuntimeException("Queue not found: " + queueId));

        if (queue.getStatus() == QueueStatus.ENDED) {
            log.info("Queue {} already ended, ignoring", queueId);
            return;
        }

        queue.setStatus(QueueStatus.ENDED);
        long endedMs = event.getDeviceTimestamp() != null ? event.getDeviceTimestamp() : System.currentTimeMillis();
        queue.setEndedAt(Instant.ofEpochMilli(endedMs));
        queueRepository.save(queue);

        // Auto-complete any currently serving patients
        List<QueueEntry> allEntries = queueEntryRepository.findByQueueIdOrderByPositionAsc(queue.getId());
        for (QueueEntry entry : allEntries) {
            if (entry.getState() == QueueEntryState.CALLED) {
                entry.setState(QueueEntryState.COMPLETED);
                entry.setCompletedAt(endedMs);
                queueEntryRepository.save(entry);
                log.info("Auto-completed now_serving entry {} on queue end", entry.getUuid());
            }
        }

        if (stashedEntryIds != null && !stashedEntryIds.isEmpty()) {
            for (String entryId : stashedEntryIds) {
                QueueEntry entry = queueEntryRepository.findByUuid(entryId).orElse(null);
                if (entry == null) continue;
                Set<QueueEntryState> allowed = VALID_TRANSITIONS.getOrDefault(entry.getState(), Set.of());
                if (!allowed.contains(QueueEntryState.STASHED)) continue;

                entry.setState(QueueEntryState.STASHED);
                entry.setStashedFromQueue(queue);
                queueEntryRepository.save(entry);
            }
            log.info("Queue ended: {}, stashed {} entries", queueId, stashedEntryIds.size());
        } else {
            log.info("Queue ended: {}, no entries stashed", queueId);
        }
    }

    @SuppressWarnings("unchecked")
    private void processStashImported(SyncPushRequest.SyncEvent event, User user, Organization org, Map<String, Object> payload) {
        String newQueueId = event.getTargetEntity();
        List<String> importedEntryIds = (List<String>) payload.get("importedEntryIds");
        String doctorId = (String) payload.get("doctorId");

        OrgMember doctor = orgMemberRepository.findByOrganizationIdWithUser(org.getId())
                .stream()
                .filter(m -> m.getUser().getUuid().equals(doctorId))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Doctor not found: " + doctorId));

        Queue newQueue = queueRepository.findByUuid(newQueueId).orElse(null);
        if (newQueue == null) {
            List<Queue> existingActive = queueRepository.findByDoctorIdAndStatusIn(
                    doctor.getId(), List.of(QueueStatus.ACTIVE, QueueStatus.PAUSED));
            if (!existingActive.isEmpty()) {
                throw new IllegalStateException("Doctor already has an active queue: " + existingActive.get(0).getUuid()
                        + ". End it before importing stash.");
            }

            newQueue = Queue.builder()
                    .organization(org)
                    .doctor(doctor)
                    .status(QueueStatus.ACTIVE)
                    .lastToken(0)
                    .totalPausedMs(0L)
                    .startedAt(Instant.now())
                    .build();
            newQueue.setUuid(newQueueId);
            queueRepository.save(newQueue);
            log.info("Created new queue for stash import: {}", newQueueId);
        }

        int position = 1;
        int maxToken = 0;

        if (importedEntryIds != null && !importedEntryIds.isEmpty()) {
            for (String entryId : importedEntryIds) {
                QueueEntry stashedEntry = queueEntryRepository.findByUuid(entryId).orElse(null);
                if (stashedEntry == null) continue;
                Set<QueueEntryState> allowed = VALID_TRANSITIONS.getOrDefault(stashedEntry.getState(), Set.of());
                if (!allowed.contains(QueueEntryState.WAITING)) continue;

                Queue sourceQueue = stashedEntry.getQueue();

                QueueEntry newEntry = QueueEntry.builder()
                        .queue(newQueue)
                        .patient(stashedEntry.getPatient())
                        .tokenNumber(stashedEntry.getTokenNumber())
                        .state(QueueEntryState.WAITING)
                        .position(position++)
                        .complaintTags(stashedEntry.getComplaintTags())
                        .complaintText(stashedEntry.getComplaintText())
                        .isBilled(false)
                        .stashedFromQueue(sourceQueue)
                        .registeredAt(System.currentTimeMillis())
                        .build();
                queueEntryRepository.save(newEntry);

                if (stashedEntry.getTokenNumber() > maxToken) {
                    maxToken = stashedEntry.getTokenNumber();
                }

                guardStateTransition(stashedEntry, QueueEntryState.REMOVED);
                stashedEntry.setState(QueueEntryState.REMOVED);
                stashedEntry.setRemovalReason("imported_to_queue");
                queueEntryRepository.save(stashedEntry);
            }

            if (maxToken > newQueue.getLastToken()) {
                newQueue.setLastToken(maxToken);
                queueRepository.save(newQueue);
            }

            log.info("Stash imported: {} entries to queue {}", importedEntryIds.size(), newQueueId);
        }
    }

    // ==================== Non-Queue Event Handlers ====================

    @SuppressWarnings("unchecked")
    private void processVisitSaved(SyncPushRequest.SyncEvent event, User user, Organization org, Map<String, Object> payload) {
        String patientId = event.getTargetEntity();
        String visitId = (String) payload.get("visitId");
        if (visitId == null || visitId.isBlank()) {
            throw new IllegalArgumentException("visitId is required in visit_saved payload");
        }
        String queueEntryId = (String) payload.get("queueEntryId");
        List<String> complaintTags = (List<String>) payload.get("complaintTags");
        Map<String, Object> data = (Map<String, Object>) payload.get("data");
        Integer schemaVersion = payload.get("schemaVersion") != null ? (Integer) payload.get("schemaVersion") : 1;

        Patient patient = patientRepository.findByUuid(patientId)
                .orElseThrow(() -> new ResourceNotFoundException("Patient", patientId));
        if (!patient.getOrganization().getId().equals(org.getId())) {
            throw new ResourceNotFoundException("Patient", patientId);
        }

        OrgMember createdBy = orgMemberRepository.findByOrgIdAndUserUuid(org.getId(), user.getUuid())
                .orElseThrow(() -> new ResourceNotFoundException("Member", user.getUuid()));

        QueueEntry queueEntry = null;
        if (queueEntryId != null) {
            queueEntry = queueEntryRepository.findByUuid(queueEntryId).orElse(null);
            if (queueEntry != null && !queueEntry.getQueue().getOrganization().getId().equals(org.getId())) {
                queueEntry = null;
            }
        }

        long visitMs = event.getDeviceTimestamp() != null ? event.getDeviceTimestamp() : System.currentTimeMillis();
        java.time.LocalDate visitDate = Instant.ofEpochMilli(visitMs)
                .atZone(java.time.ZoneId.of("Asia/Kolkata")).toLocalDate();

        Visit existingVisit = visitRepository.findByUuid(visitId).orElse(null);
        Visit savedVisit;

        if (existingVisit != null) {
            if (!existingVisit.getOrganization().getId().equals(org.getId())) {
                throw new ResourceNotFoundException("Visit", visitId);
            }
            if (!existingVisit.getPatient().getUuid().equals(patientId)) {
                throw new IllegalArgumentException("Visit " + visitId + " does not belong to patient " + patientId);
            }

            if (complaintTags != null) {
                existingVisit.setComplaintTags(toJson(complaintTags));
            }
            if (data != null) {
                existingVisit.setData(toJson(data));
            }
            existingVisit.setSchemaVersion(schemaVersion);
            visitRepository.save(existingVisit);
            savedVisit = existingVisit;

            log.info("Visit {} updated via sync for patient {}", visitId, patientId);
        } else {
            Visit visit = Visit.builder()
                    .patient(patient)
                    .organization(org)
                    .queueEntry(queueEntry)
                    .visitDate(visitDate)
                    .complaintTags(complaintTags != null ? toJson(complaintTags) : null)
                    .data(data != null ? toJson(data) : null)
                    .schemaVersion(schemaVersion)
                    .createdBy(createdBy)
                    .build();
            visit.setUuid(visitId);
            visitRepository.save(visit);
            savedVisit = visit;

            patient.setTotalVisits(patient.getTotalVisits() + 1);
            patient.setLastVisitDate(visitDate);
            if (complaintTags != null && !complaintTags.isEmpty()) {
                patient.setLastComplaintTags(toJson(complaintTags));
            }
            if (patient.getTotalVisits() > 3) {
                patient.setIsRegular(true);
            }
            patientRepository.save(patient);

            log.info("Visit {} created via sync for patient {} by user {}", visitId, patientId, user.getUuid());
        }

        // Link uploaded images to this visit (if imageIds provided in payload)
        List<String> imageIds = (List<String>) payload.get("imageIds");
        if (imageIds != null && !imageIds.isEmpty()) {
            for (String imageId : imageIds) {
                visitImageRepository.findByUuid(imageId).ifPresent(img -> {
                    if (img.getVisit() == null) {
                        img.setVisit(savedVisit);
                        visitImageRepository.save(img);
                    }
                });
            }
            log.info("Linked {} images to visit {}", imageIds.size(), visitId);
        }
    }

    @SuppressWarnings("unchecked")
    private void processBillCreated(SyncPushRequest.SyncEvent event, User user, Organization org, Map<String, Object> payload) {
        String billId = event.getTargetEntity();
        String patientId = (String) payload.get("patientId");
        String queueEntryId = (String) payload.get("queueEntryId");
        List<Map<String, Object>> items = (List<Map<String, Object>>) payload.get("items");
        Number totalAmountNum = (Number) payload.get("totalAmount");
        Boolean sendSMS = (Boolean) payload.get("sendSMS");

        if (patientId == null || patientId.isBlank()) {
            throw new IllegalArgumentException("patientId is required in bill_created payload");
        }

        if (billRepository.findByUuid(billId).isPresent()) {
            log.info("Bill {} already exists, skipping", billId);
            return;
        }

        Patient patient = patientRepository.findByUuid(patientId)
                .orElseThrow(() -> new ResourceNotFoundException("Patient", patientId));
        if (!patient.getOrganization().getId().equals(org.getId())) {
            throw new ResourceNotFoundException("Patient", patientId);
        }

        QueueEntry queueEntry = null;
        if (queueEntryId != null) {
            queueEntry = queueEntryRepository.findByUuid(queueEntryId).orElse(null);
            if (queueEntry != null && !queueEntry.getQueue().getOrganization().getId().equals(org.getId())) {
                queueEntry = null;
            }
        }

        OrgMember createdBy = orgMemberRepository.findByOrgIdAndUserUuid(org.getId(), user.getUuid())
                .orElseThrow(() -> new ResourceNotFoundException("Member", user.getUuid()));

        int totalAmount = 0;
        if (totalAmountNum != null) {
            totalAmount = totalAmountNum.intValue();
        } else if (items != null) {
            for (Map<String, Object> item : items) {
                Number amt = (Number) item.get("amount");
                if (amt != null) totalAmount += amt.intValue();
            }
        }

        String doctorName = null;
        Integer tokenNumber = null;
        if (queueEntry != null) {
            doctorName = queueEntry.getQueue().getDoctor().getUser().getName();
            tokenNumber = queueEntry.getTokenNumber();
        }

        Bill bill = Bill.builder()
                .organization(org)
                .patient(patient)
                .queueEntryId(queueEntry != null ? queueEntry.getId() : null)
                .totalAmount(java.math.BigDecimal.valueOf(totalAmount))
                .isPaid(false)
                .patientName(patient.getName())
                .patientPhone(patient.getPhone())
                .tokenNumber(tokenNumber)
                .doctorName(doctorName)
                .createdBy(createdBy)
                .build();
        bill.setUuid(billId);
        billRepository.save(bill);

        if (items != null) {
            int sortOrder = 0;
            for (Map<String, Object> itemData : items) {
                String name = (String) itemData.get("name");
                Number amount = (Number) itemData.get("amount");
                BillItem billItem = BillItem.builder()
                        .bill(bill)
                        .name(name != null ? name : "Item")
                        .amount(java.math.BigDecimal.valueOf(amount != null ? amount.intValue() : 0))
                        .sortOrder(sortOrder++)
                        .build();
                billItemRepository.save(billItem);
            }
        }

        if (queueEntry != null) {
            queueEntry.setIsBilled(true);
            queueEntry.setBill(bill);
            queueEntryRepository.save(queueEntry);
        }

        log.info("Bill {} created via sync for patient {} (total: {})", billId, patientId, totalAmount);

        if (Boolean.TRUE.equals(sendSMS)) {
            log.info("SMS bill notification requested for bill {}", billId);
        }
    }

    private void processBillUpdated(SyncPushRequest.SyncEvent event, User user, Organization org, Map<String, Object> payload) {
        String billId = event.getTargetEntity();

        Bill bill = billRepository.findByUuid(billId)
                .orElseThrow(() -> new ResourceNotFoundException("Bill", billId));

        if (!bill.getOrganization().getId().equals(org.getId())) {
            throw new ResourceNotFoundException("Bill", billId);
        }

        Boolean isPaid = (Boolean) payload.get("isPaid");
        if (Boolean.TRUE.equals(isPaid)) {
            bill.setIsPaid(true);
            long paidMs = event.getDeviceTimestamp() != null ? event.getDeviceTimestamp() : System.currentTimeMillis();
            Number paidAtFromPayload = (Number) payload.get("paidAt");
            if (paidAtFromPayload != null) {
                bill.setPaidAt(Instant.ofEpochMilli(paidAtFromPayload.longValue()));
            } else {
                bill.setPaidAt(Instant.ofEpochMilli(paidMs));
            }
        } else if (Boolean.FALSE.equals(isPaid)) {
            bill.setIsPaid(false);
            bill.setPaidAt(null);
        }

        billRepository.save(bill);
        log.info("Bill {} updated via sync (isPaid={})", billId, bill.getIsPaid());
    }

    @SuppressWarnings("unchecked")
    private void processBillPaid(SyncPushRequest.SyncEvent event, User user, Organization org, Map<String, Object> payload) {
        String billId = event.getTargetEntity();
        String patientId = (String) payload.get("patientId");
        String queueEntryId = (String) payload.get("queueEntryId");
        List<Map<String, Object>> items = (List<Map<String, Object>>) payload.get("items");
        Number totalAmountNum = (Number) payload.get("totalAmount");
        Number paidAtNum = (Number) payload.get("paidAt");

        if (patientId == null || patientId.isBlank()) {
            throw new IllegalArgumentException("patientId is required in bill_paid payload");
        }

        if (billRepository.findByUuid(billId).isPresent()) {
            log.info("Bill {} already exists, skipping", billId);
            return;
        }

        Patient patient = patientRepository.findByUuid(patientId)
                .orElseThrow(() -> new ResourceNotFoundException("Patient", patientId));
        if (!patient.getOrganization().getId().equals(org.getId())) {
            throw new ResourceNotFoundException("Patient", patientId);
        }

        QueueEntry queueEntry = null;
        if (queueEntryId != null) {
            queueEntry = queueEntryRepository.findByUuid(queueEntryId).orElse(null);
            if (queueEntry != null && !queueEntry.getQueue().getOrganization().getId().equals(org.getId())) {
                queueEntry = null;
            }
            if (queueEntry != null && billRepository.findByQueueEntryId(queueEntry.getId()).isPresent()) {
                throw new IllegalArgumentException("Bill already exists for queue entry: " + queueEntryId);
            }
        }

        OrgMember createdBy = orgMemberRepository.findByOrgIdAndUserUuid(org.getId(), user.getUuid())
                .orElseThrow(() -> new ResourceNotFoundException("Member", user.getUuid()));

        int totalAmount = 0;
        if (totalAmountNum != null) {
            totalAmount = totalAmountNum.intValue();
        } else if (items != null) {
            for (Map<String, Object> item : items) {
                Number amt = (Number) item.get("amount");
                if (amt != null) totalAmount += amt.intValue();
            }
        }

        String doctorName = null;
        Integer tokenNumber = null;
        if (queueEntry != null) {
            doctorName = queueEntry.getQueue().getDoctor().getUser().getName();
            tokenNumber = queueEntry.getTokenNumber();
        }

        long paidMs = paidAtNum != null ? paidAtNum.longValue()
                : (event.getDeviceTimestamp() != null ? event.getDeviceTimestamp() : System.currentTimeMillis());

        Bill bill = Bill.builder()
                .organization(org)
                .patient(patient)
                .queueEntryId(queueEntry != null ? queueEntry.getId() : null)
                .totalAmount(java.math.BigDecimal.valueOf(totalAmount))
                .isPaid(true)
                .paidAt(Instant.ofEpochMilli(paidMs))
                .patientName(patient.getName())
                .patientPhone(patient.getPhone())
                .tokenNumber(tokenNumber)
                .doctorName(doctorName)
                .createdBy(createdBy)
                .build();
        bill.setUuid(billId);
        billRepository.save(bill);

        if (items != null) {
            int sortOrder = 0;
            for (Map<String, Object> itemData : items) {
                String name = (String) itemData.get("name");
                Number amount = (Number) itemData.get("amount");
                BillItem billItem = BillItem.builder()
                        .bill(bill)
                        .name(name != null ? name : "Item")
                        .amount(java.math.BigDecimal.valueOf(amount != null ? amount.intValue() : 0))
                        .sortOrder(sortOrder++)
                        .build();
                billItemRepository.save(billItem);
            }
        }

        if (queueEntry != null) {
            queueEntry.setIsBilled(true);
            queueEntry.setBill(bill);
            queueEntryRepository.save(queueEntry);
        }

        log.info("Bill {} created+paid via sync for patient {} (total: {})", billId, patientId, totalAmount);
    }

    // ==================== Utilities ====================

    private void guardStateTransition(QueueEntry entry, QueueEntryState targetState) {
        QueueEntryState currentState = entry.getState();
        Set<QueueEntryState> allowed = VALID_TRANSITIONS.getOrDefault(currentState, Set.of());
        if (!allowed.contains(targetState)) {
            throw new RuntimeException(
                    "Invalid state transition: " + currentState + " → " + targetState
                            + " for entry " + entry.getUuid());
        }
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("Error converting to JSON", e);
            return null;
        }
    }
}
