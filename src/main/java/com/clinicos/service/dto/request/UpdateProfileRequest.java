package com.clinicos.service.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateProfileRequest {

    @NotNull(message = "Profile data is required")
    private Map<String, Object> profileData;

    @NotNull(message = "Profile schema version is required")
    private Integer profileSchemaVersion;
}
