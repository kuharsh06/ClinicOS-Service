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
public class SmsTemplatesResponse {

    private List<SmsTemplateDto> templates;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SmsTemplateDto {
        private String templateId;
        private String trigger;  // registration, turn_near, turn_now, bill_generated, stashed, pause_delay
        private Map<String, String> templates;  // language code → template string
        private List<String> variables;
        private Boolean isActive;
        private Map<String, String> dltTemplateIds;  // language code → DLT registration ID
        private Integer maxLength;
    }
}
