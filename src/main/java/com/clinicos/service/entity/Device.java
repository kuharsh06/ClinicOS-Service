package com.clinicos.service.entity;

import com.clinicos.service.enums.Platform;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "devices", indexes = {
        @Index(name = "idx_devices_uuid", columnList = "uuid", unique = true),
        @Index(name = "idx_devices_device_id", columnList = "device_id", unique = true),
        @Index(name = "idx_devices_user_id", columnList = "user_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Device extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "device_id", nullable = false, length = 100)
    private String deviceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, length = 20)
    private Platform platform;

    @Column(name = "os_version", length = 20)
    private String osVersion;

    @Column(name = "app_version", length = 20)
    private String appVersion;

    @Column(name = "device_model", length = 100)
    private String deviceModel;

    @Column(name = "push_token", length = 255)
    private String pushToken;

    @Column(name = "last_active_at")
    private Instant lastActiveAt;
}
