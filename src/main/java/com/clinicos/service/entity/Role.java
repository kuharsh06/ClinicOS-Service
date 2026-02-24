package com.clinicos.service.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "roles", indexes = {
        @Index(name = "idx_roles_uuid", columnList = "uuid", unique = true),
        @Index(name = "idx_roles_name", columnList = "name", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Role extends BaseEntity {

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(name = "description", length = 255)
    private String description;
}
