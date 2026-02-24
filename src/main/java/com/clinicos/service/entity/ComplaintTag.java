package com.clinicos.service.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "complaint_tags", indexes = {
        @Index(name = "idx_complaint_tags_uuid", columnList = "uuid", unique = true),
        @Index(name = "idx_complaint_tags_org", columnList = "org_id")
}, uniqueConstraints = {
        @UniqueConstraint(name = "idx_complaint_tags_org_key", columnNames = {"org_id", "tag_key"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ComplaintTag extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "org_id", nullable = false)
    private Organization organization;

    @Column(name = "tag_key", nullable = false, length = 50)
    private String tagKey;

    @Column(name = "label_en", nullable = false, length = 100)
    private String labelEn;

    @Column(name = "label_hi", length = 100)
    private String labelHi;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "is_common", nullable = false)
    @Builder.Default
    private Boolean isCommon = false;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;
}
