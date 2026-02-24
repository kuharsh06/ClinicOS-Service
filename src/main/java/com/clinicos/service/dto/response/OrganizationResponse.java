package com.clinicos.service.dto.response;

import com.clinicos.service.dto.request.CreateOrgRequest;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OrganizationResponse {

    private String orgId;
    private String name;
    private String logo;
    private String brandColor;
    private String address;
    private String city;
    private String state;
    private String pin;
    private String smsLanguage;
    private String clinicalDataVisibility;
    private CreateOrgRequest.WorkingHours workingHours;
    private String createdAt;

    // Computed fields
    private String nextWorkingDay;  // ISO date
    private Boolean isOpenToday;
    private CreateOrgRequest.Shift currentShift;
}
