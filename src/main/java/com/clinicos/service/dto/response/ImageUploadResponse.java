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
public class ImageUploadResponse {

    private String imageId;
    private String imageUrl;
    private String thumbnailUrl;
    private String originalFilename;
    private String fileType;
    private String mimeType;
    private Integer fileSizeBytes;
    private String caption;
    private List<String> tags;
    private String uploadedAt;
}
