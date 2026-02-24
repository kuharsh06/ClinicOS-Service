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
public class SyncPushResponse {

    private List<AcceptedEvent> accepted;
    private List<RejectedEvent> rejected;
    private Long serverTimestamp;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AcceptedEvent {
        private String eventId;
        private Long serverReceivedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RejectedEvent {
        private String eventId;
        private String reason;
        private String code;  // INVALID_STATE, UNAUTHORIZED_ROLE, ENTITY_NOT_FOUND, SCHEMA_MISMATCH, DUPLICATE_IGNORED
    }
}
