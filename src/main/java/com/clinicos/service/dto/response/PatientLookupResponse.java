package com.clinicos.service.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PatientLookupResponse {

    private Boolean found;
    private PatientSummary patient;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PatientSummary {
        private String patientId;
        private String phone;
        private String name;
        private Integer age;
        private String gender;
        private Integer totalVisits;
        private String lastVisitDate;
        private List<String> lastComplaintTags;
        private Boolean isRegular;  // >3 visits
    }
}
