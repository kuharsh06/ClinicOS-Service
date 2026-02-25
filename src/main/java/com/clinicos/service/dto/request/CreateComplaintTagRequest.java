package com.clinicos.service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateComplaintTagRequest {

    @NotBlank(message = "Tag key is required")
    @Pattern(regexp = "^[a-z0-9_]{1,50}$", message = "Tag key must be lowercase alphanumeric with underscores, max 50 chars")
    private String key;

    @NotBlank(message = "English label is required")
    private String labelEn;

    private String labelHi;

    private Integer sortOrder;

    @Builder.Default
    private Boolean isCommon = false;
}
