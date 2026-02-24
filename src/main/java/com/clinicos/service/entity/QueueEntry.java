package com.clinicos.service.entity;

import com.clinicos.service.enums.QueueEntryState;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "queue_entries", indexes = {
        @Index(name = "idx_queue_entries_uuid", columnList = "uuid", unique = true),
        @Index(name = "idx_queue_entries_queue", columnList = "queue_id"),
        @Index(name = "idx_queue_entries_patient", columnList = "patient_id"),
        @Index(name = "idx_queue_entries_state", columnList = "queue_id, state")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QueueEntry extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "queue_id", nullable = false)
    private Queue queue;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    @Column(name = "token_number", nullable = false)
    private Integer tokenNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    @Builder.Default
    private QueueEntryState state = QueueEntryState.WAITING;

    @Column(name = "position")
    private Integer position;

    @Column(name = "complaint_tags", columnDefinition = "JSON")
    private String complaintTags;

    @Column(name = "complaint_text", length = 500)
    private String complaintText;

    @Column(name = "is_billed", nullable = false)
    @Builder.Default
    private Boolean isBilled = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bill_id")
    private Bill bill;

    @Column(name = "removal_reason", length = 50)
    private String removalReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stashed_from_queue_id")
    private Queue stashedFromQueue;

    @Column(name = "step_out_reason", length = 50)
    private String stepOutReason;

    @Column(name = "stepped_out_at")
    private Long steppedOutAt;

    @Column(name = "registered_at", nullable = false)
    private Long registeredAt;

    @Column(name = "called_at")
    private Long calledAt;

    @Column(name = "completed_at")
    private Long completedAt;
}
