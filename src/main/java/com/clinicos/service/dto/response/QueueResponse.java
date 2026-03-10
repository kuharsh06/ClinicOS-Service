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
public class QueueResponse {

    private QueueSnapshot queue;
    private Long lastEventTimestamp;
    private List<QueueEntryFull> previousQueueStash;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class QueueSnapshot {
        private String queueId;
        private String orgId;
        private String doctorId;
        private String status;  // "active" | "paused" | "ended"
        private Integer lastToken;
        private Long pauseStartTime;
        private Long totalPausedMs;
        private String createdAt;
        private String endedAt;
        private List<QueueEntryFull> entries;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class QueueEntryFull {
        private String entryId;
        private String queueId;
        private String patientId;
        private Integer tokenNumber;
        private String state;  // "waiting" | "now_serving" | "completed" | "removed" | "stashed"
        private Integer position;
        private List<String> complaintTags;
        private String complaintText;
        private Boolean isBilled;
        private Integer billAmount;
        private String billId;
        private Long registeredAt;
        private Long servedAt;
        private Long completedAt;
        private String stashedFromQueueId;

        // Denormalized patient info
        private String patientName;
        private String patientPhone;
        private Integer patientAge;
        private String patientGender;
        private Boolean smsConsent;
        private Boolean isReturningPatient;
        private Integer totalPreviousVisits;
    }
}
