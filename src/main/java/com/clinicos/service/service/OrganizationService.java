package com.clinicos.service.service;

import com.clinicos.service.dto.request.CreateOrgRequest;
import com.clinicos.service.dto.response.*;
import com.clinicos.service.entity.*;
import com.clinicos.service.exception.ConflictException;
import com.clinicos.service.exception.ResourceNotFoundException;
import com.clinicos.service.repository.*;
import com.clinicos.service.security.jwt.JwtTokenProvider;
import com.clinicos.service.util.TokenHashUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrganizationService {

    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final OrgMemberRepository orgMemberRepository;
    private final OrgMemberRoleRepository orgMemberRoleRepository;
    private final RoleRepository roleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;

    private static final String ADMIN_ROLE = "admin";

    @Transactional
    public CreateOrgResponse createOrganization(CreateOrgRequest request, Integer userId, String deviceId) {
        // Get user
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId.toString()));

        // Check if user already belongs to an organization
        List<OrgMember> existingMemberships = orgMemberRepository.findByUserId(userId);
        boolean hasActiveOrg = existingMemberships.stream()
                .anyMatch(m -> m.getIsActive() && m.getDeletedAt() == null);

        if (hasActiveOrg) {
            throw new ConflictException("User already belongs to an organization");
        }

        // Update user name if not set
        if (user.getName() == null || user.getName().isEmpty()) {
            user.setName(request.getCreator().getName());
            userRepository.save(user);
        }

        // Build settings JSON
        Map<String, Object> settings = new HashMap<>();
        settings.put("smsLanguage", request.getSmsLanguage());
        settings.put("clinicalDataVisibility", request.getClinicalDataVisibility());

        // Create organization
        Organization org = Organization.builder()
                .name(request.getName())
                .address(request.getAddress())
                .city(request.getCity())
                .state(request.getState())
                .pin(request.getPin())
                .logoUrl(request.getLogo())
                .brandColor(request.getBrandColor())
                .settings(toJson(settings))
                .workingHours(toJson(request.getWorkingHours()))
                .createdBy(user)
                .build();

        organizationRepository.save(org);
        log.info("Organization {} created by user {}", org.getUuid(), user.getUuid());

        // Get admin role
        Role adminRole = roleRepository.findByName(ADMIN_ROLE)
                .orElseThrow(() -> new ResourceNotFoundException("Role", ADMIN_ROLE));

        // Create org member (creator as admin)
        OrgMember member = OrgMember.builder()
                .organization(org)
                .user(user)
                .isActive(true)
                .isProfileComplete(false)  // Profile not filled yet
                .build();

        orgMemberRepository.save(member);

        // Assign admin role to member
        OrgMemberRole memberRole = OrgMemberRole.builder()
                .orgMember(member)
                .role(adminRole)
                .build();

        orgMemberRoleRepository.save(memberRole);

        // Get admin permissions
        List<String> permissions = rolePermissionRepository.findByRoleIdWithPermissions(adminRole.getId())
                .stream()
                .map(rp -> rp.getPermission().getName())
                .collect(Collectors.toList());

        // Generate new tokens with org context
        String accessToken = jwtTokenProvider.generateAccessToken(userId, user.getUuid(), deviceId);
        String refreshToken = jwtTokenProvider.generateRefreshToken(userId, user.getUuid(), deviceId);

        // Revoke old refresh tokens and save new one
        refreshTokenRepository.revokeAllByDeviceId(deviceId);

        RefreshToken refreshTokenEntity = RefreshToken.builder()
                .user(user)
                .deviceId(deviceId)
                .tokenHash(TokenHashUtil.hash(refreshToken))
                .expiresAt(Instant.now().plusMillis(jwtTokenProvider.getRefreshTokenExpiration()))
                .isRevoked(false)
                .build();

        refreshTokenRepository.save(refreshTokenEntity);

        // Build response
        OrganizationResponse orgResponse = buildOrganizationResponse(org);
        OrgMemberResponse memberResponse = buildMemberResponse(member, List.of(ADMIN_ROLE), permissions);

        return CreateOrgResponse.builder()
                .org(orgResponse)
                .member(memberResponse)
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .build();
    }

    @Transactional(readOnly = true)
    public OrganizationResponse getOrganization(String orgUuid) {
        Organization org = organizationRepository.findByUuid(orgUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", orgUuid));

        return buildOrganizationResponse(org);
    }

    @Transactional
    public OrganizationResponse updateOrganization(String orgUuid, Map<String, Object> updates) {
        Organization org = organizationRepository.findByUuid(orgUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", orgUuid));

        // Apply updates
        if (updates.containsKey("name")) {
            org.setName((String) updates.get("name"));
        }
        if (updates.containsKey("address")) {
            org.setAddress((String) updates.get("address"));
        }
        if (updates.containsKey("city")) {
            org.setCity((String) updates.get("city"));
        }
        if (updates.containsKey("state")) {
            org.setState((String) updates.get("state"));
        }
        if (updates.containsKey("pin")) {
            org.setPin((String) updates.get("pin"));
        }
        if (updates.containsKey("logo")) {
            org.setLogoUrl((String) updates.get("logo"));
        }
        if (updates.containsKey("brandColor")) {
            org.setBrandColor((String) updates.get("brandColor"));
        }
        if (updates.containsKey("workingHours")) {
            org.setWorkingHours(toJson(updates.get("workingHours")));
        }

        // Update settings
        Map<String, Object> settings = fromJson(org.getSettings(), Map.class);
        if (settings == null) {
            settings = new HashMap<>();
        }
        if (updates.containsKey("smsLanguage")) {
            settings.put("smsLanguage", updates.get("smsLanguage"));
        }
        if (updates.containsKey("clinicalDataVisibility")) {
            settings.put("clinicalDataVisibility", updates.get("clinicalDataVisibility"));
        }
        org.setSettings(toJson(settings));

        organizationRepository.save(org);
        log.info("Organization {} updated", org.getUuid());

        return buildOrganizationResponse(org);
    }

    private OrganizationResponse buildOrganizationResponse(Organization org) {
        Map<String, Object> settings = fromJson(org.getSettings(), Map.class);
        CreateOrgRequest.WorkingHours workingHours = fromJson(org.getWorkingHours(), CreateOrgRequest.WorkingHours.class);

        // Compute isOpenToday and currentShift
        DayOfWeek today = LocalDate.now().getDayOfWeek();
        CreateOrgRequest.DaySchedule todaySchedule = getDaySchedule(workingHours, today);

        boolean isOpenToday = todaySchedule != null && todaySchedule.getShifts() != null && !todaySchedule.getShifts().isEmpty();
        CreateOrgRequest.Shift currentShift = null;

        if (isOpenToday) {
            LocalTime now = LocalTime.now();
            for (CreateOrgRequest.Shift shift : todaySchedule.getShifts()) {
                LocalTime open = LocalTime.parse(shift.getOpen());
                LocalTime close = LocalTime.parse(shift.getClose());
                if (!now.isBefore(open) && now.isBefore(close)) {
                    currentShift = shift;
                    break;
                }
            }
        }

        // Compute next working day
        String nextWorkingDay = computeNextWorkingDay(workingHours);

        return OrganizationResponse.builder()
                .orgId(org.getUuid())
                .name(org.getName())
                .logo(org.getLogoUrl())
                .brandColor(org.getBrandColor())
                .address(org.getAddress())
                .city(org.getCity())
                .state(org.getState())
                .pin(org.getPin())
                .smsLanguage(settings != null ? (String) settings.get("smsLanguage") : "hi")
                .clinicalDataVisibility(settings != null ? (String) settings.get("clinicalDataVisibility") : "all_members")
                .workingHours(workingHours)
                .createdAt(org.getCreatedAt().toString())
                .isOpenToday(isOpenToday)
                .currentShift(currentShift)
                .nextWorkingDay(nextWorkingDay)
                .build();
    }

    private OrgMemberResponse buildMemberResponse(OrgMember member, List<String> roles, List<String> permissions) {
        User user = member.getUser();

        return OrgMemberResponse.builder()
                .userId(user.getUuid())
                .phone(user.getPhone())
                .name(user.getName())
                .roles(roles)
                .permissions(permissions)
                .isActive(member.getIsActive())
                .profileData(fromJson(member.getProfileData(), Map.class))
                .profileSchemaVersion(member.getProfileSchemaVersion())
                .isProfileComplete(member.getIsProfileComplete())
                .assignedDoctorId(member.getAssignedDoctor() != null ? member.getAssignedDoctor().getUuid() : null)
                .assignedDoctorName(member.getAssignedDoctor() != null ? member.getAssignedDoctor().getUser().getName() : null)
                .lastActiveAt(member.getLastActiveAt() != null ? member.getLastActiveAt().toString() : null)
                .joinedAt(member.getCreatedAt().toString())
                .build();
    }

    private CreateOrgRequest.DaySchedule getDaySchedule(CreateOrgRequest.WorkingHours hours, DayOfWeek day) {
        if (hours == null) return null;
        return switch (day) {
            case MONDAY -> hours.getMonday();
            case TUESDAY -> hours.getTuesday();
            case WEDNESDAY -> hours.getWednesday();
            case THURSDAY -> hours.getThursday();
            case FRIDAY -> hours.getFriday();
            case SATURDAY -> hours.getSaturday();
            case SUNDAY -> hours.getSunday();
        };
    }

    private String computeNextWorkingDay(CreateOrgRequest.WorkingHours hours) {
        if (hours == null) return null;

        LocalDate date = LocalDate.now();
        for (int i = 0; i < 7; i++) {
            date = date.plusDays(1);
            CreateOrgRequest.DaySchedule schedule = getDaySchedule(hours, date.getDayOfWeek());
            if (schedule != null && schedule.getShifts() != null && !schedule.getShifts().isEmpty()) {
                return date.format(DateTimeFormatter.ISO_LOCAL_DATE);
            }
        }
        return null;
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("Error converting to JSON", e);
            return null;
        }
    }

    private <T> T fromJson(String json, Class<T> clazz) {
        if (json == null || json.isEmpty()) return null;
        try {
            return objectMapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            log.error("Error parsing JSON", e);
            return null;
        }
    }
}
