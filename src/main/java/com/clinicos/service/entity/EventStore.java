package com.clinicos.service.entity;

import com.clinicos.service.enums.EventStatus;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "event_store", indexes = {
        @Index(name = "idx_event_store_uuid", columnList = "uuid", unique = true),
        @Index(name = "idx_events_org_received", columnList = "org_id, server_received_at"),
        @Index(name = "idx_events_user", columnList = "user_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventStore extends BaseEntity {

    @Column(name = "device_id", nullable = false, length = 100)
    private String deviceId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "org_id", nullable = false)
    private Organization organization;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "target_entity_uuid", nullable = false, length = 36)
    private String targetEntityUuid;

    @Column(name = "target_table", nullable = false, length = 50)
    private String targetTable;

    @Column(name = "payload", nullable = false, columnDefinition = "JSON")
    private String payload;

    @Column(name = "schema_version", nullable = false)
    @Builder.Default
    private Integer schemaVersion = 1;

    @Column(name = "device_timestamp", nullable = false)
    private Long deviceTimestamp;

    @Column(name = "server_received_at")
    private Long serverReceivedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private EventStatus status = EventStatus.PENDING;

    @Column(name = "rejection_code", length = 50)
    private String rejectionCode;

    @Column(name = "rejection_reason", length = 255)
    private String rejectionReason;
}
