package com.clinicos.service.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DoctorsListResponse {

    private List<DoctorInfo> doctors;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DoctorInfo {
        private String userId;
        private String name;
        private Map<String, Object> profileData;
        private Integer profileSchemaVersion;
        private Boolean isProfileComplete;
        private Boolean isActive;
        private String specialization;
        private Integer consultationFee;
    }
}
