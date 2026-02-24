package com.clinicos.service.dto.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
public class CreateOrgRequest {

    @NotBlank(message = "Organization name is required")
    private String name;

    @NotBlank(message = "Address is required")
    private String address;

    @NotBlank(message = "City is required")
    private String city;

    @NotBlank(message = "State is required")
    private String state;

    @NotBlank(message = "PIN code is required")
    @Pattern(regexp = "^[0-9]{6}$", message = "PIN must be 6 digits")
    private String pin;

    private String logo;  // base64 (max 500KB)

    @Builder.Default
    private String brandColor = "#059669";

    @Builder.Default
    private String smsLanguage = "hi";  // ISO 639-1 code

    @Builder.Default
    private String clinicalDataVisibility = "all_members";  // "all_members" | "clinical_roles_only"

    @Valid
    @NotNull(message = "Working hours are required")
    private WorkingHours workingHours;

    @Valid
    @NotNull(message = "Creator info is required")
    private Creator creator;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WorkingHours {
        private DaySchedule monday;
        private DaySchedule tuesday;
        private DaySchedule wednesday;
        private DaySchedule thursday;
        private DaySchedule friday;
        private DaySchedule saturday;
        private DaySchedule sunday;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DaySchedule {
        private List<Shift> shifts;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Shift {
        private String open;   // "09:00" (24h format)
        private String close;  // "13:00"
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Creator {
        @NotBlank(message = "Creator name is required")
        private String name;
    }
}
