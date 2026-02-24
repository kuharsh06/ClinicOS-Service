package com.clinicos.service.controller;

import com.clinicos.service.dto.request.EndQueueRequest;
import com.clinicos.service.dto.request.ImportStashRequest;
import com.clinicos.service.dto.response.ApiResponse;
import com.clinicos.service.dto.response.ComplaintTagsResponse;
import com.clinicos.service.dto.response.PatientLookupResponse;
import com.clinicos.service.dto.response.QueueResponse;
import com.clinicos.service.security.RequirePermission;
import com.clinicos.service.service.QueueService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/v1/orgs/{orgId}")
@RequiredArgsConstructor
public class QueueController {

    private final QueueService queueService;

    /**
     * Get active queue for a doctor.
     * GET /v1/orgs/:orgId/queues/active?doctorId=<id>
     */
    @GetMapping("/queues/active")
    @RequirePermission("queue:view")
    public ResponseEntity<ApiResponse<QueueResponse>> getActiveQueue(
            @PathVariable String orgId,
            @RequestParam String doctorId) {

        QueueResponse response = queueService.getActiveQueue(orgId, doctorId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Lookup patient by phone number.
     * GET /v1/orgs/:orgId/patients/lookup?phone=<10digits>
     */
    @GetMapping("/patients/lookup")
    @RequirePermission("queue:add_patient")
    public ResponseEntity<ApiResponse<PatientLookupResponse>> lookupPatient(
            @PathVariable String orgId,
            @RequestParam String phone) {

        PatientLookupResponse response = queueService.lookupPatient(orgId, phone);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * End a queue session.
     * POST /v1/orgs/:orgId/queues/:queueId/end
     */
    @PostMapping("/queues/{queueId}/end")
    @RequirePermission("queue:end")
    public ResponseEntity<ApiResponse<Map<String, Object>>> endQueue(
            @PathVariable String orgId,
            @PathVariable String queueId,
            @RequestBody @Valid EndQueueRequest request) {

        Map<String, Object> result = queueService.endQueue(orgId, queueId, request);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * Import stashed patients from a previous queue.
     * POST /v1/orgs/:orgId/queues/:queueId/import-stash
     */
    @PostMapping("/queues/{queueId}/import-stash")
    @RequirePermission("queue:import_stash")
    public ResponseEntity<ApiResponse<Map<String, Object>>> importStash(
            @PathVariable String orgId,
            @PathVariable String queueId,
            @RequestBody @Valid ImportStashRequest request) {

        Map<String, Object> result = queueService.importStash(orgId, queueId, request);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * Get complaint tags for the organization.
     * GET /v1/orgs/:orgId/complaint-tags
     */
    @GetMapping("/complaint-tags")
    @RequirePermission("queue:view")
    public ResponseEntity<ApiResponse<ComplaintTagsResponse>> getComplaintTags(
            @PathVariable String orgId) {

        ComplaintTagsResponse response = queueService.getComplaintTags(orgId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
