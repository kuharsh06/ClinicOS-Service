package com.clinicos.service.controller;

import com.clinicos.service.dto.response.ApiResponse;
import com.clinicos.service.dto.response.DoctorsListResponse;
import com.clinicos.service.security.RequirePermission;
import com.clinicos.service.service.MemberService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/orgs/{orgId}/doctors")
@RequiredArgsConstructor
@Tag(name = "Doctors", description = "Doctor listing endpoints")
public class DoctorController {

    private final MemberService memberService;

    @GetMapping
    @Operation(summary = "Get Doctors", description = "Get all doctors in the organization")
    @RequirePermission("queue:view")
    public ResponseEntity<ApiResponse<DoctorsListResponse>> getDoctors(
            @PathVariable String orgId) {

        DoctorsListResponse response = memberService.getDoctors(orgId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
