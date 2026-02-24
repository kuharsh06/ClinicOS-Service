package com.clinicos.service.service;

import com.clinicos.service.dto.request.RefreshTokenRequest;
import com.clinicos.service.dto.request.SendOtpRequest;
import com.clinicos.service.dto.request.VerifyOtpRequest;
import com.clinicos.service.dto.response.AuthResponse;
import com.clinicos.service.dto.response.OtpResponse;
import com.clinicos.service.entity.*;
import com.clinicos.service.enums.Platform;
import com.clinicos.service.exception.BusinessException;
import com.clinicos.service.repository.*;
import com.clinicos.service.security.jwt.JwtTokenProvider;
import com.clinicos.service.util.TokenHashUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final OtpRequestRepository otpRequestRepository;
    private final DeviceRepository deviceRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final OrgMemberRepository orgMemberRepository;
    private final OrgMemberRoleRepository orgMemberRoleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final OtpAttemptService otpAttemptService;

    private static final int OTP_LENGTH = 6;
    private static final int OTP_EXPIRY_SECONDS = 300;  // 5 minutes
    private static final int OTP_RETRY_AFTER_SECONDS = 30;
    private static final int MAX_OTP_ATTEMPTS = 3;

    @Transactional
    public OtpResponse sendOtp(SendOtpRequest request) {
        String phone = request.getPhone();
        String countryCode = request.getCountryCode();

        // Generate OTP
        String otp = generateOtp();
        String otpHash = passwordEncoder.encode(otp);

        // Create OTP request
        OtpRequest otpRequest = OtpRequest.builder()
                .phone(phone)
                .countryCode(countryCode)
                .otpHash(otpHash)
                .expiresAt(Instant.now().plusSeconds(OTP_EXPIRY_SECONDS))
                .verifyAttempts(0)
                .isVerified(false)
                .build();

        otpRequestRepository.save(otpRequest);

        log.info("OTP sent to {}{}", countryCode, phone);

        // TODO: Integrate with SMS provider to send actual OTP

        return OtpResponse.builder()
                .requestId(otpRequest.getUuid())
                .expiresInSeconds(OTP_EXPIRY_SECONDS)
                .retryAfterSeconds(OTP_RETRY_AFTER_SECONDS)
                .devOtp(otp) // Remove in production
                .build();
    }

    @Transactional
    public AuthResponse verifyOtp(VerifyOtpRequest request) {
        String requestId = request.getRequestId();

        // Find OTP request by UUID
        OtpRequest otpRequest = otpRequestRepository
                .findByUuid(requestId)
                .orElseThrow(() -> new BusinessException("OTP_REQUEST_NOT_FOUND", "No OTP request found"));

        // Check if already verified
        if (otpRequest.getIsVerified()) {
            throw new BusinessException("OTP_ALREADY_VERIFIED", "OTP already verified");
        }

        // Check expiry
        if (otpRequest.getExpiresAt().isBefore(Instant.now())) {
            throw new BusinessException("OTP_EXPIRED", "OTP has expired");
        }

        // Check attempts
        if (otpRequest.getVerifyAttempts() >= MAX_OTP_ATTEMPTS) {
            throw new BusinessException("OTP_MAX_ATTEMPTS", "Maximum OTP attempts exceeded");
        }

        // Verify OTP
        if (!passwordEncoder.matches(request.getOtp(), otpRequest.getOtpHash())) {
            // Increment failed attempts in separate transaction (persists even on rollback)
            otpAttemptService.incrementVerifyAttempts(otpRequest.getId());
            // retryable: true - user can try entering OTP again
            throw new BusinessException("OTP_INVALID", "Invalid OTP", true);
        }

        // Mark OTP as verified
        otpRequest.setIsVerified(true);
        otpRequestRepository.save(otpRequest);

        // Get phone from OTP request
        String phone = otpRequest.getPhone();
        String countryCode = otpRequest.getCountryCode();

        // Find or create user
        boolean isNewUser = false;
        User user = userRepository.findByCountryCodeAndPhone(countryCode, phone)
                .orElseGet(() -> {
                    User newUser = User.builder()
                            .phone(phone)
                            .countryCode(countryCode)
                            .build();
                    return userRepository.save(newUser);
                });

        if (user.getName() == null) {
            isNewUser = true;
        }

        // Create or update device
        String deviceId = request.getDeviceId();
        VerifyOtpRequest.DeviceInfo deviceInfo = request.getDeviceInfo();

        Device device = deviceRepository.findByDeviceId(deviceId)
                .orElseGet(() -> Device.builder()
                        .user(user)
                        .deviceId(deviceId)
                        .platform(parsePlatform(deviceInfo.getPlatform()))
                        .build());

        device.setUser(user);
        device.setPlatform(parsePlatform(deviceInfo.getPlatform()));
        device.setOsVersion(deviceInfo.getOsVersion());
        device.setAppVersion(deviceInfo.getAppVersion());
        device.setDeviceModel(deviceInfo.getDeviceModel());
        device.setLastActiveAt(Instant.now());
        deviceRepository.save(device);

        // Generate tokens
        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getUuid(), deviceId);
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId(), user.getUuid(), deviceId);

        // Save refresh token (SHA-256 hash - BCrypt not needed for high-entropy JWTs)
        RefreshToken refreshTokenEntity = RefreshToken.builder()
                .user(user)
                .deviceId(deviceId)
                .tokenHash(TokenHashUtil.hash(refreshToken))
                .expiresAt(Instant.now().plusMillis(jwtTokenProvider.getRefreshTokenExpiration()))
                .isRevoked(false)
                .build();

        refreshTokenRepository.save(refreshTokenEntity);

        log.info("User {} logged in from device {}", user.getUuid(), deviceId);

        // Build AuthUser with org context
        AuthResponse.AuthUser authUser = buildAuthUser(user);

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .expiresAt(System.currentTimeMillis() + jwtTokenProvider.getAccessTokenExpiration())
                .user(authUser)
                .isNewUser(isNewUser)
                .build();
    }

    @Transactional
    public AuthResponse refreshToken(RefreshTokenRequest request) {
        String refreshToken = request.getRefreshToken();
        String deviceId = request.getDeviceId();

        // Validate token
        if (!jwtTokenProvider.validateToken(refreshToken)) {
            throw new BusinessException("TOKEN_INVALID", "Invalid refresh token");
        }

        if (!jwtTokenProvider.isRefreshToken(refreshToken)) {
            throw new BusinessException("TOKEN_INVALID", "Not a refresh token");
        }

        // Verify device ID matches
        String tokenDeviceId = jwtTokenProvider.getDeviceId(refreshToken);
        if (!deviceId.equals(tokenDeviceId)) {
            throw new BusinessException("DEVICE_MISMATCH", "Device ID mismatch");
        }

        // Check if token is revoked in database
        String tokenHash = TokenHashUtil.hash(refreshToken);
        RefreshToken storedToken = refreshTokenRepository.findByTokenHashAndIsRevokedFalse(tokenHash)
                .orElseThrow(() -> new BusinessException("TOKEN_REVOKED", "Refresh token has been revoked"));

        // Get user from stored token (more secure than parsing from JWT)
        User user = storedToken.getUser();
        Integer userId = user.getId();
        String userUuid = user.getUuid();

        // Revoke old refresh tokens for this device
        refreshTokenRepository.revokeAllByDeviceId(deviceId);

        // Generate new tokens
        String newAccessToken = jwtTokenProvider.generateAccessToken(userId, userUuid, deviceId);
        String newRefreshToken = jwtTokenProvider.generateRefreshToken(userId, userUuid, deviceId);

        // Save new refresh token (SHA-256 hash - BCrypt not needed for high-entropy JWTs)
        RefreshToken refreshTokenEntity = RefreshToken.builder()
                .user(user)
                .deviceId(deviceId)
                .tokenHash(TokenHashUtil.hash(newRefreshToken))
                .expiresAt(Instant.now().plusMillis(jwtTokenProvider.getRefreshTokenExpiration()))
                .isRevoked(false)
                .build();

        refreshTokenRepository.save(refreshTokenEntity);

        // Build AuthUser with latest org context (roles/permissions may have changed)
        AuthResponse.AuthUser authUser = buildAuthUser(user);

        return AuthResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .expiresAt(System.currentTimeMillis() + jwtTokenProvider.getAccessTokenExpiration())
                .user(authUser)
                .build();
    }

    @Transactional
    public void logout(String deviceId, Integer userId) {
        refreshTokenRepository.revokeAllByDeviceId(deviceId);
        log.info("User {} logged out from device {}", userId, deviceId);
    }

    /**
     * Build AuthUser with org context, roles, and permissions
     */
    private AuthResponse.AuthUser buildAuthUser(User user) {
        // Find user's org membership (first active one for now)
        List<OrgMember> memberships = orgMemberRepository.findByUserId(user.getId());
        OrgMember activeMembership = memberships.stream()
                .filter(m -> m.getIsActive() && m.getDeletedAt() == null)
                .findFirst()
                .orElse(null);

        String orgId = null;
        List<String> roles = new ArrayList<>();
        List<String> permissions = new ArrayList<>();
        Boolean isProfileComplete = false;
        String assignedDoctorId = null;
        String assignedDoctorName = null;

        if (activeMembership != null) {
            orgId = activeMembership.getOrganization().getUuid();
            isProfileComplete = activeMembership.getIsProfileComplete();

            // Get assigned doctor for assistants
            if (activeMembership.getAssignedDoctor() != null) {
                assignedDoctorId = activeMembership.getAssignedDoctor().getUuid();
                // Get doctor's name from user
                assignedDoctorName = activeMembership.getAssignedDoctor().getUser().getName();
            }

            // Get roles
            List<OrgMemberRole> memberRoles = orgMemberRoleRepository.findByOrgMemberIdWithRoles(activeMembership.getId());
            roles = memberRoles.stream()
                    .map(omr -> omr.getRole().getName())
                    .collect(Collectors.toList());

            // Get permissions (union of all role permissions)
            for (OrgMemberRole omr : memberRoles) {
                List<RolePermission> rolePerms = rolePermissionRepository.findByRoleIdWithPermissions(omr.getRole().getId());
                for (RolePermission rp : rolePerms) {
                    String permName = rp.getPermission().getName();
                    if (!permissions.contains(permName)) {
                        permissions.add(permName);
                    }
                }
            }
        }

        return AuthResponse.AuthUser.builder()
                .userId(user.getUuid())
                .phone(user.getPhone())
                .name(user.getName())
                .orgId(orgId)
                .roles(roles)
                .permissions(permissions)
                .isProfileComplete(isProfileComplete)
                .assignedDoctorId(assignedDoctorId)
                .assignedDoctorName(assignedDoctorName)
                .build();
    }

    private String generateOtp() {
        // TODO: Restore random OTP generation when SMS service is live
        // SecureRandom random = new SecureRandom();
        // StringBuilder otp = new StringBuilder();
        // for (int i = 0; i < OTP_LENGTH; i++) {
        //     otp.append(random.nextInt(10));
        // }
        // return otp.toString();
        return "123456".toString();
    }

    private Platform parsePlatform(String platform) {
        if (platform == null) {
            return Platform.ANDROID;
        }
        try {
            return Platform.fromValue(platform.toLowerCase());
        } catch (IllegalArgumentException e) {
            return Platform.ANDROID;
        }
    }

}
