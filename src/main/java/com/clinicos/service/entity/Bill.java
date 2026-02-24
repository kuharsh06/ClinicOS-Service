package com.clinicos.service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "bills", indexes = {
        @Index(name = "idx_bills_uuid", columnList = "uuid", unique = true),
        @Index(name = "idx_bills_org", columnList = "org_id"),
        @Index(name = "idx_bills_patient", columnList = "patient_id"),
        @Index(name = "idx_bills_queue_entry", columnList = "queue_entry_id"),
        @Index(name = "idx_bills_is_paid", columnList = "org_id, is_paid"),
        @Index(name = "idx_bills_created", columnList = "org_id, created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Bill extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "org_id", nullable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    @Column(name = "queue_entry_id")
    private Integer queueEntryId;

    @Column(name = "total_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "is_paid", nullable = false)
    @Builder.Default
    private Boolean isPaid = false;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "patient_name", nullable = false, length = 100)
    private String patientName;

    @Column(name = "patient_phone", nullable = false, length = 15)
    private String patientPhone;

    @Column(name = "token_number")
    private Integer tokenNumber;

    @Column(name = "doctor_name", length = 100)
    private String doctorName;

    @Column(name = "sms_sent", nullable = false)
    @Builder.Default
    private Boolean smsSent = false;

    @Column(name = "sms_sent_at")
    private Instant smsSentAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false)
    private OrgMember createdBy;
}
