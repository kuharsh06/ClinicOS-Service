package com.clinicos.service.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "sms_templates", indexes = {
        @Index(name = "idx_sms_templates_uuid", columnList = "uuid", unique = true),
        @Index(name = "idx_sms_templates_org_active", columnList = "org_id, is_active")
}, uniqueConstraints = {
        @UniqueConstraint(name = "idx_sms_templates_org_key", columnNames = {"org_id", "template_key", "deleted_at"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SmsTemplate extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "org_id", nullable = false)
    private Organization organization;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "template_key", nullable = false, length = 50)
    private String templateKey;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;
}
