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
public class AnalyticsResponse {

    private String period;  // "today" | "week" | "month"
    private DateRange dateRange;
    private Summary summary;
    private Comparison comparison;
    private List<DailyBreakdown> dailyBreakdown;
    private List<TopComplaint> topComplaints;
    private List<HourlyDistribution> hourlyDistribution;  // today only

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DateRange {
        private String from;
        private String to;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Summary {
        private Integer totalPatients;
        private Integer totalRevenue;
        private Long avgWaitTimeMs;
        private Long avgConsultationTimeMs;
        private String busiestHour;
        private Double queueCompletionRate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Comparison {
        private ChangeMetric patients;
        private ChangeMetric revenue;
        private ChangeMetric avgWait;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ChangeMetric {
        private Integer value;
        private Double changePercent;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DailyBreakdown {
        private String date;
        private Integer patients;
        private Integer revenue;
        private Long avgWaitMs;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TopComplaint {
        private String tagKey;
        private String label;
        private Integer count;
        private Double percentage;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class HourlyDistribution {
        private String hour;
        private Integer patients;
    }
}
