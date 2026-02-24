package com.clinicos.service.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "event_role_snapshot", indexes = {
        @Index(name = "idx_event_role_snapshot_uuid", columnList = "uuid", unique = true),
        @Index(name = "idx_ers_event", columnList = "event_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventRoleSnapshot extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    private EventStore event;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;
}
