package com.clinicos.service.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "organizations", indexes = {
        @Index(name = "idx_organizations_uuid", columnList = "uuid", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Organization extends BaseEntity {

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "logo_url", length = 500)
    private String logoUrl;

    @Column(name = "brand_color", nullable = false, length = 7)
    @Builder.Default
    private String brandColor = "#059669";

    @Column(name = "address", nullable = false, columnDefinition = "TEXT")
    private String address;

    @Column(name = "city", nullable = false, length = 100)
    private String city;

    @Column(name = "state", nullable = false, length = 100)
    private String state;

    @Column(name = "pin", nullable = false, length = 10)
    private String pin;

    @Column(name = "settings", columnDefinition = "JSON")
    private String settings;

    @Column(name = "working_hours", columnDefinition = "JSON")
    private String workingHours;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;
}
