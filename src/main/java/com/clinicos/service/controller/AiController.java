package com.clinicos.service.controller;

import com.clinicos.service.dto.request.ExtractionRequest;
import com.clinicos.service.dto.response.ApiResponse;
import com.clinicos.service.dto.response.ExtractionResponse;
import com.clinicos.service.security.RequirePermission;
import com.clinicos.service.service.AiExtractionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/orgs/{orgId}")
@RequiredArgsConstructor
public class AiController {

    private final AiExtractionService aiExtractionService;

    /**
     * Extract structured clinical data from a voice transcript using AI.
     * POST /v1/orgs/:orgId/ai/extract
     */
    @PostMapping("/ai/extract")
    @RequirePermission("visit:create")
    public ResponseEntity<ApiResponse<ExtractionResponse>> extract(
            @PathVariable String orgId,
            @RequestBody @Valid ExtractionRequest request) {

        ExtractionResponse response = aiExtractionService.extract(
                request.getTranscript(), request.getCurrentState());
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
