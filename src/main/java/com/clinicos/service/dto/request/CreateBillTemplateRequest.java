package com.clinicos.service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateBillTemplateRequest {

    @NotBlank(message = "Name is required")
    private String name;

    @NotNull(message = "Default amount is required")
    private Integer defaultAmount;

    private Boolean isDefault;
}
