package com.clinicos.service.controller;

import com.clinicos.service.dto.response.AnalyticsResponse;
import com.clinicos.service.dto.response.ApiResponse;
import com.clinicos.service.security.RequirePermission;
import com.clinicos.service.service.AnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/orgs/{orgId}")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    /**
     * Get dashboard analytics.
     * GET /v1/orgs/:orgId/analytics?period=today|week|month&doctorId=<id>
     */
    @GetMapping("/analytics")
    @RequirePermission("analytics:view")
    public ResponseEntity<ApiResponse<AnalyticsResponse>> getAnalytics(
            @PathVariable String orgId,
            @RequestParam(required = false, defaultValue = "today") String period,
            @RequestParam(required = false) String doctorId) {

        AnalyticsResponse response = analyticsService.getAnalytics(orgId, period, doctorId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
