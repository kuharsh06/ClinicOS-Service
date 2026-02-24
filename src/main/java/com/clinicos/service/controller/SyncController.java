package com.clinicos.service.controller;

import com.clinicos.service.dto.request.SyncPushRequest;
import com.clinicos.service.dto.response.ApiResponse;
import com.clinicos.service.dto.response.SyncPullResponse;
import com.clinicos.service.dto.response.SyncPushResponse;
import com.clinicos.service.security.CustomUserDetails;
import com.clinicos.service.service.SyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/sync")
@RequiredArgsConstructor
@Tag(name = "Sync", description = "Event sync protocol endpoints (offline-first core)")
public class SyncController {

    private final SyncService syncService;

    @PostMapping("/push")
    @Operation(summary = "Push Events", description = "Push local events to server for processing")
    public ResponseEntity<ApiResponse<SyncPushResponse>> pushEvents(
            @Valid @RequestBody SyncPushRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        SyncPushResponse response = syncService.pushEvents(request, userDetails);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/pull")
    @Operation(summary = "Pull Events", description = "Pull remote events from server")
    public ResponseEntity<ApiResponse<SyncPullResponse>> pullEvents(
            @RequestParam(required = false) Long since,
            @RequestParam String deviceId,
            @RequestParam(required = false, defaultValue = "100") Integer limit,
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        SyncPullResponse response = syncService.pullEvents(since, deviceId, limit, userDetails);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
