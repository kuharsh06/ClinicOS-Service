package com.clinicos.service.entity;

import com.clinicos.service.enums.SmsStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "sms_logs", indexes = {
        @Index(name = "idx_sms_logs_uuid", columnList = "uuid", unique = true),
        @Index(name = "idx_sms_logs_org", columnList = "org_id"),
        @Index(name = "idx_sms_logs_status", columnList = "status"),
        @Index(name = "idx_sms_logs_recipient", columnList = "recipient_phone"),
        @Index(name = "idx_sms_logs_created", columnList = "org_id, created_at"),
        @Index(name = "idx_sms_logs_provider_msg", columnList = "provider_msg_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SmsLog extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "org_id", nullable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "template_id")
    private SmsTemplate template;

    @Column(name = "recipient_phone", nullable = false, length = 15)
    private String recipientPhone;

    @Column(name = "message_content", nullable = false, columnDefinition = "TEXT")
    private String messageContent;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private SmsStatus status = SmsStatus.PENDING;

    @Column(name = "provider", length = 50)
    private String provider;

    @Column(name = "provider_msg_id", length = 100)
    private String providerMsgId;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id")
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "queue_entry_id")
    private QueueEntry queueEntry;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bill_id")
    private Bill bill;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false)
    private OrgMember createdBy;
}
