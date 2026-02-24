package com.clinicos.service.controller;

import com.clinicos.service.dto.request.ManualSmsSendRequest;
import com.clinicos.service.dto.response.ApiResponse;
import com.clinicos.service.dto.response.QueueSmsStatusResponse;
import com.clinicos.service.dto.response.SmsTemplatesResponse;
import com.clinicos.service.security.CustomUserDetails;
import com.clinicos.service.security.RequirePermission;
import com.clinicos.service.service.SmsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/v1/orgs/{orgId}")
@RequiredArgsConstructor
public class SmsController {

    private final SmsService smsService;

    /**
     * Get SMS templates.
     * GET /v1/orgs/:orgId/sms/templates
     */
    @GetMapping("/sms/templates")
    @RequirePermission("sms:view_templates")
    public ResponseEntity<ApiResponse<SmsTemplatesResponse>> getTemplates(
            @PathVariable String orgId) {

        SmsTemplatesResponse response = smsService.getTemplates(orgId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Get SMS delivery status for a queue.
     * GET /v1/orgs/:orgId/queues/:queueId/sms-status
     */
    @GetMapping("/queues/{queueId}/sms-status")
    @RequirePermission("queue:view")
    public ResponseEntity<ApiResponse<QueueSmsStatusResponse>> getQueueSmsStatus(
            @PathVariable String orgId,
            @PathVariable String queueId) {

        QueueSmsStatusResponse response = smsService.getQueueSmsStatus(orgId, queueId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Manually send or resend an SMS.
     * POST /v1/orgs/:orgId/sms/send
     */
    @PostMapping("/sms/send")
    @RequirePermission("sms:manual_send")
    public ResponseEntity<ApiResponse<Map<String, Object>>> sendManualSms(
            @PathVariable String orgId,
            @RequestBody @Valid ManualSmsSendRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        Map<String, Object> result = smsService.sendManualSms(orgId, request, userDetails.getUserId());
        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
