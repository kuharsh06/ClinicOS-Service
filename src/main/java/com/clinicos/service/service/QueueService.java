package com.clinicos.service.service;

import com.clinicos.service.dto.request.EndQueueRequest;
import com.clinicos.service.dto.request.ImportStashRequest;
import com.clinicos.service.dto.response.ComplaintTagsResponse;
import com.clinicos.service.dto.response.PatientLookupResponse;
import com.clinicos.service.dto.response.QueueResponse;
import com.clinicos.service.entity.*;
import com.clinicos.service.enums.QueueEntryState;
import com.clinicos.service.enums.QueueStatus;
import com.clinicos.service.exception.BusinessException;
import com.clinicos.service.exception.ResourceNotFoundException;
import com.clinicos.service.repository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class QueueService {

    private final QueueRepository queueRepository;
    private final QueueEntryRepository queueEntryRepository;
    private final PatientRepository patientRepository;
    private final ComplaintTagRepository complaintTagRepository;
    private final OrganizationRepository organizationRepository;
    private final OrgMemberRepository orgMemberRepository;
    private final ObjectMapper objectMapper;

    /**
     * Get active queue for a doctor.
     * Returns current active/paused queue (if any) and stashed entries from last ended queue.
     */
    @Transactional(readOnly = true)
    public QueueResponse getActiveQueue(String orgUuid, String doctorUuid) {
        Organization org = organizationRepository.findByUuid(orgUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", orgUuid));

        OrgMember doctor = orgMemberRepository.findByOrgIdAndUserUuid(org.getId(), doctorUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor", doctorUuid));

        // Find active or paused queue (single query)
        List<Queue> activeQueues = queueRepository.findByDoctorIdAndStatusIn(
                doctor.getId(), List.of(QueueStatus.ACTIVE, QueueStatus.PAUSED));

        QueueResponse.QueueSnapshot queueSnapshot = null;
        if (!activeQueues.isEmpty()) {
            queueSnapshot = buildQueueSnapshot(activeQueues.get(0));
        }

        // Find stashed entries from most recently ended queue
        List<QueueResponse.QueueEntryFull> previousQueueStash = findPreviousQueueStash(doctor.getId());

        return QueueResponse.builder()
                .queue(queueSnapshot)
                .previousQueueStash(previousQueueStash)
                .build();
    }

    /**
     * Lookup patient by phone number for auto-fill during registration.
     */
    @Transactional(readOnly = true)
    public PatientLookupResponse lookupPatient(String orgUuid, String phone) {
        Organization org = organizationRepository.findByUuid(orgUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", orgUuid));

        Optional<Patient> patientOpt = patientRepository.findByOrganizationIdAndCountryCodeAndPhone(
                org.getId(), "+91", phone);

        if (patientOpt.isEmpty()) {
            return PatientLookupResponse.builder()
                    .found(false)
                    .patient(null)
                    .build();
        }

        Patient patient = patientOpt.get();
        List<String> lastTags = parseJsonArray(patient.getLastComplaintTags());

        PatientLookupResponse.PatientSummary summary = PatientLookupResponse.PatientSummary.builder()
                .patientId(patient.getUuid())
                .phone(patient.getPhone())
                .name(patient.getName())
                .age(patient.getAge())
                .gender(patient.getGender() != null ? patient.getGender().getValue() : null)
                .totalVisits(patient.getTotalVisits())
                .lastVisitDate(patient.getLastVisitDate() != null ? patient.getLastVisitDate().toString() : null)
                .lastComplaintTags(lastTags)
                .isRegular(patient.getIsRegular())
                .build();

        return PatientLookupResponse.builder()
                .found(true)
                .patient(summary)
                .build();
    }

    /**
     * End a queue session, optionally stashing remaining patients.
     */
    @Transactional
    public Map<String, Object> endQueue(String orgUuid, String queueUuid, EndQueueRequest request) {
        Organization org = organizationRepository.findByUuid(orgUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", orgUuid));

        Queue queue = queueRepository.findByUuid(queueUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Queue", queueUuid));

        if (!queue.getOrganization().getId().equals(org.getId())) {
            throw new BusinessException("Queue does not belong to this organization");
        }

        if (queue.getStatus() == QueueStatus.ENDED) {
            throw new BusinessException("Queue has already ended");
        }

        int stashedCount = 0;

        if (Boolean.TRUE.equals(request.getStashRemaining())) {
            // Stash all waiting entries
            List<QueueEntry> waitingEntries = queueEntryRepository.findByQueueIdAndStateOrderByPositionAsc(
                    queue.getId(), QueueEntryState.WAITING);

            for (QueueEntry entry : waitingEntries) {
                entry.setState(QueueEntryState.STASHED);
                entry.setStashedFromQueue(queue);
                queueEntryRepository.save(entry);
                stashedCount++;
            }
        }

        // Mark queue as ended
        queue.setStatus(QueueStatus.ENDED);
        queue.setEndedAt(Instant.now());
        queueRepository.save(queue);

        log.info("Queue {} ended. Stashed {} entries", queueUuid, stashedCount);

        return Map.of("stashedCount", stashedCount);
    }

    /**
     * Import stashed patients from a previous queue into the current queue.
     */
    @Transactional
    public Map<String, Object> importStash(String orgUuid, String queueUuid, ImportStashRequest request) {
        Organization org = organizationRepository.findByUuid(orgUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", orgUuid));

        Queue targetQueue = queueRepository.findByUuid(queueUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Queue", queueUuid));

        if (!targetQueue.getOrganization().getId().equals(org.getId())) {
            throw new BusinessException("Queue does not belong to this organization");
        }

        if (targetQueue.getStatus() == QueueStatus.ENDED) {
            throw new BusinessException("Cannot import to an ended queue");
        }

        Queue sourceQueue = queueRepository.findByUuid(request.getSourceQueueId())
                .orElseThrow(() -> new ResourceNotFoundException("Source Queue", request.getSourceQueueId()));

        if (sourceQueue.getStatus() != QueueStatus.ENDED) {
            throw new BusinessException("INVALID_SOURCE_QUEUE", "Can only import stash from an ended queue", false);
        }

        // Find stashed entries from source queue
        List<QueueEntry> stashedEntries = queueEntryRepository.findByQueueIdAndStateOrderByPositionAsc(
                sourceQueue.getId(), QueueEntryState.STASHED);

        // Filter to specific entry IDs if provided
        if (request.getEntryIds() != null && !request.getEntryIds().isEmpty()) {
            Set<String> entryIdSet = new HashSet<>(request.getEntryIds());
            stashedEntries = stashedEntries.stream()
                    .filter(e -> entryIdSet.contains(e.getUuid()))
                    .collect(Collectors.toList());
        }

        List<String> importedEntryIds = new ArrayList<>();

        // Get current max position in target queue
        List<QueueEntry> currentEntries = queueEntryRepository.findByQueueIdOrderByPositionAsc(targetQueue.getId());
        int nextPosition = currentEntries.stream()
                .mapToInt(e -> e.getPosition() != null ? e.getPosition() : 0)
                .max()
                .orElse(0) + 1;

        for (QueueEntry stashedEntry : stashedEntries) {
            // Create new entry in target queue
            QueueEntry newEntry = QueueEntry.builder()
                    .queue(targetQueue)
                    .patient(stashedEntry.getPatient())
                    .tokenNumber(stashedEntry.getTokenNumber())  // Keep original token
                    .state(QueueEntryState.WAITING)
                    .position(nextPosition++)
                    .complaintTags(stashedEntry.getComplaintTags())
                    .complaintText(stashedEntry.getComplaintText())
                    .isBilled(false)
                    .stashedFromQueue(sourceQueue)
                    .registeredAt(System.currentTimeMillis())
                    .build();

            queueEntryRepository.save(newEntry);
            importedEntryIds.add(newEntry.getUuid());

            // Mark original entry as imported (remove stashed state)
            stashedEntry.setState(QueueEntryState.REMOVED);
            stashedEntry.setRemovalReason("imported_to_queue");
            queueEntryRepository.save(stashedEntry);
        }

        // Update target queue's last token if necessary
        int maxToken = stashedEntries.stream()
                .mapToInt(QueueEntry::getTokenNumber)
                .max()
                .orElse(targetQueue.getLastToken());
        if (maxToken > targetQueue.getLastToken()) {
            targetQueue.setLastToken(maxToken);
            queueRepository.save(targetQueue);
        }

        log.info("Imported {} entries from queue {} to queue {}", importedEntryIds.size(),
                request.getSourceQueueId(), queueUuid);

        return Map.of(
                "importedCount", importedEntryIds.size(),
                "importedEntryIds", importedEntryIds
        );
    }

    /**
     * Get complaint tags for the organization.
     */
    @Transactional(readOnly = true)
    public ComplaintTagsResponse getComplaintTags(String orgUuid) {
        Organization org = organizationRepository.findByUuid(orgUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", orgUuid));

        List<ComplaintTag> tags = complaintTagRepository.findByOrganizationIdAndIsActiveTrueOrderBySortOrderAsc(
                org.getId());

        List<ComplaintTagsResponse.ComplaintTagDto> tagDtos = tags.stream()
                .map(tag -> ComplaintTagsResponse.ComplaintTagDto.builder()
                        .tagId(tag.getUuid())
                        .labelHi(tag.getLabelHi())
                        .labelEn(tag.getLabelEn())
                        .key(tag.getTagKey())
                        .sortOrder(tag.getSortOrder())
                        .isCommon(tag.getIsCommon())
                        .build())
                .collect(Collectors.toList());

        return ComplaintTagsResponse.builder()
                .tags(tagDtos)
                .build();
    }

    private QueueResponse.QueueSnapshot buildQueueSnapshot(Queue queue) {
        List<QueueEntry> entries = queueEntryRepository.findByQueueIdWithDetailsOrderByPositionAsc(queue.getId());

        List<QueueResponse.QueueEntryFull> entryDtos = entries.stream()
                .map(this::buildQueueEntryFull)
                .collect(Collectors.toList());

        return QueueResponse.QueueSnapshot.builder()
                .queueId(queue.getUuid())
                .orgId(queue.getOrganization().getUuid())
                .doctorId(queue.getDoctor().getUser().getUuid())
                .status(queue.getStatus().name().toLowerCase())
                .lastToken(queue.getLastToken())
                .pauseStartTime(queue.getPauseStartTime())
                .totalPausedMs(queue.getTotalPausedMs())
                .createdAt(queue.getStartedAt().toString())
                .endedAt(queue.getEndedAt() != null ? queue.getEndedAt().toString() : null)
                .entries(entryDtos)
                .lastEventTimestamp(System.currentTimeMillis())
                .build();
    }

    private QueueResponse.QueueEntryFull buildQueueEntryFull(QueueEntry entry) {
        Patient patient = entry.getPatient();

        return QueueResponse.QueueEntryFull.builder()
                .entryId(entry.getUuid())
                .queueId(entry.getQueue().getUuid())
                .patientId(patient.getUuid())
                .tokenNumber(entry.getTokenNumber())
                .state(entry.getState().name().toLowerCase())
                .position(entry.getPosition())
                .complaintTags(parseJsonArray(entry.getComplaintTags()))
                .complaintText(entry.getComplaintText())
                .isBilled(entry.getIsBilled())
                .billAmount(entry.getBill() != null ? entry.getBill().getTotalAmount().intValue() : null)
                .billId(entry.getBill() != null ? entry.getBill().getUuid() : null)
                .registeredAt(entry.getRegisteredAt())
                .servedAt(entry.getCalledAt())
                .completedAt(entry.getCompletedAt())
                .stashedFromQueueId(entry.getStashedFromQueue() != null ? entry.getStashedFromQueue().getUuid() : null)
                .patientName(patient.getName())
                .patientPhone(patient.getPhone())
                .patientAge(patient.getAge())
                .patientGender(patient.getGender() != null ? patient.getGender().getValue() : null)
                .isReturningPatient(patient.getTotalVisits() > 0)
                .totalPreviousVisits(patient.getTotalVisits())
                .build();
    }

    private List<QueueResponse.QueueEntryFull> findPreviousQueueStash(Integer doctorId) {
        // Find the most recently ended queue for this doctor that has stashed entries
        // Find most recently ended queue (single query instead of loading all)
        Optional<Queue> lastEnded = queueRepository.findMostRecentEndedQueue(doctorId);
        if (lastEnded.isEmpty()) {
            return List.of();
        }

        List<QueueEntry> stashedEntries = queueEntryRepository.findByQueueIdAndStateWithDetailsOrderByPositionAsc(
                lastEnded.get().getId(), QueueEntryState.STASHED);

        return stashedEntries.stream()
                .map(this::buildQueueEntryFull)
                .collect(Collectors.toList());
    }

    private List<String> parseJsonArray(String json) {
        if (json == null || json.isEmpty()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            log.warn("Error parsing JSON array: {}", json, e);
            return List.of();
        }
    }
}
