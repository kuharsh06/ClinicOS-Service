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
public class PatientThreadResponse {

    private PatientSummary patient;
    private List<VisitDto> visits;
    private Meta meta;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PatientSummary {
        private String patientId;
        private String name;
        private String phone;
        private Integer age;
        private String gender;
        private Integer totalVisits;
        private Boolean isRegular;
        private String createdAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class VisitDto {
        private String visitId;
        private String patientId;
        private String queueEntryId;
        private String date;
        private List<String> complaintTags;
        private Map<String, Object> data;  // null when redacted
        private Integer schemaVersion;
        private List<ImageRef> images;  // empty when redacted
        private CreatedBy createdBy;
        private String createdAt;
        private String updatedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ImageRef {
        private String imageId;
        private String thumbnailUrl;
        private String fullUrl;
        private String caption;
        private String uploadedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CreatedBy {
        private String userId;
        private String name;
        private String role;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Meta {
        private PatientListResponse.CursorPagination pagination;
    }
}
