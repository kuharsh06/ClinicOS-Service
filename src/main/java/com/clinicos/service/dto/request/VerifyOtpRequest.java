package com.clinicos.service.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerifyOtpRequest {

    @NotBlank(message = "Request ID is required")
    private String requestId;

    @NotBlank(message = "OTP is required")
    @Pattern(regexp = "^[0-9]{4,6}$", message = "OTP must be 4-6 digits")
    private String otp;

    @NotBlank(message = "Device ID is required")
    private String deviceId;

    @Valid
    @NotNull(message = "Device info is required")
    private DeviceInfo deviceInfo;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeviceInfo {
        @NotBlank(message = "Platform is required")
        private String platform;  // "android" | "ios"

        private String osVersion;
        private String appVersion;
        private String deviceModel;
    }
}
