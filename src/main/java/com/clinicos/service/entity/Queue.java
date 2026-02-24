package com.clinicos.service.entity;

import com.clinicos.service.enums.QueueStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "queues", indexes = {
        @Index(name = "idx_queues_uuid", columnList = "uuid", unique = true),
        @Index(name = "idx_queues_org", columnList = "org_id"),
        @Index(name = "idx_queues_doctor_status", columnList = "doctor_id, status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Queue extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "org_id", nullable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_id", nullable = false)
    private OrgMember doctor;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private QueueStatus status = QueueStatus.ACTIVE;

    @Column(name = "last_token", nullable = false)
    @Builder.Default
    private Integer lastToken = 0;

    @Column(name = "pause_start_time")
    private Long pauseStartTime;

    @Column(name = "total_paused_ms", nullable = false)
    @Builder.Default
    private Long totalPausedMs = 0L;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @PrePersist
    @Override
    protected void onCreate() {
        super.onCreate();
        if (this.startedAt == null) {
            this.startedAt = Instant.now();
        }
    }
}
