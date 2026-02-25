package com.clinicos.service.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateBillTemplateRequest {

    private String name;
    private Integer defaultAmount;
    private Boolean isDefault;
    private Integer sortOrder;
}
