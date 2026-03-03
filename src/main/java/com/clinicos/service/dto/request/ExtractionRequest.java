package com.clinicos.service.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExtractionRequest {

    @NotBlank(message = "Transcript is required")
    private String transcript;

    private Map<String, Object> currentState;  // nullable — existing consultation data for re-dictation merge
}
