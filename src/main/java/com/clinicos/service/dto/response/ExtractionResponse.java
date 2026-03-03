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
public class ExtractionResponse {

    private List<Prescription> prescriptions;
    private Vitals vitals;
    private List<LabOrder> labOrders;
    private FollowUp followUp;
    private String examination;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Prescription {
        private String medicineName;
        private String dosage;
        private String frequency;
        private String timing;
        private String duration;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Vitals {
        @Builder.Default private String bp = "";
        @Builder.Default private String pulse = "";
        @Builder.Default private String temp = "";
        @Builder.Default private String weight = "";
        @Builder.Default private String spo2 = "";
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LabOrder {
        private String testName;
        private String type;
        private String notes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FollowUp {
        private Integer days;
        private String notes;
    }
}
