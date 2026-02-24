package com.clinicos.service.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QueueSmsStatusResponse {

    private Map<String, EntrySmsStatus> statuses;  // keyed by entryId

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class EntrySmsStatus {
        private String registration;  // queued, sent, delivered, failed, dnd_blocked
        private String turnNear;
        private String turnNow;
        private String bill;
    }
}
