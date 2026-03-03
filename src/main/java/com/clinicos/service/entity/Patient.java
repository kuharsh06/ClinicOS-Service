package com.clinicos.service.entity;

import com.clinicos.service.enums.Gender;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Entity
@Table(name = "patients", indexes = {
        @Index(name = "idx_patients_uuid", columnList = "uuid", unique = true),
        @Index(name = "idx_patients_org", columnList = "org_id"),
        @Index(name = "idx_patients_name", columnList = "org_id, name")
}, uniqueConstraints = {
        @UniqueConstraint(name = "idx_patients_org_phone", columnNames = {"org_id", "country_code", "phone"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Patient extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "org_id", nullable = false)
    private Organization organization;

    @Column(name = "phone", length = 15)
    private String phone;

    @Column(name = "country_code", length = 5)
    @Builder.Default
    private String countryCode = "+91";

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "age")
    private Integer age;

    @Enumerated(EnumType.STRING)
    @Column(name = "gender", length = 10)
    private Gender gender;

    @Column(name = "total_visits", nullable = false)
    @Builder.Default
    private Integer totalVisits = 0;

    @Column(name = "last_visit_date")
    private LocalDate lastVisitDate;

    @Column(name = "last_complaint_tags", columnDefinition = "JSON")
    private String lastComplaintTags;

    @Column(name = "is_regular", nullable = false)
    @Builder.Default
    private Boolean isRegular = false;
}
