package com.clinicos.service.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "test_phones")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TestPhone {

    @Id
    @Column(name = "phone", length = 15)
    private String phone;

    @Column(name = "country_code", nullable = false, length = 5)
    private String countryCode;
}
