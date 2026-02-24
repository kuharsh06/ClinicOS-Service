package com.clinicos.service.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
public class SyncPushRequest {

    @NotBlank(message = "Device ID is required")
    private String deviceId;

    @Valid
    @NotNull(message = "Events list is required")
    @Size(max = 50, message = "Maximum 50 events per batch")
    private List<SyncEvent> events;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SyncEvent {
        @NotBlank(message = "Event ID is required")
        private String eventId;

        @NotBlank(message = "Device ID is required")
        private String deviceId;

        @NotBlank(message = "User ID is required")
        private String userId;

        private List<String> userRoles;  // Informational only

        @NotBlank(message = "Event type is required")
        private String eventType;

        @NotBlank(message = "Target entity is required")
        private String targetEntity;

        @NotBlank(message = "Target table is required")
        private String targetTable;

        @NotNull(message = "Payload is required")
        private Map<String, Object> payload;

        @NotNull(message = "Device timestamp is required")
        private Long deviceTimestamp;

        private Long serverReceivedAt;

        private Boolean synced;

        @Builder.Default
        private Integer schemaVersion = 1;
    }
}
