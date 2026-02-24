package com.clinicos.service.dto.request;

import jakarta.validation.constraints.NotNull;
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
public class CreateVisitRequest {

    private String queueEntryId;
    private List<String> complaintTags;

    @NotNull(message = "Data is required")
    private Map<String, Object> data;

    @NotNull(message = "Schema version is required")
    private Integer schemaVersion;

    private List<ImageRef> images;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImageRef {
        private String imageId;
        private String thumbnailUrl;
        private String fullUrl;
        private String caption;
        private String uploadedAt;
    }
}
