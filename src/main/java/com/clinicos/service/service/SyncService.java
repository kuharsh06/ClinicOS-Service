package com.clinicos.service.service;

import com.clinicos.service.dto.request.SyncPushRequest;
import com.clinicos.service.dto.response.SyncPullResponse;
import com.clinicos.service.dto.response.SyncPushResponse;
import com.clinicos.service.entity.*;
import com.clinicos.service.enums.EventStatus;
import com.clinicos.service.exception.ResourceNotFoundException;
import com.clinicos.service.enums.Gender;
import com.clinicos.service.enums.QueueEntryState;
import com.clinicos.service.enums.QueueStatus;
import com.clinicos.service.repository.*;
import com.clinicos.service.security.CustomUserDetails;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SyncService {

    private final EventStoreRepository eventStoreRepository;
    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final OrgMemberRepository orgMemberRepository;
    private final OrgMemberRoleRepository orgMemberRoleRepository;
    private final QueueRepository queueRepository;
    private final QueueEntryRepository queueEntryRepository;
    private final PatientRepository patientRepository;
    private final VisitRepository visitRepository;
    private final BillRepository billRepository;
    private final BillItemRepository billItemRepository;
    private final ObjectMapper objectMapper;

    // Valid state transitions per API contract v3.1 §12.2
    // States: waiting, now_serving (CALLED), completed, removed, stashed
    // step_out: now_serving → waiting (not a separate state)
    // patient_removed: only from waiting (not from now_serving)
    private static final Map<QueueEntryState, Set<QueueEntryState>> VALID_TRANSITIONS = Map.of(
            QueueEntryState.WAITING, Set.of(QueueEntryState.CALLED, QueueEntryState.REMOVED, QueueEntryState.STASHED),
            QueueEntryState.CALLED, Set.of(QueueEntryState.COMPLETED, QueueEntryState.WAITING),
            QueueEntryState.STASHED, Set.of(QueueEntryState.WAITING, QueueEntryState.REMOVED),
            QueueEntryState.COMPLETED, Set.of(),
            QueueEntryState.REMOVED, Set.of()
    );

    // Event type to allowed roles mapping
    // Doctor can perform all operations that assistant can (single-doctor clinic scenario)
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

    @Transactional
    public SyncPushResponse pushEvents(SyncPushRequest request, CustomUserDetails userDetails) {
        List<SyncPushResponse.AcceptedEvent> accepted = new ArrayList<>();
        List<SyncPushResponse.RejectedEvent> rejected = new ArrayList<>();
        long serverTimestamp = System.currentTimeMillis();

        // Get user and org
        User user = userRepository.findById(userDetails.getId())
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (userDetails.getOrgId() == null) {
            throw new RuntimeException("Cannot sync events without an organization");
        }
        Organization org = organizationRepository.findById(userDetails.getOrgId())
                .orElseThrow(() -> new RuntimeException("Organization not found"));

        // Get user's actual roles from DB
        Set<String> actualRoles = userDetails.getRoles() != null
                ? new HashSet<>(userDetails.getRoles())
                : new HashSet<>();

        for (SyncPushRequest.SyncEvent event : request.getEvents()) {
            try {
                // 1. DEDUP by eventId
                Optional<EventStore> existing = eventStoreRepository.findByUuid(event.getEventId());
                if (existing.isPresent()) {
                    rejected.add(SyncPushResponse.RejectedEvent.builder()
                            .eventId(event.getEventId())
                            .code("DUPLICATE_IGNORED")
                            .reason("Event already processed")
                            .build());
                    continue;
                }

                // 2. ROLE CHECK
                List<String> allowedRoles = EVENT_ALLOWED_ROLES.get(event.getEventType());
                if (allowedRoles == null) {
                    rejected.add(SyncPushResponse.RejectedEvent.builder()
                            .eventId(event.getEventId())
                            .code("INVALID_EVENT_TYPE")
                            .reason("Unknown event type: " + event.getEventType())
                            .build());
                    continue;
                }

                boolean hasAllowedRole = actualRoles.stream()
                        .anyMatch(allowedRoles::contains);

                if (!hasAllowedRole) {
                    rejected.add(SyncPushResponse.RejectedEvent.builder()
                            .eventId(event.getEventId())
                            .code("UNAUTHORIZED_ROLE")
                            .reason("User roles " + actualRoles + " cannot perform " + event.getEventType())
                            .build());
                    continue;
                }

                // 3. SCHEMA CHECK (basic version check)
                if (event.getSchemaVersion() == null || event.getSchemaVersion() < 1) {
                    rejected.add(SyncPushResponse.RejectedEvent.builder()
                            .eventId(event.getEventId())
                            .code("SCHEMA_MISMATCH")
                            .reason("Invalid schema version")
                            .build());
                    continue;
                }

                // 4. STATE GUARD - would check entity state here
                // For now, we'll accept and let event handlers validate

                // 5. STORE as PENDING first
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

                // Set the UUID to the eventId from client
                eventStore.setUuid(event.getEventId());

                eventStoreRepository.save(eventStore);

                // 6. Process event (apply to target entities)
                try {
                    processEvent(event, user, org);

                    // Mark as APPLIED only after successful processing
                    eventStore.setStatus(EventStatus.APPLIED);
                    eventStoreRepository.save(eventStore);

                    accepted.add(SyncPushResponse.AcceptedEvent.builder()
                            .eventId(event.getEventId())
                            .serverReceivedAt(serverTimestamp)
                            .build());

                    log.info("Event {} processed: {} on {}", event.getEventId(), event.getEventType(), event.getTargetEntity());

                } catch (Exception processingEx) {
                    // Mark as REJECTED with reason - event_store stays consistent
                    eventStore.setStatus(EventStatus.REJECTED);
                    eventStore.setRejectionCode("PROCESSING_ERROR");
                    eventStore.setRejectionReason(processingEx.getMessage());
                    eventStoreRepository.save(eventStore);

                    rejected.add(SyncPushResponse.RejectedEvent.builder()
                            .eventId(event.getEventId())
                            .code("PROCESSING_ERROR")
                            .reason(processingEx.getMessage())
                            .build());

                    log.error("Event {} failed processing: {}", event.getEventId(), processingEx.getMessage());
                }

            } catch (Exception e) {
                log.error("Error processing event {}: {}", event.getEventId(), e.getMessage());
                rejected.add(SyncPushResponse.RejectedEvent.builder()
                        .eventId(event.getEventId())
                        .code("PROCESSING_ERROR")
                        .reason(e.getMessage())
                        .build());
            }
        }

        return SyncPushResponse.builder()
                .accepted(accepted)
                .rejected(rejected)
                .serverTimestamp(serverTimestamp)
                .build();
    }

    @Transactional(readOnly = true)
    public SyncPullResponse pullEvents(Long since, String deviceId, Integer limit, CustomUserDetails userDetails) {
        if (limit == null || limit > 500) {
            limit = 100;
        }

        Integer orgId = userDetails.getOrgId();
        if (orgId == null) {
            return SyncPullResponse.builder()
                    .events(Collections.emptyList())
                    .serverTimestamp(System.currentTimeMillis())
                    .hasMore(false)
                    .build();
        }

        // Find events for this org after 'since', excluding this device
        List<EventStore> events = eventStoreRepository.findEventsForPull(
                orgId,
                since != null ? since : 0L,
                deviceId,
                PageRequest.of(0, limit + 1)  // Fetch one extra to check hasMore
        );

        boolean hasMore = events.size() > limit;
        if (hasMore) {
            events = events.subList(0, limit);
        }

        // Get user's permissions for clinical data redaction
        boolean canViewFullClinical = userDetails.hasPermission("patient:view_clinical");

        // Convert to DTOs
        List<SyncPullResponse.SyncEventDto> eventDtos = events.stream()
                .map(e -> convertToDto(e, canViewFullClinical))
                .collect(Collectors.toList());

        // Get latest serverReceivedAt
        Long serverTimestamp = events.isEmpty()
                ? System.currentTimeMillis()
                : events.get(events.size() - 1).getServerReceivedAt();

        return SyncPullResponse.builder()
                .events(eventDtos)
                .serverTimestamp(serverTimestamp)
                .hasMore(hasMore)
                .build();
    }

    private void processEvent(SyncPushRequest.SyncEvent event, User user, Organization org) {
        Map<String, Object> payload = event.getPayload();

        switch (event.getEventType()) {
            case "patient_added":
                processPatientAdded(event, user, org, payload);
                break;
            case "patient_removed":
                processPatientRemoved(event, payload);
                break;
            case "call_now":
                processCallNow(event, payload);
                break;
            case "step_out":
                processStepOut(event, payload);
                break;
            case "mark_complete":
                processMarkComplete(event, payload);
                break;
            case "queue_paused":
                processQueuePaused(event, payload);
                break;
            case "queue_resumed":
                processQueueResumed(event, payload);
                break;
            case "queue_ended":
                processQueueEnded(event, payload);
                break;
            case "stash_imported":
                processStashImported(event, user, org, payload);
                break;
            case "visit_saved":
                processVisitSaved(event, user, org, payload);
                break;
            case "bill_created":
                processBillCreated(event, user, org, payload);
                break;
            case "bill_updated":
                processBillUpdated(event, user, org, payload);
                break;
            case "bill_paid":
                processBillPaid(event, user, org, payload);
                break;
            default:
                log.warn("Unknown event type: {}", event.getEventType());
        }
    }

    /**
     * Process patient_added event.
     * Creates/updates patient, creates queue entry, auto-creates queue if needed.
     */
    @SuppressWarnings("unchecked")
    private void processPatientAdded(SyncPushRequest.SyncEvent event, User user, Organization org, Map<String, Object> payload) {
        String queueId = (String) payload.get("queueId");
        String patientId = (String) payload.get("patientId");
        Integer tokenNumber = (Integer) payload.get("tokenNumber");
        String doctorId = (String) payload.get("doctorId");

        // Patient info
        Map<String, Object> patientInfo = (Map<String, Object>) payload.get("patient");
        String phone = patientInfo != null ? (String) patientInfo.get("phone") : null;
        String name = patientInfo != null ? (String) patientInfo.get("name") : null;
        Integer age = patientInfo != null ? (Integer) patientInfo.get("age") : null;
        String gender = patientInfo != null ? (String) patientInfo.get("gender") : null;

        // Complaint info
        List<String> complaintTags = (List<String>) payload.get("complaintTags");
        String complaintText = (String) payload.get("complaintText");

        // Find or create patient
        Patient patient = patientRepository.findByUuid(patientId).orElse(null);
        if (patient == null && phone != null) {
            // Try to find by phone
            patient = patientRepository.findByOrganizationIdAndCountryCodeAndPhone(
                    org.getId(), "+91", phone).orElse(null);
        }

        if (patient == null) {
            // Create new patient
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
            // Find the doctor member
            OrgMember doctor = orgMemberRepository.findByOrganizationIdWithUser(org.getId())
                    .stream()
                    .filter(m -> m.getUser().getUuid().equals(doctorId))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Doctor not found: " + doctorId));

            // Check if doctor already has an active/paused queue — one doctor, one queue
            List<Queue> existingActive = queueRepository.findByDoctorIdAndStatusIn(
                    doctor.getId(), List.of(QueueStatus.ACTIVE, QueueStatus.PAUSED));
            if (!existingActive.isEmpty()) {
                throw new IllegalStateException("Doctor already has an active queue: " + existingActive.get(0).getUuid()
                        + ". End it before starting a new one.");
            }

            // Create new queue
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

        // Update queue's last token
        if (tokenNumber != null && tokenNumber > queue.getLastToken()) {
            queue.setLastToken(tokenNumber);
            queueRepository.save(queue);
        }

        // Create queue entry
        QueueEntry entry = QueueEntry.builder()
                .queue(queue)
                .patient(patient)
                .tokenNumber(tokenNumber != null ? tokenNumber : queue.getLastToken())
                .state(QueueEntryState.WAITING)
                .position(tokenNumber)
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

    /**
     * Validates that a queue entry state transition is allowed.
     * Throws RuntimeException if the transition is invalid — caught by the
     * inner try/catch in pushEvents() which marks the event as REJECTED.
     */
    private void guardStateTransition(QueueEntry entry, QueueEntryState targetState) {
        QueueEntryState currentState = entry.getState();
        Set<QueueEntryState> allowed = VALID_TRANSITIONS.getOrDefault(currentState, Set.of());
        if (!allowed.contains(targetState)) {
            throw new RuntimeException(
                    "Invalid state transition: " + currentState + " → " + targetState
                            + " for entry " + entry.getUuid());
        }
    }

    /**
     * Process patient_removed event.
     */
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

    /**
     * Process call_now event - call patient for consultation.
     * Only one patient can be now_serving at a time per queue.
     */
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

    /**
     * Process step_out event — patient stepped out, goes to end of waiting queue.
     * Per contract: now_serving → waiting (step_out is an action, not a state)
     * Patient goes to the END of the queue, not their original position.
     */
    private void processStepOut(SyncPushRequest.SyncEvent event, Map<String, Object> payload) {
        String entryId = event.getTargetEntity();
        String reason = (String) payload.get("reason");

        QueueEntry entry = queueEntryRepository.findByUuid(entryId)
                .orElseThrow(() -> new RuntimeException("Queue entry not found: " + entryId));

        guardStateTransition(entry, QueueEntryState.WAITING);

        // Calculate end-of-queue position
        List<QueueEntry> allEntries = queueEntryRepository.findByQueueIdOrderByPositionAsc(entry.getQueue().getId());
        int maxPosition = allEntries.stream()
                .filter(e -> e.getState() == QueueEntryState.WAITING)
                .mapToInt(e -> e.getPosition() != null ? e.getPosition() : 0)
                .max()
                .orElse(0);

        entry.setState(QueueEntryState.WAITING);
        entry.setPosition(maxPosition + 1); // end of queue
        entry.setStepOutReason(reason);
        entry.setSteppedOutAt(event.getDeviceTimestamp() != null ? event.getDeviceTimestamp() : System.currentTimeMillis());
        entry.setCalledAt(null); // reset called timestamp
        queueEntryRepository.save(entry);

        log.info("Patient stepped out, moved to end of waiting queue (position {}): entry={}", maxPosition + 1, entryId);
    }

    /**
     * Process mark_complete event - consultation completed.
     */
    private void processMarkComplete(SyncPushRequest.SyncEvent event, Map<String, Object> payload) {
        String entryId = event.getTargetEntity();

        QueueEntry entry = queueEntryRepository.findByUuid(entryId)
                .orElseThrow(() -> new RuntimeException("Queue entry not found: " + entryId));

        guardStateTransition(entry, QueueEntryState.COMPLETED);
        entry.setState(QueueEntryState.COMPLETED);
        entry.setCompletedAt(event.getDeviceTimestamp() != null ? event.getDeviceTimestamp() : System.currentTimeMillis());
        queueEntryRepository.save(entry);

        // Update patient visit count
        Patient patient = entry.getPatient();
        patient.setTotalVisits(patient.getTotalVisits() + 1);
        // Derive visit date from device timestamp (offline-correct), not server time
        long completedMs = event.getDeviceTimestamp() != null ? event.getDeviceTimestamp() : System.currentTimeMillis();
        patient.setLastVisitDate(Instant.ofEpochMilli(completedMs).atZone(java.time.ZoneId.of("Asia/Kolkata")).toLocalDate());
        patient.setLastComplaintTags(entry.getComplaintTags());
        patientRepository.save(patient);

        log.info("Patient consultation completed: entry={}", entryId);
    }

    /**
     * Process queue_paused event.
     */
    private void processQueuePaused(SyncPushRequest.SyncEvent event, Map<String, Object> payload) {
        String queueId = event.getTargetEntity();

        Queue queue = queueRepository.findByUuid(queueId)
                .orElseThrow(() -> new RuntimeException("Queue not found: " + queueId));

        // Guard: only ACTIVE queues can be paused
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

    /**
     * Process queue_resumed event.
     */
    private void processQueueResumed(SyncPushRequest.SyncEvent event, Map<String, Object> payload) {
        String queueId = event.getTargetEntity();

        Queue queue = queueRepository.findByUuid(queueId)
                .orElseThrow(() -> new RuntimeException("Queue not found: " + queueId));

        // Guard: only PAUSED queues can be resumed
        if (queue.getStatus() == QueueStatus.ENDED) {
            throw new IllegalStateException("Cannot resume an ENDED queue: " + queueId);
        }
        if (queue.getStatus() == QueueStatus.ACTIVE) {
            log.info("Queue {} already active, ignoring", queueId);
            return;
        }

        // Calculate paused duration using device timestamp (offline-correct)
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

    /**
     * Process queue_ended event.
     * Ends the queue and stashes waiting patients.
     */
    @SuppressWarnings("unchecked")
    private void processQueueEnded(SyncPushRequest.SyncEvent event, Map<String, Object> payload) {
        String queueId = event.getTargetEntity();
        List<String> stashedEntryIds = (List<String>) payload.get("stashedEntryIds");

        Queue queue = queueRepository.findByUuid(queueId)
                .orElseThrow(() -> new RuntimeException("Queue not found: " + queueId));

        // Guard: already ended queues are no-op
        if (queue.getStatus() == QueueStatus.ENDED) {
            log.info("Queue {} already ended, ignoring", queueId);
            return;
        }

        // End the queue
        queue.setStatus(QueueStatus.ENDED);
        // Use device timestamp for accurate end time (offline-correct)
        long endedMs = event.getDeviceTimestamp() != null ? event.getDeviceTimestamp() : System.currentTimeMillis();
        queue.setEndedAt(Instant.ofEpochMilli(endedMs));
        queueRepository.save(queue);

        // Auto-complete any currently serving (CALLED) patients per contract
        List<QueueEntry> allEntries = queueEntryRepository.findByQueueIdOrderByPositionAsc(queue.getId());
        for (QueueEntry entry : allEntries) {
            if (entry.getState() == QueueEntryState.CALLED) {
                entry.setState(QueueEntryState.COMPLETED);
                entry.setCompletedAt(endedMs);
                queueEntryRepository.save(entry);
                log.info("Auto-completed now_serving entry {} on queue end", entry.getUuid());
            }
        }

        // Stash specified entries
        if (stashedEntryIds != null && !stashedEntryIds.isEmpty()) {
            for (String entryId : stashedEntryIds) {
                QueueEntry entry = queueEntryRepository.findByUuid(entryId).orElse(null);
                if (entry == null) continue;
                // Skip entries not in WAITING state (may have changed since event was created)
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

    /**
     * Process stash_imported event.
     * Creates new queue (if needed) and imports stashed patients.
     */
    @SuppressWarnings("unchecked")
    private void processStashImported(SyncPushRequest.SyncEvent event, User user, Organization org, Map<String, Object> payload) {
        String newQueueId = event.getTargetEntity(); // New queue ID generated by client
        List<String> importedEntryIds = (List<String>) payload.get("importedEntryIds");
        String doctorId = (String) payload.get("doctorId");

        // Find the doctor member
        OrgMember doctor = orgMemberRepository.findByOrganizationIdWithUser(org.getId())
                .stream()
                .filter(m -> m.getUser().getUuid().equals(doctorId))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Doctor not found: " + doctorId));

        // Find or create the new queue
        Queue newQueue = queueRepository.findByUuid(newQueueId).orElse(null);
        if (newQueue == null) {
            // Check if doctor already has an active/paused queue
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

        // Import the stashed entries
        int position = 1;
        int maxToken = 0;

        if (importedEntryIds != null && !importedEntryIds.isEmpty()) {
            for (String entryId : importedEntryIds) {
                QueueEntry stashedEntry = queueEntryRepository.findByUuid(entryId).orElse(null);
                if (stashedEntry == null) continue;
                // Skip entries not in STASHED state (may have been imported already)
                Set<QueueEntryState> allowed = VALID_TRANSITIONS.getOrDefault(stashedEntry.getState(), Set.of());
                if (!allowed.contains(QueueEntryState.WAITING)) continue;

                Queue sourceQueue = stashedEntry.getQueue();

                // Create new entry in new queue
                QueueEntry newEntry = QueueEntry.builder()
                        .queue(newQueue)
                        .patient(stashedEntry.getPatient())
                        .tokenNumber(stashedEntry.getTokenNumber()) // Preserve original token
                        .state(QueueEntryState.WAITING)
                        .position(position++)
                        .complaintTags(stashedEntry.getComplaintTags())
                        .complaintText(stashedEntry.getComplaintText())
                        .isBilled(false)
                        .stashedFromQueue(sourceQueue)
                        .registeredAt(System.currentTimeMillis())
                        .build();
                queueEntryRepository.save(newEntry);

                // Track max token
                if (stashedEntry.getTokenNumber() > maxToken) {
                    maxToken = stashedEntry.getTokenNumber();
                }

                // Mark original entry as imported (STASHED → REMOVED)
                guardStateTransition(stashedEntry, QueueEntryState.REMOVED);
                stashedEntry.setState(QueueEntryState.REMOVED);
                stashedEntry.setRemovalReason("imported_to_queue");
                queueEntryRepository.save(stashedEntry);
            }

            // Update queue's last token
            if (maxToken > newQueue.getLastToken()) {
                newQueue.setLastToken(maxToken);
                queueRepository.save(newQueue);
            }

            log.info("Stash imported: {} entries to queue {}", importedEntryIds.size(), newQueueId);
        }
    }

    /**
     * Process visit_saved event.
     * Creates or updates a visit record for a patient.
     * targetEntity = patientId, payload.visitId = visit UUID.
     */
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

        // Find patient and validate org ownership
        Patient patient = patientRepository.findByUuid(patientId)
                .orElseThrow(() -> new ResourceNotFoundException("Patient", patientId));
        if (!patient.getOrganization().getId().equals(org.getId())) {
            throw new ResourceNotFoundException("Patient", patientId);
        }

        // Find the doctor member who created the visit
        OrgMember createdBy = orgMemberRepository.findByOrgIdAndUserUuid(org.getId(), user.getUuid())
                .orElseThrow(() -> new ResourceNotFoundException("Member", user.getUuid()));

        // Find queue entry if provided, validate org ownership
        QueueEntry queueEntry = null;
        if (queueEntryId != null) {
            queueEntry = queueEntryRepository.findByUuid(queueEntryId).orElse(null);
            if (queueEntry != null && !queueEntry.getQueue().getOrganization().getId().equals(org.getId())) {
                queueEntry = null; // silently ignore cross-org entry
            }
        }

        // Derive visit date from device timestamp (offline-correct)
        long visitMs = event.getDeviceTimestamp() != null ? event.getDeviceTimestamp() : System.currentTimeMillis();
        java.time.LocalDate visitDate = java.time.Instant.ofEpochMilli(visitMs)
                .atZone(java.time.ZoneId.of("Asia/Kolkata")).toLocalDate();

        // Check if visit already exists (update case)
        Visit existingVisit = visitRepository.findByUuid(visitId).orElse(null);

        if (existingVisit != null) {
            // Validate org ownership on update
            if (!existingVisit.getOrganization().getId().equals(org.getId())) {
                throw new ResourceNotFoundException("Visit", visitId);
            }
            // Validate visit belongs to the target patient
            if (!existingVisit.getPatient().getUuid().equals(patientId)) {
                throw new IllegalArgumentException("Visit " + visitId + " does not belong to patient " + patientId);
            }

            // Update existing visit
            if (complaintTags != null) {
                existingVisit.setComplaintTags(toJson(complaintTags));
            }
            if (data != null) {
                existingVisit.setData(toJson(data));
            }
            existingVisit.setSchemaVersion(schemaVersion);
            visitRepository.save(existingVisit);

            log.info("Visit {} updated via sync for patient {}", visitId, patientId);
        } else {
            // Create new visit
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

            // Update patient stats
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
    }

    /**
     * Process bill_created event.
     * Creates a new bill with items for a patient's queue entry.
     * targetEntity = billId (client-generated UUID).
     */
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

        // Check if bill already exists (idempotent)
        if (billRepository.findByUuid(billId).isPresent()) {
            log.info("Bill {} already exists, skipping", billId);
            return;
        }

        // Find and validate patient
        Patient patient = patientRepository.findByUuid(patientId)
                .orElseThrow(() -> new ResourceNotFoundException("Patient", patientId));
        if (!patient.getOrganization().getId().equals(org.getId())) {
            throw new ResourceNotFoundException("Patient", patientId);
        }

        // Find queue entry if provided, validate org
        QueueEntry queueEntry = null;
        if (queueEntryId != null) {
            queueEntry = queueEntryRepository.findByUuid(queueEntryId).orElse(null);
            if (queueEntry != null && !queueEntry.getQueue().getOrganization().getId().equals(org.getId())) {
                queueEntry = null;
            }
        }

        // Find creator member
        OrgMember createdBy = orgMemberRepository.findByOrgIdAndUserUuid(org.getId(), user.getUuid())
                .orElseThrow(() -> new ResourceNotFoundException("Member", user.getUuid()));

        // Calculate total from items if not provided
        int totalAmount = 0;
        if (totalAmountNum != null) {
            totalAmount = totalAmountNum.intValue();
        } else if (items != null) {
            for (Map<String, Object> item : items) {
                Number amt = (Number) item.get("amount");
                if (amt != null) totalAmount += amt.intValue();
            }
        }

        // Get doctor name and token from queue entry
        String doctorName = null;
        Integer tokenNumber = null;
        if (queueEntry != null) {
            doctorName = queueEntry.getQueue().getDoctor().getUser().getName();
            tokenNumber = queueEntry.getTokenNumber();
        }

        // Create bill
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

        // Create bill items
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

        // Mark queue entry as billed
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

    /**
     * Process bill_updated event.
     * Currently only supports marking a bill as paid.
     * targetEntity = billId.
     */
    private void processBillUpdated(SyncPushRequest.SyncEvent event, User user, Organization org, Map<String, Object> payload) {
        String billId = event.getTargetEntity();

        Bill bill = billRepository.findByUuid(billId)
                .orElseThrow(() -> new ResourceNotFoundException("Bill", billId));

        // Validate org ownership
        if (!bill.getOrganization().getId().equals(org.getId())) {
            throw new ResourceNotFoundException("Bill", billId);
        }

        // Update paid status
        Boolean isPaid = (Boolean) payload.get("isPaid");
        if (Boolean.TRUE.equals(isPaid)) {
            bill.setIsPaid(true);
            // Use deviceTimestamp for paidAt (offline-correct)
            long paidMs = event.getDeviceTimestamp() != null ? event.getDeviceTimestamp() : System.currentTimeMillis();
            Number paidAtFromPayload = (Number) payload.get("paidAt");
            if (paidAtFromPayload != null) {
                bill.setPaidAt(java.time.Instant.ofEpochMilli(paidAtFromPayload.longValue()));
            } else {
                bill.setPaidAt(java.time.Instant.ofEpochMilli(paidMs));
            }
        } else if (Boolean.FALSE.equals(isPaid)) {
            bill.setIsPaid(false);
            bill.setPaidAt(null);
        }

        billRepository.save(bill);
        log.info("Bill {} updated via sync (isPaid={})", billId, bill.getIsPaid());
    }

    /**
     * Process bill_paid event — single event that creates bill + marks paid.
     * Combines bill_created + bill_updated into one atomic operation.
     * targetEntity = billId (client-generated UUID).
     */
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

        // Idempotent — if bill already exists, skip
        if (billRepository.findByUuid(billId).isPresent()) {
            log.info("Bill {} already exists, skipping", billId);
            return;
        }

        // Find and validate patient
        Patient patient = patientRepository.findByUuid(patientId)
                .orElseThrow(() -> new ResourceNotFoundException("Patient", patientId));
        if (!patient.getOrganization().getId().equals(org.getId())) {
            throw new ResourceNotFoundException("Patient", patientId);
        }

        // Find queue entry if provided
        QueueEntry queueEntry = null;
        if (queueEntryId != null) {
            queueEntry = queueEntryRepository.findByUuid(queueEntryId).orElse(null);
            if (queueEntry != null && !queueEntry.getQueue().getOrganization().getId().equals(org.getId())) {
                queueEntry = null;
            }
            // Guard: prevent duplicate bills for the same queue entry (multi-device race)
            if (queueEntry != null && billRepository.findByQueueEntryId(queueEntry.getId()).isPresent()) {
                throw new IllegalArgumentException("Bill already exists for queue entry: " + queueEntryId);
            }
        }

        // Find creator member
        OrgMember createdBy = orgMemberRepository.findByOrgIdAndUserUuid(org.getId(), user.getUuid())
                .orElseThrow(() -> new ResourceNotFoundException("Member", user.getUuid()));

        // Calculate total
        int totalAmount = 0;
        if (totalAmountNum != null) {
            totalAmount = totalAmountNum.intValue();
        } else if (items != null) {
            for (Map<String, Object> item : items) {
                Number amt = (Number) item.get("amount");
                if (amt != null) totalAmount += amt.intValue();
            }
        }

        // Get doctor name and token from queue entry
        String doctorName = null;
        Integer tokenNumber = null;
        if (queueEntry != null) {
            doctorName = queueEntry.getQueue().getDoctor().getUser().getName();
            tokenNumber = queueEntry.getTokenNumber();
        }

        // Determine paidAt timestamp
        long paidMs = paidAtNum != null ? paidAtNum.longValue()
                : (event.getDeviceTimestamp() != null ? event.getDeviceTimestamp() : System.currentTimeMillis());

        // Create bill — already marked as PAID
        Bill bill = Bill.builder()
                .organization(org)
                .patient(patient)
                .queueEntryId(queueEntry != null ? queueEntry.getId() : null)
                .totalAmount(java.math.BigDecimal.valueOf(totalAmount))
                .isPaid(true)
                .paidAt(java.time.Instant.ofEpochMilli(paidMs))
                .patientName(patient.getName())
                .patientPhone(patient.getPhone())
                .tokenNumber(tokenNumber)
                .doctorName(doctorName)
                .createdBy(createdBy)
                .build();
        bill.setUuid(billId);
        billRepository.save(bill);

        // Create bill items
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

        // Mark queue entry as billed
        if (queueEntry != null) {
            queueEntry.setIsBilled(true);
            queueEntry.setBill(bill);
            queueEntryRepository.save(queueEntry);
        }

        log.info("Bill {} created+paid via sync for patient {} (total: {})", billId, patientId, totalAmount);
    }

    @SuppressWarnings("unchecked")
    private SyncPullResponse.SyncEventDto convertToDto(EventStore event, boolean canViewFullClinical) {
        Map<String, Object> payload = fromJson(event.getPayload());

        // Redact clinical data if user doesn't have permission
        if (!canViewFullClinical && "visit_saved".equals(event.getEventType())) {
            if (payload != null) {
                payload.put("data", null);
                payload.put("images", Collections.emptyList());
            }
        }

        // Get user roles (would need to fetch from member record)
        List<String> userRoles = Collections.emptyList();

        return SyncPullResponse.SyncEventDto.builder()
                .eventId(event.getUuid())
                .deviceId(event.getDeviceId())
                .userId(event.getUser().getUuid())
                .orgId(event.getOrganization().getUuid())
                .userRoles(userRoles)
                .eventType(event.getEventType())
                .targetEntity(event.getTargetEntityUuid())
                .targetTable(event.getTargetTable())
                .payload(payload)
                .deviceTimestamp(event.getDeviceTimestamp())
                .serverReceivedAt(event.getServerReceivedAt())
                .synced(true)
                .schemaVersion(event.getSchemaVersion())
                .build();
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJson(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            log.error("Error parsing JSON", e);
            return null;
        }
    }
}
