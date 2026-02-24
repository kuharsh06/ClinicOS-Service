package com.clinicos.service.controller;

import com.clinicos.service.dto.request.RefreshTokenRequest;
import com.clinicos.service.dto.request.SendOtpRequest;
import com.clinicos.service.dto.request.VerifyOtpRequest;
import com.clinicos.service.dto.response.ApiResponse;
import com.clinicos.service.dto.response.AuthResponse;
import com.clinicos.service.dto.response.OtpResponse;
import com.clinicos.service.security.CustomUserDetails;
import com.clinicos.service.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "OTP-based authentication endpoints")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/otp/send")
    @Operation(summary = "Send OTP", description = "Send OTP to the provided phone number")
    public ResponseEntity<ApiResponse<OtpResponse>> sendOtp(@Valid @RequestBody SendOtpRequest request) {
        OtpResponse response = authService.sendOtp(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @PostMapping("/otp/verify")
    @Operation(summary = "Verify OTP", description = "Verify OTP and get authentication tokens")
    public ResponseEntity<ApiResponse<AuthResponse>> verifyOtp(@Valid @RequestBody VerifyOtpRequest request) {
        AuthResponse response = authService.verifyOtp(request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/token/refresh")
    @Operation(summary = "Refresh Token", description = "Get new access token using refresh token")
    public ResponseEntity<ApiResponse<AuthResponse>> refreshToken(@Valid @RequestBody RefreshTokenRequest request) {
        AuthResponse response = authService.refreshToken(request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/logout")
    @Operation(summary = "Logout", description = "Logout and revoke refresh tokens")
    public ResponseEntity<ApiResponse<Void>> logout(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestHeader("X-Device-Id") String deviceId) {
        authService.logout(deviceId, userDetails.getId());
        return ResponseEntity.ok(ApiResponse.<Void>success(null));
    }
}
