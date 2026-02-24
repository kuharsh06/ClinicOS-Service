package com.clinicos.service.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "permissions", indexes = {
        @Index(name = "idx_permissions_uuid", columnList = "uuid", unique = true),
        @Index(name = "idx_permissions_name", columnList = "name", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Permission extends BaseEntity {

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    @Column(name = "category", nullable = false, length = 30)
    private String category;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(name = "description", length = 255)
    private String description;
}
