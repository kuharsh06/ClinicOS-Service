package com.clinicos.service.service;

import com.clinicos.service.dto.response.ImageDetailResponse;
import com.clinicos.service.dto.response.ImageListResponse;
import com.clinicos.service.dto.response.ImageUploadResponse;
import com.clinicos.service.entity.*;
import com.clinicos.service.exception.BusinessException;
import com.clinicos.service.exception.ResourceNotFoundException;
import com.clinicos.service.repository.*;
import com.clinicos.service.security.CustomUserDetails;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ImageService {

    private final StorageService storageService;
    private final ThumbnailService thumbnailService;
    private final VisitImageRepository visitImageRepository;
    private final PatientRepository patientRepository;
    private final VisitRepository visitRepository;
    private final OrganizationRepository organizationRepository;
    private final OrgMemberRepository orgMemberRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    private static final Set<String> ALLOWED_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "application/pdf"
    );
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    @Transactional
    public ImageUploadResponse upload(String orgUuid, String patientUuid, String visitUuid,
                                       MultipartFile file, String caption, String tagsStr,
                                       String metadataJson, CustomUserDetails userDetails) {
        // Resolve user UUID from auth context
        User user = userRepository.findById(userDetails.getId())
                .orElseThrow(() -> new RuntimeException("User not found"));
        String userUuid = user.getUuid();

        // Validate file
        if (file == null || file.isEmpty()) {
            throw new BusinessException("File is required");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException("File size exceeds 10MB limit");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType)) {
            throw new BusinessException("Unsupported file type: " + contentType + ". Allowed: JPEG, PNG, WebP, PDF");
        }

        // Validate org
        Organization org = organizationRepository.findByUuid(orgUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", orgUuid));

        // Validate patient if provided
        Patient patient = null;
        if (patientUuid != null && !patientUuid.isBlank()) {
            patient = patientRepository.findByUuid(patientUuid)
                    .orElseThrow(() -> new ResourceNotFoundException("Patient", patientUuid));
            if (!patient.getOrganization().getId().equals(org.getId())) {
                throw new ResourceNotFoundException("Patient", patientUuid);
            }
        }

        // Validate visit if provided
        Visit visit = null;
        if (visitUuid != null && !visitUuid.isBlank()) {
            visit = visitRepository.findByUuid(visitUuid).orElse(null);
            if (visit != null && !visit.getOrganization().getId().equals(org.getId())) {
                visit = null;
            }
        }

        // Get uploader
        OrgMember uploadedBy = orgMemberRepository.findByOrgIdAndUserUuid(org.getId(), userUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Member", userUuid));

        // Determine file extension and type
        String originalFilename = file.getOriginalFilename();
        String ext = getExtension(originalFilename, contentType);
        String fileType = contentType.equals("application/pdf") ? "pdf" : "image";

        // Generate storage key
        String fileUuid = UUID.randomUUID().toString();
        String storageKey = patient != null
                ? orgUuid + "/patients/" + patientUuid + "/" + fileUuid + "." + ext
                : orgUuid + "/general/" + fileUuid + "." + ext;

        try {
            // TODO: Optimize memory — stream to temp file instead of byte[] to avoid heap pressure
            // Currently holds entire file in memory (~10-20MB per upload). Acceptable for low concurrency
            // but should use file.transferTo(tempFile) + FileInputStream for production scale.
            byte[] fileBytes = file.getBytes();

            // Strip EXIF metadata (GPS coordinates, camera info) from images before storage.
            // Frontend also strips EXIF — this is a server-side safety layer.
            fileBytes = stripExifMetadata(fileBytes, contentType);

            StorageService.StorageResult result = storageService.store(
                    storageKey, new ByteArrayInputStream(fileBytes), fileBytes.length, contentType);

            // Generate and store thumbnail
            String thumbnailUrl = null;
            try {
                byte[] thumbnailBytes = thumbnailService.generateThumbnail(fileBytes, contentType);
                if (thumbnailBytes != null) {
                    int dotIndex = storageKey.lastIndexOf('.');
                    String thumbKey = (dotIndex > 0 ? storageKey.substring(0, dotIndex) : storageKey) + "_thumb.jpg";
                    StorageService.StorageResult thumbResult = storageService.store(
                            thumbKey, thumbnailService.toInputStream(thumbnailBytes), thumbnailBytes.length, "image/jpeg");
                    thumbnailUrl = thumbResult.url();
                }
            } catch (Exception e) {
                log.warn("Thumbnail generation failed for {}, saving without thumbnail: {}", storageKey, e.getMessage());
            }

            // Parse tags
            List<String> tags = tagsStr != null && !tagsStr.isBlank()
                    ? Arrays.stream(tagsStr.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList())
                    : null;

            // Create VisitImage record
            VisitImage image = VisitImage.builder()
                    .visit(visit)
                    .patient(patient)
                    .organization(org)
                    .doctorUuid(userUuid)
                    .imageUrl(result.url())
                    .thumbnailUrl(thumbnailUrl)
                    .caption(caption)
                    .sortOrder(0)
                    .fileSizeBytes((int) fileBytes.length)
                    .mimeType(contentType)
                    .originalFilename(originalFilename)
                    .fileType(fileType)
                    .storageKey(storageKey)
                    .tags(tags != null ? toJson(tags) : null)
                    .metadata(metadataJson)
                    .uploadedBy(uploadedBy)
                    .build();
            visitImageRepository.save(image);

            log.info("Image uploaded: {} ({} bytes) for patient {} by {}",
                    image.getUuid(), fileBytes.length, patientUuid, userUuid);

            return ImageUploadResponse.builder()
                    .imageId(image.getUuid())
                    .imageUrl(result.url())
                    .thumbnailUrl(thumbnailUrl)
                    .originalFilename(originalFilename)
                    .fileType(fileType)
                    .mimeType(contentType)
                    .fileSizeBytes((int) fileBytes.length)
                    .caption(caption)
                    .tags(tags)
                    .uploadedAt(image.getCreatedAt() != null ? image.getCreatedAt().toString() : null)
                    .build();

        } catch (Exception e) {
            log.error("Failed to upload image for patient {}: {}", patientUuid, e.getMessage());
            throw new RuntimeException("Failed to upload file: " + e.getMessage(), e);
        }
    }

    @Transactional(readOnly = true)
    public ImageListResponse listByPatient(String orgUuid, String patientUuid,
                                            String fileType, String afterCursor, Integer limit) {
        Organization org = organizationRepository.findByUuid(orgUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", orgUuid));
        Patient patient = patientRepository.findByUuid(patientUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Patient", patientUuid));
        if (!patient.getOrganization().getId().equals(org.getId())) {
            throw new ResourceNotFoundException("Patient", patientUuid);
        }

        int pageSize = clampLimit(limit);
        List<VisitImage> images;

        Integer cursorId = decodeCursor(afterCursor);

        if (fileType != null && !fileType.isBlank()) {
            images = cursorId != null
                    ? visitImageRepository.findByPatientAndOrgAndFileTypeAfterCursor(patient.getId(), org.getId(), fileType, cursorId, PageRequest.of(0, pageSize + 1))
                    : visitImageRepository.findByPatientAndOrgAndFileType(patient.getId(), org.getId(), fileType, PageRequest.of(0, pageSize + 1));
        } else {
            images = cursorId != null
                    ? visitImageRepository.findByPatientAndOrgAfterCursor(patient.getId(), org.getId(), cursorId, PageRequest.of(0, pageSize + 1))
                    : visitImageRepository.findByPatientAndOrg(patient.getId(), org.getId(), PageRequest.of(0, pageSize + 1));
        }

        return toListResponse(images, pageSize);
    }

    @Transactional(readOnly = true)
    public ImageListResponse listByDoctor(String orgUuid, String doctorUuid,
                                           String afterCursor, Integer limit) {
        Organization org = organizationRepository.findByUuid(orgUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", orgUuid));

        int pageSize = clampLimit(limit);
        Integer cursorId = decodeCursor(afterCursor);

        List<VisitImage> images = cursorId != null
                ? visitImageRepository.findByDoctorAndOrgAfterCursor(doctorUuid, org.getId(), cursorId, PageRequest.of(0, pageSize + 1))
                : visitImageRepository.findByDoctorAndOrg(doctorUuid, org.getId(), PageRequest.of(0, pageSize + 1));

        return toListResponse(images, pageSize);
    }

    @Transactional(readOnly = true)
    public ImageListResponse listByOrg(String orgUuid, String fileType, String afterCursor, Integer limit) {
        Organization org = organizationRepository.findByUuid(orgUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", orgUuid));

        int pageSize = clampLimit(limit);
        Integer cursorId = decodeCursor(afterCursor);

        List<VisitImage> images;
        if (fileType != null && !fileType.isBlank()) {
            images = cursorId != null
                    ? visitImageRepository.findByOrgAndFileTypeAfterCursor(org.getId(), fileType, cursorId, PageRequest.of(0, pageSize + 1))
                    : visitImageRepository.findByOrgAndFileType(org.getId(), fileType, PageRequest.of(0, pageSize + 1));
        } else {
            images = cursorId != null
                    ? visitImageRepository.findByOrgAfterCursor(org.getId(), cursorId, PageRequest.of(0, pageSize + 1))
                    : visitImageRepository.findByOrg(org.getId(), PageRequest.of(0, pageSize + 1));
        }

        return toListResponse(images, pageSize);
    }

    @Transactional(readOnly = true)
    public ImageListResponse listMyUploads(String orgUuid, String afterCursor, Integer limit, CustomUserDetails userDetails) {
        Organization org = organizationRepository.findByUuid(orgUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", orgUuid));

        User user = userRepository.findById(userDetails.getId())
                .orElseThrow(() -> new RuntimeException("User not found"));

        int pageSize = clampLimit(limit);
        Integer cursorId = decodeCursor(afterCursor);

        List<VisitImage> images = cursorId != null
                ? visitImageRepository.findByDoctorAndOrgAfterCursor(user.getUuid(), org.getId(), cursorId, PageRequest.of(0, pageSize + 1))
                : visitImageRepository.findByDoctorAndOrg(user.getUuid(), org.getId(), PageRequest.of(0, pageSize + 1));

        return toListResponse(images, pageSize);
    }

    @Transactional(readOnly = true)
    public ImageListResponse listByVisit(String orgUuid, String visitUuid) {
        Organization org = organizationRepository.findByUuid(orgUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", orgUuid));
        Visit visit = visitRepository.findByUuid(visitUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Visit", visitUuid));
        if (!visit.getOrganization().getId().equals(org.getId())) {
            throw new ResourceNotFoundException("Visit", visitUuid);
        }

        List<VisitImage> images = visitImageRepository.findByVisitIdAndDeletedAtIsNullOrderBySortOrderAsc(visit.getId());

        List<ImageListResponse.ImageSummary> summaries = images.stream()
                .map(this::toSummary)
                .collect(Collectors.toList());

        return ImageListResponse.builder()
                .images(summaries)
                .build();
    }

    @Transactional(readOnly = true)
    public ImageDetailResponse getImage(String orgUuid, String imageUuid) {
        Organization org = organizationRepository.findByUuid(orgUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", orgUuid));
        VisitImage image = visitImageRepository.findByUuid(imageUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Image", imageUuid));
        if (!image.getOrganization().getId().equals(org.getId())) {
            throw new ResourceNotFoundException("Image", imageUuid);
        }

        // Get doctor name for display
        String doctorName = null;
        if (image.getDoctorUuid() != null) {
            doctorName = orgMemberRepository.findByOrgIdAndUserUuid(org.getId(), image.getDoctorUuid())
                    .map(m -> m.getUser().getName())
                    .orElse(null);
        }

        return ImageDetailResponse.builder()
                .imageId(image.getUuid())
                .imageUrl(image.getImageUrl())
                .thumbnailUrl(image.getThumbnailUrl())
                .originalFilename(image.getOriginalFilename())
                .fileType(image.getFileType())
                .mimeType(image.getMimeType())
                .fileSizeBytes(image.getFileSizeBytes())
                .caption(image.getCaption())
                .tags(fromJsonList(image.getTags()))
                .metadata(fromJsonMap(image.getMetadata()))
                .aiAnalysis(fromJsonMap(image.getAiAnalysis()))
                .visitId(image.getVisit() != null ? image.getVisit().getUuid() : null)
                .patientId(image.getPatient().getUuid())
                .doctorUuid(image.getDoctorUuid())
                .doctorName(doctorName)
                .uploadedAt(image.getCreatedAt() != null ? image.getCreatedAt().toString() : null)
                .build();
    }

    // ==================== Helpers ====================

    /**
     * Strips EXIF metadata (GPS coordinates, camera info, etc.) from JPEG and PNG images
     * by re-encoding through ImageIO, which discards all metadata.
     * Non-image types (PDF, WebP) are returned unchanged.
     */
    private byte[] stripExifMetadata(byte[] fileBytes, String contentType) {
        if (contentType == null) return fileBytes;
        if (!contentType.equals("image/jpeg") && !contentType.equals("image/png")) {
            return fileBytes;
        }

        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(fileBytes));
            if (image == null) return fileBytes;

            ByteArrayOutputStream out = new ByteArrayOutputStream();

            if (contentType.equals("image/jpeg")) {
                ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
                ImageWriteParam param = writer.getDefaultWriteParam();
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(0.95f);
                try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
                    writer.setOutput(ios);
                    writer.write(null, new IIOImage(image, null, null), param);
                }
                writer.dispose();
            } else {
                ImageIO.write(image, "png", out);
            }

            log.debug("EXIF metadata stripped from {} ({} -> {} bytes)", contentType, fileBytes.length, out.size());
            return out.toByteArray();
        } catch (Exception e) {
            log.warn("Failed to strip EXIF metadata, storing original: {}", e.getMessage());
            return fileBytes;
        }
    }

    private ImageListResponse toListResponse(List<VisitImage> images, int pageSize) {
        boolean hasMore = images.size() > pageSize;
        if (hasMore) {
            images = images.subList(0, pageSize);
        }

        List<ImageListResponse.ImageSummary> summaries = images.stream()
                .map(this::toSummary)
                .collect(Collectors.toList());

        String nextCursor = hasMore && !images.isEmpty()
                ? images.get(images.size() - 1).getUuid()
                : null;

        return ImageListResponse.builder()
                .images(summaries)
                .meta(ImageListResponse.Meta.builder()
                        .pagination(ImageListResponse.CursorPagination.builder()
                                .hasMore(hasMore)
                                .nextCursor(nextCursor)
                                .build())
                        .build())
                .build();
    }

    private ImageListResponse.ImageSummary toSummary(VisitImage image) {
        return ImageListResponse.ImageSummary.builder()
                .imageId(image.getUuid())
                .thumbnailUrl(image.getThumbnailUrl())
                .fullUrl(image.getImageUrl())
                .originalFilename(image.getOriginalFilename())
                .fileType(image.getFileType())
                .mimeType(image.getMimeType())
                .caption(image.getCaption())
                .tags(fromJsonList(image.getTags()))
                .visitId(image.getVisit() != null ? image.getVisit().getUuid() : null)
                .patientId(image.getPatient() != null ? image.getPatient().getUuid() : null)
                .uploadedAt(image.getCreatedAt() != null ? image.getCreatedAt().toString() : null)
                .build();
    }

    private Integer decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        // Cursor is a UUID — find the image and use its ID
        return visitImageRepository.findByUuid(cursor)
                .map(VisitImage::getId)
                .orElse(null);
    }

    private int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) return DEFAULT_LIMIT;
        return Math.min(limit, MAX_LIMIT);
    }

    private String getExtension(String filename, String contentType) {
        if (filename != null && filename.contains(".")) {
            return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
        }
        return switch (contentType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "application/pdf" -> "pdf";
            default -> "bin";
        };
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> fromJsonList(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            return objectMapper.readValue(json, List.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJsonMap(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
