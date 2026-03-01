package com.clinicos.service.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "visit_images", indexes = {
        @Index(name = "idx_visit_images_uuid", columnList = "uuid", unique = true),
        @Index(name = "idx_visit_images_visit", columnList = "visit_id"),
        @Index(name = "idx_visit_images_patient", columnList = "patient_id"),
        @Index(name = "idx_visit_images_org", columnList = "organization_id"),
        @Index(name = "idx_visit_images_doctor", columnList = "doctor_uuid"),
        @Index(name = "idx_visit_images_org_patient", columnList = "organization_id, patient_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VisitImage extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id")
    private Visit visit;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "doctor_uuid", length = 36)
    private String doctorUuid;

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

    @Column(name = "original_filename", length = 255)
    private String originalFilename;

    @Column(name = "file_type", length = 20, nullable = false)
    @Builder.Default
    private String fileType = "image";

    @Column(name = "storage_key", length = 500, nullable = false)
    private String storageKey;

    @Column(name = "tags", columnDefinition = "JSON")
    private String tags;

    @Column(name = "metadata", columnDefinition = "JSON")
    private String metadata;

    @Column(name = "ai_analysis", columnDefinition = "JSON")
    private String aiAnalysis;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by", nullable = false)
    private OrgMember uploadedBy;
}
