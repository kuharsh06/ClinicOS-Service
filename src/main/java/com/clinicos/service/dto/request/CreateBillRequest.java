package com.clinicos.service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateBillRequest {

    @NotBlank(message = "Patient ID is required")
    private String patientId;

    @NotBlank(message = "Queue entry ID is required")
    private String queueEntryId;

    @NotNull(message = "Bill items are required")
    private List<BillItemInput> items;

    private Boolean sendSMS;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BillItemInput {
        private String name;
        private Integer amount;  // INR
    }
}
