package com.clinicos.service.service;

import com.clinicos.service.dto.request.SyncPushRequest;
import com.clinicos.service.dto.response.SyncPullResponse;
import com.clinicos.service.dto.response.SyncPushResponse;
import com.clinicos.service.entity.*;
import com.clinicos.service.enums.EventStatus;
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
    private final ObjectMapper objectMapper;

    // Event type to allowed roles mapping
    private static final Map<String, List<String>> EVENT_ALLOWED_ROLES = Map.ofEntries(
            Map.entry("patient_added", List.of("assistant")),
            Map.entry("patient_removed", List.of("assistant")),
            Map.entry("call_now", List.of("assistant")),
            Map.entry("step_out", List.of("assistant")),
            Map.entry("mark_complete", List.of("assistant", "doctor")),
            Map.entry("queue_paused", List.of("assistant")),
            Map.entry("queue_resumed", List.of("assistant")),
            Map.entry("queue_ended", List.of("assistant")),
            Map.entry("stash_imported", List.of("assistant")),
            Map.entry("visit_saved", List.of("doctor")),
            Map.entry("bill_created", List.of("assistant", "doctor")),
            Map.entry("bill_updated", List.of("assistant", "doctor"))
    );

    @Transactional
    public SyncPushResponse pushEvents(SyncPushRequest request, CustomUserDetails userDetails) {
        List<SyncPushResponse.AcceptedEvent> accepted = new ArrayList<>();
        List<SyncPushResponse.RejectedEvent> rejected = new ArrayList<>();
        long serverTimestamp = System.currentTimeMillis();

        // Get user and org
        User user = userRepository.findById(userDetails.getId())
                .orElseThrow(() -> new RuntimeException("User not found"));

        Organization org = null;
        if (userDetails.getOrgId() != null) {
            org = organizationRepository.findById(userDetails.getOrgId())
                    .orElseThrow(() -> new RuntimeException("Organization not found"));
        }

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
        boolean canViewFullClinical = userDetails.hasPermission("patient:view_full");

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
                log.debug("Processing visit_saved for {}", event.getTargetEntity());
                // TODO: Implement visit processing
                break;
            case "bill_created":
                log.debug("Processing bill_created for {}", event.getTargetEntity());
                // TODO: Implement bill processing
                break;
            case "bill_updated":
                log.debug("Processing bill_updated for {}", event.getTargetEntity());
                // TODO: Implement bill update processing
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
        if (queue == null) {
            // Find the doctor member
            OrgMember doctor = orgMemberRepository.findByOrganizationIdWithUser(org.getId())
                    .stream()
                    .filter(m -> m.getUser().getUuid().equals(doctorId))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Doctor not found: " + doctorId));

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
     * Process patient_removed event.
     */
    private void processPatientRemoved(SyncPushRequest.SyncEvent event, Map<String, Object> payload) {
        String entryId = event.getTargetEntity();
        String reason = (String) payload.get("reason");

        QueueEntry entry = queueEntryRepository.findByUuid(entryId)
                .orElseThrow(() -> new RuntimeException("Queue entry not found: " + entryId));

        entry.setState(QueueEntryState.REMOVED);
        entry.setRemovalReason(reason != null ? reason : "removed");
        queueEntryRepository.save(entry);

        log.info("Patient removed from queue: entry={}", entryId);
    }

    /**
     * Process call_now event - call patient for consultation.
     */
    private void processCallNow(SyncPushRequest.SyncEvent event, Map<String, Object> payload) {
        String entryId = event.getTargetEntity();

        QueueEntry entry = queueEntryRepository.findByUuid(entryId)
                .orElseThrow(() -> new RuntimeException("Queue entry not found: " + entryId));

        entry.setState(QueueEntryState.CALLED);
        entry.setCalledAt(event.getDeviceTimestamp() != null ? event.getDeviceTimestamp() : System.currentTimeMillis());
        queueEntryRepository.save(entry);

        log.info("Patient called: entry={}", entryId);
    }

    /**
     * Process step_out event - patient stepped out temporarily.
     */
    private void processStepOut(SyncPushRequest.SyncEvent event, Map<String, Object> payload) {
        String entryId = event.getTargetEntity();
        String reason = (String) payload.get("reason");

        QueueEntry entry = queueEntryRepository.findByUuid(entryId)
                .orElseThrow(() -> new RuntimeException("Queue entry not found: " + entryId));

        entry.setState(QueueEntryState.STEPPED_OUT);
        entry.setStepOutReason(reason);
        entry.setSteppedOutAt(event.getDeviceTimestamp() != null ? event.getDeviceTimestamp() : System.currentTimeMillis());
        queueEntryRepository.save(entry);

        log.info("Patient stepped out: entry={}", entryId);
    }

    /**
     * Process mark_complete event - consultation completed.
     */
    private void processMarkComplete(SyncPushRequest.SyncEvent event, Map<String, Object> payload) {
        String entryId = event.getTargetEntity();

        QueueEntry entry = queueEntryRepository.findByUuid(entryId)
                .orElseThrow(() -> new RuntimeException("Queue entry not found: " + entryId));

        entry.setState(QueueEntryState.COMPLETED);
        entry.setCompletedAt(event.getDeviceTimestamp() != null ? event.getDeviceTimestamp() : System.currentTimeMillis());
        queueEntryRepository.save(entry);

        // Update patient visit count
        Patient patient = entry.getPatient();
        patient.setTotalVisits(patient.getTotalVisits() + 1);
        patient.setLastVisitDate(java.time.LocalDate.now());
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

        // Calculate paused duration
        if (queue.getPauseStartTime() != null) {
            long pausedDuration = System.currentTimeMillis() - queue.getPauseStartTime();
            queue.setTotalPausedMs(queue.getTotalPausedMs() + pausedDuration);
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

        // End the queue
        queue.setStatus(QueueStatus.ENDED);
        queue.setEndedAt(Instant.now());
        queueRepository.save(queue);

        // Stash specified entries
        if (stashedEntryIds != null && !stashedEntryIds.isEmpty()) {
            for (String entryId : stashedEntryIds) {
                QueueEntry entry = queueEntryRepository.findByUuid(entryId).orElse(null);
                if (entry != null && entry.getState() == QueueEntryState.WAITING) {
                    entry.setState(QueueEntryState.STASHED);
                    entry.setStashedFromQueue(queue);
                    queueEntryRepository.save(entry);
                }
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
                if (stashedEntry != null && stashedEntry.getState() == QueueEntryState.STASHED) {
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

                    // Mark original entry as imported
                    stashedEntry.setState(QueueEntryState.REMOVED);
                    stashedEntry.setRemovalReason("imported_to_queue");
                    queueEntryRepository.save(stashedEntry);
                }
            }

            // Update queue's last token
            if (maxToken > newQueue.getLastToken()) {
                newQueue.setLastToken(maxToken);
                queueRepository.save(newQueue);
            }

            log.info("Stash imported: {} entries to queue {}", importedEntryIds.size(), newQueueId);
        }
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
