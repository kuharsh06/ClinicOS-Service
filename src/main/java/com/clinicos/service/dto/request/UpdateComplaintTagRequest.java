package com.clinicos.service.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateComplaintTagRequest {

    private String labelEn;
    private String labelHi;
    private Integer sortOrder;
    private Boolean isCommon;
    private Boolean isActive;
}
