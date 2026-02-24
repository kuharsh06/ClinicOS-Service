package com.clinicos.service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "org_members", indexes = {
        @Index(name = "idx_org_members_uuid", columnList = "uuid", unique = true),
        @Index(name = "idx_org_members_org", columnList = "org_id")
}, uniqueConstraints = {
        @UniqueConstraint(name = "idx_org_members_user_org", columnNames = {"user_id", "org_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrgMember extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "org_id", nullable = false)
    private Organization organization;

    @Column(name = "profile_data", columnDefinition = "JSON")
    private String profileData;

    @Column(name = "profile_schema_version")
    private Integer profileSchemaVersion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_doctor_id")
    private OrgMember assignedDoctor;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "is_profile_complete", nullable = false)
    @Builder.Default
    private Boolean isProfileComplete = false;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @Column(name = "left_at")
    private Instant leftAt;

    @Column(name = "last_active_at")
    private Instant lastActiveAt;

    @PrePersist
    @Override
    protected void onCreate() {
        super.onCreate();
        if (this.joinedAt == null) {
            this.joinedAt = Instant.now();
        }
    }
}
