package com.clinicos.service.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ManualSmsSendRequest {

    @NotBlank(message = "Entry ID is required")
    private String entryId;

    @NotBlank(message = "Template type is required")
    private String templateType;  // registration, turn_near, turn_now, bill_generated, stashed

    private String customMessage;  // optional, must be <160 chars
}
