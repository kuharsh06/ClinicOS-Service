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
public class BillResponse {

    private String billId;
    private String orgId;
    private String patientId;
    private String queueEntryId;
    private List<BillItem> items;
    private Integer totalAmount;
    private Boolean isPaid;
    private String paidAt;
    private String createdAt;

    // Denormalized for display
    private String patientName;
    private String patientPhone;
    private Integer tokenNumber;
    private String doctorName;
    private String clinicName;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BillItem {
        private String itemId;
        private String name;
        private Integer amount;
    }
}
