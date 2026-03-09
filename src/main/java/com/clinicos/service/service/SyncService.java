package com.clinicos.service.service;

import com.clinicos.service.dto.request.SyncPushRequest;
import com.clinicos.service.dto.response.SyncPullResponse;
import com.clinicos.service.dto.response.SyncPushResponse;
import com.clinicos.service.entity.*;
import com.clinicos.service.repository.*;
import com.clinicos.service.security.CustomUserDetails;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Sync service orchestrator.
 * Manages queue-level locking and delegates event processing to SyncEventProcessor.
 * Each event is processed in its own transaction (REQUIRES_NEW) for isolation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SyncService {

    private final SyncEventProcessor syncEventProcessor;
    private final EventStoreRepository eventStoreRepository;
    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final QueueEntryRepository queueEntryRepository;
    private final ObjectMapper objectMapper;

    // In-memory locks per queueId — serializes concurrent queue-mutating events
    // Prevents race conditions like dual call_now from separate HTTP requests
    private final ConcurrentHashMap<String, ReentrantLock> queueLocks = new ConcurrentHashMap<>();

    // Queue-mutating event types that need serialization
    private static final Set<String> QUEUE_MUTATING_EVENTS = Set.of(
            "patient_added", "patient_removed", "call_now", "step_out",
            "mark_complete", "queue_paused", "queue_resumed", "queue_ended",
            "stash_imported", "stash_dismissed"
    );

    /**
     * Push events from a device.
     * NOT @Transactional — each event is processed in its own transaction via SyncEventProcessor.
     * Queue-mutating events are serialized per queueId using in-memory locks.
     */
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

        Set<String> actualRoles = userDetails.getRoles() != null
                ? new HashSet<>(userDetails.getRoles())
                : new HashSet<>();

        for (SyncPushRequest.SyncEvent event : request.getEvents()) {
            String queueId = resolveQueueId(event);
            boolean needsLock = queueId != null && QUEUE_MUTATING_EVENTS.contains(event.getEventType());

            ReentrantLock lock = null;
            if (needsLock) {
                lock = queueLocks.computeIfAbsent(queueId, k -> new ReentrantLock());
                lock.lock();
            }

            try {
                SyncEventProcessor.EventResult result =
                        syncEventProcessor.processSingleEvent(event, user, org, actualRoles, serverTimestamp);

                if (result.accepted()) {
                    accepted.add(result.acceptedEvent());
                } else {
                    rejected.add(result.rejectedEvent());
                }
            } catch (Exception e) {
                log.error("Unexpected error processing event {}: {}", event.getEventId(), e.getMessage());
                rejected.add(SyncPushResponse.RejectedEvent.builder()
                        .eventId(event.getEventId())
                        .code("PROCESSING_ERROR")
                        .reason(e.getMessage())
                        .build());
            } finally {
                if (lock != null) {
                    lock.unlock();
                }
            }
        }

        return SyncPushResponse.builder()
                .accepted(accepted)
                .rejected(rejected)
                .serverTimestamp(serverTimestamp)
                .build();
    }

    /**
     * Pull events for a device.
     * Returns events from other devices since the given timestamp.
     */
    @Transactional(readOnly = true)
    public SyncPullResponse pullEvents(Long since, String deviceId, Integer limit, CustomUserDetails userDetails) {
        if (since == null) since = 0L;
        if (limit == null || limit <= 0) limit = 100;
        if (limit > 500) limit = 500;

        Organization org = organizationRepository.findById(userDetails.getOrgId())
                .orElseThrow(() -> new RuntimeException("Organization not found"));

        boolean canViewFullClinical = canViewFullClinicalData(org);

        List<EventStore> events = eventStoreRepository.findEventsForPull(
                org.getId(), since, deviceId, PageRequest.of(0, limit + 1));

        boolean hasMore = events.size() > limit;
        if (hasMore) {
            events = events.subList(0, limit);
        }

        List<SyncPullResponse.SyncEventDto> eventDtos = events.stream()
                .map(e -> convertToDto(e, canViewFullClinical))
                .toList();

        Long serverTimestamp = events.isEmpty() ? since
                : events.get(events.size() - 1).getServerReceivedAt();

        return SyncPullResponse.builder()
                .events(eventDtos)
                .serverTimestamp(serverTimestamp)
                .hasMore(hasMore)
                .build();
    }

    // ==================== Queue ID Resolution ====================

    /**
     * Resolve the queueId that an event affects, for lock acquisition.
     * Returns null for non-queue events (visit_saved, bill_*).
     * This is a lightweight read-only lookup outside the per-event transaction.
     */
    @SuppressWarnings("unchecked")
    private String resolveQueueId(SyncPushRequest.SyncEvent event) {
        String eventType = event.getEventType();

        return switch (eventType) {
            // Direct queue target
            case "queue_paused", "queue_resumed", "queue_ended" ->
                    event.getTargetEntity();

            // Queue from payload
            case "patient_added" -> {
                Map<String, Object> payload = event.getPayload();
                yield payload != null ? (String) payload.get("queueId") : null;
            }

            // New queue (stash import target) / source queue (stash dismiss)
            case "stash_imported", "stash_dismissed" ->
                    event.getTargetEntity();

            // Queue via entry lookup
            case "patient_removed", "call_now", "step_out", "mark_complete" -> {
                String entryId = event.getTargetEntity();
                yield queueEntryRepository.findByUuid(entryId)
                        .map(entry -> entry.getQueue().getUuid())
                        .orElse(null);
            }

            // Non-queue events — no lock needed
            default -> null;
        };
    }

    // ==================== Pull Helpers ====================

    @SuppressWarnings("unchecked")
    private boolean canViewFullClinicalData(Organization org) {
        Map<String, Object> settings = fromJson(org.getSettings());
        String visibility = settings != null ? (String) settings.get("clinicalDataVisibility") : "all_members";
        if (visibility == null) visibility = "all_members";
        if ("all_members".equals(visibility)) return true;
        try {
            CustomUserDetails userDetails = (CustomUserDetails) SecurityContextHolder.getContext()
                    .getAuthentication().getPrincipal();
            return userDetails.hasPermission("patient:view_clinical");
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private SyncPullResponse.SyncEventDto convertToDto(EventStore event, boolean canViewFullClinical) {
        Map<String, Object> payload = fromJson(event.getPayload());

        if (!canViewFullClinical && "visit_saved".equals(event.getEventType())) {
            if (payload != null) {
                payload.put("data", null);
                payload.put("images", Collections.emptyList());
            }
        }

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
