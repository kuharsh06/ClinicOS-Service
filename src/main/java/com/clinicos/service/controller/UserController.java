package com.clinicos.service.controller;

import com.clinicos.service.dto.response.AccountDeletionResponse;
import com.clinicos.service.dto.response.ApiResponse;
import com.clinicos.service.security.CustomUserDetails;
import com.clinicos.service.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/users")
@RequiredArgsConstructor
@Tag(name = "User", description = "User account management")
public class UserController {

    private final UserService userService;

    @DeleteMapping("/me")
    @Operation(summary = "Delete Account",
            description = "Delete the authenticated user's account. "
                    + "Account is deactivated immediately and permanently deleted after 30 days. "
                    + "Medical records are retained per regulatory requirements.")
    public ResponseEntity<ApiResponse<AccountDeletionResponse>> deleteAccount(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        AccountDeletionResponse response = userService.deleteAccount(userDetails.getId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
