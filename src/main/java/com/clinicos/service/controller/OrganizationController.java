package com.clinicos.service.controller;

import com.clinicos.service.dto.request.CreateOrgRequest;
import com.clinicos.service.dto.response.ApiResponse;
import com.clinicos.service.dto.response.CreateOrgResponse;
import com.clinicos.service.dto.response.OrganizationResponse;
import com.clinicos.service.security.CustomUserDetails;
import com.clinicos.service.security.RequirePermission;
import com.clinicos.service.service.OrganizationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/v1/orgs")
@RequiredArgsConstructor
@Tag(name = "Organization", description = "Organization management endpoints")
public class OrganizationController {

    private final OrganizationService organizationService;

    @PostMapping
    @Operation(summary = "Create Organization", description = "Create a new organization. Creator becomes admin.")
    public ResponseEntity<ApiResponse<CreateOrgResponse>> createOrganization(
            @Valid @RequestBody CreateOrgRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestHeader("X-Device-Id") String deviceId) {

        CreateOrgResponse response = organizationService.createOrganization(
                request,
                userDetails.getId(),
                deviceId
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @GetMapping("/{orgId}")
    @Operation(summary = "Get Organization", description = "Get organization details")
    @RequirePermission("settings:view")
    public ResponseEntity<ApiResponse<OrganizationResponse>> getOrganization(
            @PathVariable String orgId) {

        OrganizationResponse response = organizationService.getOrganization(orgId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PutMapping("/{orgId}")
    @Operation(summary = "Update Organization", description = "Update organization details")
    @RequirePermission("settings:edit_org")
    public ResponseEntity<ApiResponse<OrganizationResponse>> updateOrganization(
            @PathVariable String orgId,
            @RequestBody Map<String, Object> updates) {

        OrganizationResponse response = organizationService.updateOrganization(orgId, updates);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
