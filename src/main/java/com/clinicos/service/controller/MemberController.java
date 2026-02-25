package com.clinicos.service.controller;

import com.clinicos.service.dto.request.AddMemberRequest;
import com.clinicos.service.dto.request.UpdateMemberRequest;
import com.clinicos.service.dto.request.UpdateProfileRequest;
import com.clinicos.service.dto.response.*;
import com.clinicos.service.security.RequirePermission;
import com.clinicos.service.service.MemberService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/orgs/{orgId}/members")
@RequiredArgsConstructor
@Tag(name = "Members", description = "Organization member management endpoints")
public class MemberController {

    private final MemberService memberService;

    @PostMapping
    @Operation(summary = "Add Member", description = "Add a new member to the organization")
    @RequirePermission("members:add")
    public ResponseEntity<ApiResponse<AddMemberResponse>> addMember(
            @PathVariable String orgId,
            @Valid @RequestBody AddMemberRequest request) {

        AddMemberResponse response = memberService.addMember(orgId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @GetMapping
    @Operation(summary = "List Members", description = "List all members of the organization")
    @RequirePermission("members:view")
    public ResponseEntity<ApiResponse<ListMembersResponse>> listMembers(
            @PathVariable String orgId) {

        ListMembersResponse response = memberService.listMembers(orgId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PutMapping("/{userId}")
    @Operation(summary = "Update Member", description = "Update member roles and status")
    @RequirePermission("members:update")
    public ResponseEntity<ApiResponse<OrgMemberResponse>> updateMember(
            @PathVariable String orgId,
            @PathVariable String userId,
            @Valid @RequestBody UpdateMemberRequest request) {

        OrgMemberResponse response = memberService.updateMember(orgId, userId, request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PutMapping("/{userId}/profile")
    @Operation(summary = "Update Profile", description = "Update member profile data")
    public ResponseEntity<ApiResponse<OrgMemberResponse>> updateProfile(
            @PathVariable String orgId,
            @PathVariable String userId,
            @Valid @RequestBody UpdateProfileRequest request) {
        // Note: Permission check is handled in service - self or settings:manage_team

        OrgMemberResponse response = memberService.updateProfile(orgId, userId, request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
