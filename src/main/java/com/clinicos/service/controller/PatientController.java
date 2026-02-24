package com.clinicos.service.controller;

import com.clinicos.service.dto.request.CreateVisitRequest;
import com.clinicos.service.dto.response.ApiResponse;
import com.clinicos.service.dto.response.PatientListResponse;
import com.clinicos.service.dto.response.PatientSearchResponse;
import com.clinicos.service.dto.response.PatientThreadResponse;
import com.clinicos.service.security.CustomUserDetails;
import com.clinicos.service.security.RequirePermission;
import com.clinicos.service.service.PatientService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/orgs/{orgId}")
@RequiredArgsConstructor
public class PatientController {

    private final PatientService patientService;

    /**
     * Get paginated list of patients.
     * GET /v1/orgs/:orgId/patients?after=<cursor>&limit=20&search=<query>&sort=<field>
     */
    @GetMapping("/patients")
    @RequirePermission("patient:view")
    public ResponseEntity<ApiResponse<PatientListResponse>> listPatients(
            @PathVariable String orgId,
            @RequestParam(required = false) String after,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String sort) {

        PatientListResponse response = patientService.listPatients(orgId, after, limit, search, sort);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Search patients for autocomplete.
     * GET /v1/orgs/:orgId/patients/search?q=<query>&limit=5
     */
    @GetMapping("/patients/search")
    @RequirePermission("patient:view")
    public ResponseEntity<ApiResponse<PatientSearchResponse>> searchPatients(
            @PathVariable String orgId,
            @RequestParam String q,
            @RequestParam(required = false) Integer limit) {

        PatientSearchResponse response = patientService.searchPatients(orgId, q, limit);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Get patient visit history (thread).
     * GET /v1/orgs/:orgId/patients/:patientId/thread?after=<cursor>&limit=10
     */
    @GetMapping("/patients/{patientId}/thread")
    @RequirePermission("patient:view")
    public ResponseEntity<ApiResponse<PatientThreadResponse>> getPatientThread(
            @PathVariable String orgId,
            @PathVariable String patientId,
            @RequestParam(required = false) String after,
            @RequestParam(required = false) Integer limit) {

        PatientThreadResponse response = patientService.getPatientThread(orgId, patientId, after, limit);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Create a new visit for a patient.
     * POST /v1/orgs/:orgId/patients/:patientId/visits
     */
    @PostMapping("/patients/{patientId}/visits")
    @RequirePermission("patient:add_notes")
    public ResponseEntity<ApiResponse<PatientThreadResponse.VisitDto>> createVisit(
            @PathVariable String orgId,
            @PathVariable String patientId,
            @RequestBody @Valid CreateVisitRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        PatientThreadResponse.VisitDto visit = patientService.createVisit(
                orgId, patientId, request, userDetails.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(visit));
    }

    /**
     * Update an existing visit.
     * PUT /v1/orgs/:orgId/patients/:patientId/visits/:visitId
     */
    @PutMapping("/patients/{patientId}/visits/{visitId}")
    @RequirePermission("patient:add_notes")
    public ResponseEntity<ApiResponse<PatientThreadResponse.VisitDto>> updateVisit(
            @PathVariable String orgId,
            @PathVariable String patientId,
            @PathVariable String visitId,
            @RequestBody @Valid CreateVisitRequest request) {

        PatientThreadResponse.VisitDto visit = patientService.updateVisit(orgId, patientId, visitId, request);
        return ResponseEntity.ok(ApiResponse.success(visit));
    }
}
