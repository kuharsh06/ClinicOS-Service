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
public class ImageDetailResponse {

    private String imageId;
    private String imageUrl;
    private String thumbnailUrl;
    private String originalFilename;
    private String fileType;
    private String mimeType;
    private Integer fileSizeBytes;
    private String caption;
    private List<String> tags;
    private Map<String, Object> metadata;
    private Map<String, Object> aiAnalysis;
    private String visitId;
    private String patientId;
    private String doctorUuid;
    private String doctorName;
    private String uploadedAt;
}
