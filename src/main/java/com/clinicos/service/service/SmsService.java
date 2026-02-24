package com.clinicos.service.service;

import com.clinicos.service.dto.request.ManualSmsSendRequest;
import com.clinicos.service.dto.response.QueueSmsStatusResponse;
import com.clinicos.service.dto.response.SmsTemplatesResponse;
import com.clinicos.service.entity.*;
import com.clinicos.service.enums.SmsStatus;
import com.clinicos.service.exception.BusinessException;
import com.clinicos.service.exception.ResourceNotFoundException;
import com.clinicos.service.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SmsService {

    private final OrganizationRepository organizationRepository;
    private final OrgMemberRepository orgMemberRepository;
    private final SmsTemplateRepository smsTemplateRepository;
    private final SmsLogRepository smsLogRepository;
    private final QueueRepository queueRepository;
    private final QueueEntryRepository queueEntryRepository;

    /**
     * Get SMS templates for the organization.
     */
    @Transactional(readOnly = true)
    public SmsTemplatesResponse getTemplates(String orgUuid) {
        Organization org = organizationRepository.findByUuid(orgUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", orgUuid));

        List<SmsTemplate> templates = smsTemplateRepository.findByOrganizationIdAndIsActiveTrueAndDeletedAtIsNull(
                org.getId());

        List<SmsTemplatesResponse.SmsTemplateDto> templateDtos = templates.stream()
                .map(t -> SmsTemplatesResponse.SmsTemplateDto.builder()
                        .templateId(t.getUuid())
                        .trigger(t.getTemplateKey())
                        .templates(Map.of("hi", t.getContent(), "en", t.getContent()))
                        .variables(extractVariables(t.getContent()))
                        .isActive(t.getIsActive())
                        .dltTemplateIds(Map.of())  // To be added when DLT integration is done
                        .maxLength(160)
                        .build())
                .collect(Collectors.toList());

        return SmsTemplatesResponse.builder()
                .templates(templateDtos)
                .build();
    }

    /**
     * Get SMS delivery status for a queue.
     */
    @Transactional(readOnly = true)
    public QueueSmsStatusResponse getQueueSmsStatus(String orgUuid, String queueUuid) {
        Organization org = organizationRepository.findByUuid(orgUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", orgUuid));

        com.clinicos.service.entity.Queue queue = queueRepository.findByUuid(queueUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Queue", queueUuid));

        if (!queue.getOrganization().getId().equals(org.getId())) {
            throw new ResourceNotFoundException("Queue", queueUuid);
        }

        List<QueueEntry> entries = queueEntryRepository.findByQueueIdOrderByPositionAsc(queue.getId());

        Map<String, QueueSmsStatusResponse.EntrySmsStatus> statuses = new HashMap<>();
        for (QueueEntry entry : entries) {
            // Get SMS logs for this entry
            List<SmsLog> entryLogs = smsLogRepository.findByOrganizationIdOrderByCreatedAtDesc(org.getId())
                    .stream()
                    .filter(log -> log.getQueueEntry() != null && log.getQueueEntry().getId().equals(entry.getId()))
                    .collect(Collectors.toList());

            String registrationStatus = "queued";
            String turnNearStatus = null;
            String turnNowStatus = null;
            String billStatus = null;

            for (SmsLog log : entryLogs) {
                String status = convertStatus(log.getStatus());
                String templateKey = log.getTemplate() != null ? log.getTemplate().getTemplateKey() : "";

                switch (templateKey) {
                    case "registration" -> registrationStatus = status;
                    case "turn_near" -> turnNearStatus = status;
                    case "turn_now" -> turnNowStatus = status;
                    case "bill_generated" -> billStatus = status;
                }
            }

            statuses.put(entry.getUuid(), QueueSmsStatusResponse.EntrySmsStatus.builder()
                    .registration(registrationStatus)
                    .turnNear(turnNearStatus)
                    .turnNow(turnNowStatus)
                    .bill(billStatus)
                    .build());
        }

        return QueueSmsStatusResponse.builder()
                .statuses(statuses)
                .build();
    }

    /**
     * Manually send or resend an SMS.
     */
    @Transactional
    public Map<String, Object> sendManualSms(String orgUuid, ManualSmsSendRequest request, Integer userId) {
        Organization org = organizationRepository.findByUuid(orgUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", orgUuid));

        QueueEntry entry = queueEntryRepository.findByUuid(request.getEntryId())
                .orElseThrow(() -> new ResourceNotFoundException("Queue Entry", request.getEntryId()));

        if (!entry.getQueue().getOrganization().getId().equals(org.getId())) {
            throw new BusinessException("Queue entry does not belong to this organization");
        }

        OrgMember createdBy = orgMemberRepository.findByOrganizationIdAndUserId(org.getId(), userId)
                .orElseThrow(() -> new ResourceNotFoundException("Member", userId.toString()));

        String message;
        SmsTemplate template = null;

        if (request.getCustomMessage() != null && !request.getCustomMessage().isEmpty()) {
            if (request.getCustomMessage().length() > 160) {
                throw new BusinessException("Custom message must be less than 160 characters");
            }
            message = request.getCustomMessage();
        } else {
            template = smsTemplateRepository.findByOrganizationIdAndTemplateKeyAndDeletedAtIsNull(
                    org.getId(), request.getTemplateType()).orElse(null);
            if (template == null) {
                throw new ResourceNotFoundException("SMS Template", request.getTemplateType());
            }
            message = processTemplate(template.getContent(), entry);
        }

        // Create SMS log (actual sending will be handled by SMS gateway integration)
        SmsLog smsLog = SmsLog.builder()
                .organization(org)
                .template(template)
                .recipientPhone(entry.getPatient().getPhone())
                .messageContent(message)
                .status(SmsStatus.PENDING)
                .queueEntry(entry)
                .patient(entry.getPatient())
                .createdBy(createdBy)
                .build();

        smsLogRepository.save(smsLog);

        log.info("Manual SMS queued: {} for entry {} by user {}", smsLog.getUuid(), request.getEntryId(), userId);

        // TODO: Actually send the SMS via gateway (MSG91 or similar)
        // For now, mark as sent
        smsLog.setStatus(SmsStatus.SENT);
        smsLog.setSentAt(Instant.now());
        smsLogRepository.save(smsLog);

        return Map.of(
                "smsId", smsLog.getUuid(),
                "status", "sent"
        );
    }

    private String convertStatus(SmsStatus status) {
        return switch (status) {
            case PENDING -> "queued";
            case SENT -> "sent";
            case DELIVERED -> "delivered";
            case FAILED -> "failed";
            case DND_BLOCKED -> "dnd_blocked";
        };
    }

    private List<String> extractVariables(String template) {
        List<String> variables = new ArrayList<>();
        int start = 0;
        while ((start = template.indexOf("{{", start)) != -1) {
            int end = template.indexOf("}}", start);
            if (end != -1) {
                variables.add(template.substring(start + 2, end).trim());
                start = end + 2;
            } else {
                break;
            }
        }
        return variables;
    }

    private String processTemplate(String template, QueueEntry entry) {
        return template
                .replace("{{token}}", String.valueOf(entry.getTokenNumber()))
                .replace("{{patient_name}}", entry.getPatient().getName())
                .replace("{{clinic}}", entry.getQueue().getOrganization().getName())
                .replace("{{position}}", String.valueOf(entry.getPosition() != null ? entry.getPosition() : 0));
    }
}
