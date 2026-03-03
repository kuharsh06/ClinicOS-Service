package com.clinicos.service.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Audit log entity for DPDP Rule 6 compliance.
 * Append-only, immutable — never updated or soft-deleted.
 * Does NOT extend BaseEntity (no uuid, updatedAt, deletedAt).
 */
@Entity
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_user", columnList = "user_id, created_at"),
        @Index(name = "idx_audit_org", columnList = "org_id, created_at"),
        @Index(name = "idx_audit_action", columnList = "action, created_at"),
        @Index(name = "idx_audit_status", columnList = "status, created_at"),
        @Index(name = "idx_audit_created", columnList = "created_at")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", length = 36)
    private String userId;

    @Column(name = "org_id", length = 36)
    private String orgId;

    @Column(name = "action", nullable = false, length = 50)
    private String action;

    @Column(name = "resource_type", nullable = false, length = 30)
    private String resourceType;

    @Column(name = "resource_id", length = 36)
    private String resourceId;

    @Column(name = "status", nullable = false, length = 15)
    private String status;

    @Column(name = "denied_reason", length = 200)
    private String deniedReason;

    @Column(name = "endpoint", length = 200)
    private String endpoint;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "device_id", length = 100)
    private String deviceId;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
