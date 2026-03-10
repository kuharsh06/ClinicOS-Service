package com.clinicos.service.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "users", indexes = {
        @Index(name = "idx_users_uuid", columnList = "uuid", unique = true),
        @Index(name = "idx_users_phone", columnList = "country_code, phone", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User extends BaseEntity {

    @Column(name = "phone", nullable = false, length = 15)
    private String phone;

    @Column(name = "country_code", nullable = false, length = 5)
    @Builder.Default
    private String countryCode = "+91";

    @Column(name = "name", length = 100)
    private String name;

    @Column(name = "scheduled_permanent_deletion_at")
    private java.time.Instant scheduledPermanentDeletionAt;
}
