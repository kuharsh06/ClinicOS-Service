package com.clinicos.service.controller;

import com.clinicos.service.dto.response.ApiResponse;
import com.clinicos.service.dto.response.ImageDetailResponse;
import com.clinicos.service.dto.response.ImageListResponse;
import com.clinicos.service.dto.response.ImageUploadResponse;
import com.clinicos.service.security.CustomUserDetails;
import com.clinicos.service.security.RequirePermission;
import com.clinicos.service.service.ImageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/v1/orgs/{orgId}")
@RequiredArgsConstructor
public class ImageController {

    private final ImageService imageService;

    /**
     * Upload an image or PDF.
     * POST /v1/orgs/:orgId/images/upload
     */
    @PostMapping("/images/upload")
    @RequirePermission("visit:create")
    public ResponseEntity<ApiResponse<ImageUploadResponse>> upload(
            @PathVariable String orgId,
            @RequestParam("file") MultipartFile file,
            @RequestParam String patientId,
            @RequestParam(required = false) String visitId,
            @RequestParam(required = false) String caption,
            @RequestParam(required = false) String tags,
            @RequestParam(required = false) String metadata,
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        ImageUploadResponse response = imageService.upload(
                orgId, patientId, visitId, file, caption, tags, metadata, userDetails);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    /**
     * List images for a patient.
     * GET /v1/orgs/:orgId/patients/:patientId/images
     */
    @GetMapping("/patients/{patientId}/images")
    @RequirePermission("patient:view_clinical")
    public ResponseEntity<ApiResponse<ImageListResponse>> listByPatient(
            @PathVariable String orgId,
            @PathVariable String patientId,
            @RequestParam(required = false) String fileType,
            @RequestParam(required = false) String afterCursor,
            @RequestParam(required = false, defaultValue = "20") Integer limit) {

        ImageListResponse response = imageService.listByPatient(orgId, patientId, fileType, afterCursor, limit);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * List images uploaded by a doctor.
     * GET /v1/orgs/:orgId/doctors/:doctorId/images
     */
    @GetMapping("/doctors/{doctorId}/images")
    @RequirePermission("patient:view_clinical")
    public ResponseEntity<ApiResponse<ImageListResponse>> listByDoctor(
            @PathVariable String orgId,
            @PathVariable String doctorId,
            @RequestParam(required = false) String afterCursor,
            @RequestParam(required = false, defaultValue = "20") Integer limit) {

        ImageListResponse response = imageService.listByDoctor(orgId, doctorId, afterCursor, limit);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * List images for a visit.
     * GET /v1/orgs/:orgId/visits/:visitId/images
     */
    @GetMapping("/visits/{visitId}/images")
    @RequirePermission("visit:view")
    public ResponseEntity<ApiResponse<ImageListResponse>> listByVisit(
            @PathVariable String orgId,
            @PathVariable String visitId) {

        ImageListResponse response = imageService.listByVisit(orgId, visitId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Get single image detail.
     * GET /v1/orgs/:orgId/images/:imageId
     */
    @GetMapping("/images/{imageId}")
    @RequirePermission("patient:view_clinical")
    public ResponseEntity<ApiResponse<ImageDetailResponse>> getImage(
            @PathVariable String orgId,
            @PathVariable String imageId) {

        ImageDetailResponse response = imageService.getImage(orgId, imageId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
