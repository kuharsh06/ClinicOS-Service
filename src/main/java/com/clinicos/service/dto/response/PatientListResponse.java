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
public class PatientListResponse {

    private List<PatientListItem> patients;
    private Meta meta;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PatientListItem {
        private String patientId;
        private String phone;
        private String name;
        private Integer age;
        private String gender;
        private Integer totalVisits;
        private String lastVisitDate;
        private List<String> lastComplaintTags;
        private Boolean isRegular;
        private Boolean smsConsent;
        private String createdAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Meta {
        private CursorPagination pagination;
        private Long serverTimestamp;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CursorPagination {
        private String nextCursor;
        private Boolean hasMore;
    }
}
