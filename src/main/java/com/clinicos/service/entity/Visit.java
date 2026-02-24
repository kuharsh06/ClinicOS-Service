package com.clinicos.service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Entity
@Table(name = "visits", indexes = {
        @Index(name = "idx_visits_uuid", columnList = "uuid", unique = true),
        @Index(name = "idx_visits_patient", columnList = "patient_id"),
        @Index(name = "idx_visits_org_date", columnList = "org_id, visit_date"),
        @Index(name = "idx_visits_queue_entry", columnList = "queue_entry_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Visit extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "org_id", nullable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "queue_entry_id")
    private QueueEntry queueEntry;

    @Column(name = "visit_date", nullable = false)
    private LocalDate visitDate;

    @Column(name = "complaint_tags", columnDefinition = "JSON")
    private String complaintTags;

    @Column(name = "data", columnDefinition = "JSON")
    private String data;

    @Column(name = "schema_version", nullable = false)
    @Builder.Default
    private Integer schemaVersion = 1;

    @Column(name = "is_redacted", nullable = false)
    @Builder.Default
    private Boolean isRedacted = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false)
    private OrgMember createdBy;
}
