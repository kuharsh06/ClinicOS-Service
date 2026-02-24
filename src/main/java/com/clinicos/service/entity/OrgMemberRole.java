package com.clinicos.service.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "org_member_roles", indexes = {
        @Index(name = "idx_org_member_roles_uuid", columnList = "uuid", unique = true)
}, uniqueConstraints = {
        @UniqueConstraint(name = "idx_member_role", columnNames = {"org_member_id", "role_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrgMemberRole extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "org_member_id", nullable = false)
    private OrgMember orgMember;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_by")
    private User assignedBy;
}
