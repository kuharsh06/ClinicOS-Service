package com.clinicos.service.controller;

import com.clinicos.service.dto.response.ApiResponse;
import com.clinicos.service.dto.response.SpeechTokenResponse;
import com.clinicos.service.security.CustomUserDetails;
import com.clinicos.service.security.RequirePermission;
import com.clinicos.service.service.SpeechTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/orgs/{orgId}/speech")
@RequiredArgsConstructor
@Tag(name = "Speech", description = "Speech-to-text token endpoints")
public class SpeechController {

    private final SpeechTokenService speechTokenService;

    @GetMapping("/token")
    @Operation(summary = "Get speech token", description = "Get API key for client-side speech-to-text (Deepgram)")
    @RequirePermission("visit:create")
    public ResponseEntity<ApiResponse<SpeechTokenResponse>> getToken(
            @PathVariable String orgId,
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        SpeechTokenResponse response = speechTokenService.getToken(orgId, userDetails.getId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
