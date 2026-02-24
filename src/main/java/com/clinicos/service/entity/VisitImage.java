package com.clinicos.service.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "visit_images", indexes = {
        @Index(name = "idx_visit_images_uuid", columnList = "uuid", unique = true),
        @Index(name = "idx_visit_images_visit", columnList = "visit_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VisitImage extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id", nullable = false)
    private Visit visit;

    @Column(name = "image_url", nullable = false, length = 500)
    private String imageUrl;

    @Column(name = "thumbnail_url", length = 500)
    private String thumbnailUrl;

    @Column(name = "caption", length = 255)
    private String caption;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "file_size_bytes")
    private Integer fileSizeBytes;

    @Column(name = "mime_type", length = 50)
    private String mimeType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by", nullable = false)
    private OrgMember uploadedBy;
}
