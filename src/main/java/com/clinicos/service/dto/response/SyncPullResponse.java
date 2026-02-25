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
public class SyncPullResponse {

    private List<SyncEventDto> events;
    private Long serverTimestamp;
    private Boolean hasMore;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SyncEventDto {
        private String eventId;
        private String deviceId;
        private String userId;
        private String orgId;
        private List<String> userRoles;
        private String eventType;
        private String targetEntity;
        private String targetTable;
        private Map<String, Object> payload;
        private Long deviceTimestamp;
        private Long serverReceivedAt;
        private Boolean synced;
        private Integer schemaVersion;
    }
}
