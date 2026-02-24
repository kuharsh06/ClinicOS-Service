package com.clinicos.service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "otp_requests", indexes = {
        @Index(name = "idx_otp_uuid", columnList = "uuid", unique = true),
        @Index(name = "idx_otp_phone_created", columnList = "country_code, phone, created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OtpRequest extends BaseEntity {

    @Column(name = "phone", nullable = false, length = 15)
    private String phone;

    @Column(name = "country_code", nullable = false, length = 5)
    @Builder.Default
    private String countryCode = "+91";

    @Column(name = "otp_hash", nullable = false, length = 255)
    private String otpHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "verify_attempts", nullable = false)
    @Builder.Default
    private Integer verifyAttempts = 0;

    @Column(name = "is_verified", nullable = false)
    @Builder.Default
    private Boolean isVerified = false;
}
