package com.clinicos.service.service;

import com.clinicos.service.dto.request.AddMemberRequest;
import com.clinicos.service.dto.request.UpdateMemberRequest;
import com.clinicos.service.dto.request.UpdateProfileRequest;
import com.clinicos.service.dto.response.*;
import com.clinicos.service.entity.*;
import com.clinicos.service.exception.BusinessException;
import com.clinicos.service.exception.ResourceNotFoundException;
import com.clinicos.service.repository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class MemberService {

    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final OrgMemberRepository orgMemberRepository;
    private final OrgMemberRoleRepository orgMemberRoleRepository;
    private final RoleRepository roleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final ObjectMapper objectMapper;

    private static final String DOCTOR_ROLE = "doctor";
    private static final String ASSISTANT_ROLE = "assistant";

    @Transactional
    public AddMemberResponse addMember(String orgUuid, AddMemberRequest request) {
        // Get organization
        Organization org = organizationRepository.findByUuid(orgUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", orgUuid));

        // Find or create user
        User user = userRepository.findByPhone(request.getPhone())
                .orElseGet(() -> {
                    User newUser = User.builder()
                            .phone(request.getPhone())
                            .countryCode("+91")
                            .name(request.getName())
                            .build();
                    return userRepository.save(newUser);
                });

        // Block adding a user whose account is scheduled for deletion
        if (user.getDeletedAt() != null) {
            throw new BusinessException("USER_ACCOUNT_DELETED",
                    "This phone number belongs to an account pending deletion");
        }

        // Update name if provided and user had no name
        if (user.getName() == null || user.getName().isEmpty()) {
            user.setName(request.getName());
            userRepository.save(user);
        }

        // Check if already a member
        Optional<OrgMember> existingMember = orgMemberRepository.findByOrganizationIdAndUserId(org.getId(), user.getId());
        if (existingMember.isPresent()) {
            throw new BusinessException("MEMBER_EXISTS", "User is already a member of this organization");
        }

        // Create org member
        OrgMember member = OrgMember.builder()
                .organization(org)
                .user(user)
                .isActive(true)
                .isProfileComplete(!request.getRoles().contains(DOCTOR_ROLE))  // Doctors need to complete profile
                .build();

        // Handle assistant->doctor assignment
        if (request.getRoles().contains(ASSISTANT_ROLE) && request.getAssignedDoctorId() != null) {
            OrgMember doctorMember = findDoctorMember(org.getId(), request.getAssignedDoctorId());
            member.setAssignedDoctor(doctorMember);
        }

        orgMemberRepository.save(member);

        // Assign roles
        List<String> roleNames = request.getRoles();
        List<String> permissions = new ArrayList<>();

        for (String roleName : roleNames) {
            Role role = roleRepository.findByName(roleName)
                    .orElseThrow(() -> new ResourceNotFoundException("Role", roleName));

            OrgMemberRole memberRole = OrgMemberRole.builder()
                    .orgMember(member)
                    .role(role)
                    .build();
            orgMemberRoleRepository.save(memberRole);

            // Collect permissions
            List<RolePermission> rolePerms = rolePermissionRepository.findByRoleIdWithPermissions(role.getId());
            for (RolePermission rp : rolePerms) {
                if (!permissions.contains(rp.getPermission().getName())) {
                    permissions.add(rp.getPermission().getName());
                }
            }
        }

        log.info("Member {} added to organization {} with roles {}", user.getUuid(), orgUuid, roleNames);

        // TODO: Send SMS invite

        OrgMemberResponse memberResponse = buildMemberResponse(member, roleNames, permissions);

        return AddMemberResponse.builder()
                .member(memberResponse)
                .inviteSent(false)  // TODO: Implement SMS
                .build();
    }

    @Transactional(readOnly = true)
    public ListMembersResponse listMembers(String orgUuid) {
        Organization org = organizationRepository.findByUuid(orgUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", orgUuid));

        List<OrgMember> members = orgMemberRepository.findByOrganizationIdWithUser(org.getId());

        List<OrgMemberResponse> memberResponses = members.stream()
                .filter(m -> m.getDeletedAt() == null)
                .map(this::buildMemberResponseWithRoles)
                .collect(Collectors.toList());

        return ListMembersResponse.builder()
                .members(memberResponses)
                .build();
    }

    @Transactional
    public OrgMemberResponse updateMember(String orgUuid, String userUuid, UpdateMemberRequest request) {
        Organization org = organizationRepository.findByUuid(orgUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", orgUuid));

        User user = userRepository.findByUuid(userUuid)
                .orElseThrow(() -> new ResourceNotFoundException("User", userUuid));

        OrgMember member = orgMemberRepository.findByOrganizationIdAndUserId(org.getId(), user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Member", userUuid));

        // Update name
        if (request.getName() != null) {
            user.setName(request.getName());
            userRepository.save(user);
        }

        // Update isActive
        if (request.getIsActive() != null) {
            member.setIsActive(request.getIsActive());
        }

        // Update roles if provided
        if (request.getRoles() != null && !request.getRoles().isEmpty()) {
            Integer memberId = member.getId();

            // Remove existing roles
            orgMemberRoleRepository.deleteByOrgMemberId(memberId);

            // Re-fetch member with assigned doctor after delete (clearAutomatically=true clears persistence context)
            member = orgMemberRepository.findByIdWithAssignedDoctor(memberId)
                    .orElseThrow(() -> new ResourceNotFoundException("Member", userUuid));

            // Add new roles
            boolean hasDoctor = false;
            for (String roleName : request.getRoles()) {
                Role role = roleRepository.findByName(roleName)
                        .orElseThrow(() -> new ResourceNotFoundException("Role", roleName));

                if (DOCTOR_ROLE.equals(roleName)) {
                    hasDoctor = true;
                }

                OrgMemberRole memberRole = OrgMemberRole.builder()
                        .orgMember(member)
                        .role(role)
                        .build();
                orgMemberRoleRepository.save(memberRole);
            }

            // Update profile completeness
            if (hasDoctor && member.getProfileData() == null) {
                member.setIsProfileComplete(false);
            } else if (!hasDoctor) {
                member.setIsProfileComplete(true);
            }
        }

        // Handle assigned doctor (only for assistants)
        if (request.getAssignedDoctorId() != null) {
            // Verify member is an assistant
            List<OrgMemberRole> memberRoles = orgMemberRoleRepository.findByOrgMemberIdWithRoles(member.getId());
            boolean isAssistant = memberRoles.stream()
                    .anyMatch(omr -> ASSISTANT_ROLE.equals(omr.getRole().getName()));

            if (!isAssistant) {
                throw new BusinessException("INVALID_ASSIGNMENT", "Only assistants can be assigned to doctors");
            }

            if (request.getAssignedDoctorId().isEmpty()) {
                // Clear assignment
                member.setAssignedDoctor(null);
            } else {
                OrgMember doctorMember = findDoctorMember(org.getId(), request.getAssignedDoctorId());
                member.setAssignedDoctor(doctorMember);
            }
        }

        orgMemberRepository.save(member);
        log.info("Member {} updated in organization {}", userUuid, orgUuid);

        return buildMemberResponseWithRoles(member);
    }

    @Transactional
    public OrgMemberResponse updateProfile(String orgUuid, String userUuid, UpdateProfileRequest request) {
        Organization org = organizationRepository.findByUuid(orgUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", orgUuid));

        User user = userRepository.findByUuid(userUuid)
                .orElseThrow(() -> new ResourceNotFoundException("User", userUuid));

        OrgMember member = orgMemberRepository.findByOrganizationIdAndUserId(org.getId(), user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Member", userUuid));

        // Store profile data
        member.setProfileData(toJson(request.getProfileData()));
        member.setProfileSchemaVersion(request.getProfileSchemaVersion());

        // Check if profile is complete (for doctors, check required fields)
        List<OrgMemberRole> memberRoles = orgMemberRoleRepository.findByOrgMemberIdWithRoles(member.getId());
        boolean isDoctor = memberRoles.stream()
                .anyMatch(omr -> DOCTOR_ROLE.equals(omr.getRole().getName()));

        if (isDoctor) {
            Map<String, Object> profileData = request.getProfileData();
            boolean hasSpecialization = profileData.containsKey("specialization") && profileData.get("specialization") != null;
            boolean hasRegNumber = profileData.containsKey("registrationNumber") && profileData.get("registrationNumber") != null;
            member.setIsProfileComplete(hasSpecialization && hasRegNumber);
        } else {
            member.setIsProfileComplete(true);
        }

        orgMemberRepository.save(member);
        log.info("Profile updated for member {} in organization {}", userUuid, orgUuid);

        return buildMemberResponseWithRoles(member);
    }

    @Transactional(readOnly = true)
    public OrgMemberResponse getProfile(String orgUuid, String userUuid) {
        Organization org = organizationRepository.findByUuid(orgUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", orgUuid));

        User user = userRepository.findByUuid(userUuid)
                .orElseThrow(() -> new ResourceNotFoundException("User", userUuid));

        OrgMember member = orgMemberRepository.findByOrganizationIdAndUserId(org.getId(), user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Member", userUuid));

        return buildMemberResponseWithRoles(member);
    }

    @Transactional(readOnly = true)
    public DoctorsListResponse getDoctors(String orgUuid) {
        Organization org = organizationRepository.findByUuid(orgUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", orgUuid));

        // Get doctor role
        Role doctorRole = roleRepository.findByName(DOCTOR_ROLE)
                .orElseThrow(() -> new ResourceNotFoundException("Role", DOCTOR_ROLE));

        // Find all members with doctor role
        List<OrgMemberRole> doctorMemberRoles = orgMemberRoleRepository.findByRoleId(doctorRole.getId());

        List<DoctorsListResponse.DoctorInfo> doctors = doctorMemberRoles.stream()
                .map(OrgMemberRole::getOrgMember)
                .filter(m -> m.getOrganization().getId().equals(org.getId()))
                .filter(m -> m.getDeletedAt() == null)
                .map(this::buildDoctorInfo)
                .collect(Collectors.toList());

        return DoctorsListResponse.builder()
                .doctors(doctors)
                .build();
    }

    private OrgMember findDoctorMember(Integer orgId, String doctorUuid) {
        // Find the doctor's user
        User doctorUser = userRepository.findByUuid(doctorUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor", doctorUuid));

        // Find their membership
        OrgMember doctorMember = orgMemberRepository.findByOrganizationIdAndUserId(orgId, doctorUser.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Doctor member", doctorUuid));

        // Verify they have doctor role
        List<OrgMemberRole> roles = orgMemberRoleRepository.findByOrgMemberIdWithRoles(doctorMember.getId());
        boolean isDoctor = roles.stream()
                .anyMatch(omr -> DOCTOR_ROLE.equals(omr.getRole().getName()));

        if (!isDoctor) {
            throw new BusinessException("NOT_A_DOCTOR", "The specified member is not a doctor");
        }

        return doctorMember;
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
                .profileData(fromJson(member.getProfileData()))
                .profileSchemaVersion(member.getProfileSchemaVersion())
                .isProfileComplete(member.getIsProfileComplete())
                .assignedDoctorId(member.getAssignedDoctor() != null ? member.getAssignedDoctor().getUser().getUuid() : null)
                .assignedDoctorName(member.getAssignedDoctor() != null ? member.getAssignedDoctor().getUser().getName() : null)
                .lastActiveAt(member.getLastActiveAt() != null ? member.getLastActiveAt().toString() : null)
                .joinedAt(member.getCreatedAt().toString())
                .build();
    }

    private OrgMemberResponse buildMemberResponseWithRoles(OrgMember member) {
        List<OrgMemberRole> memberRoles = orgMemberRoleRepository.findByOrgMemberIdWithRoles(member.getId());
        List<String> roles = memberRoles.stream()
                .map(omr -> omr.getRole().getName())
                .collect(Collectors.toList());

        List<String> permissions = new ArrayList<>();
        for (OrgMemberRole omr : memberRoles) {
            List<RolePermission> rolePerms = rolePermissionRepository.findByRoleIdWithPermissions(omr.getRole().getId());
            for (RolePermission rp : rolePerms) {
                if (!permissions.contains(rp.getPermission().getName())) {
                    permissions.add(rp.getPermission().getName());
                }
            }
        }

        return buildMemberResponse(member, roles, permissions);
    }

    private DoctorsListResponse.DoctorInfo buildDoctorInfo(OrgMember member) {
        User user = member.getUser();
        Map<String, Object> profileData = fromJson(member.getProfileData());

        String specialization = null;
        Integer consultationFee = null;

        if (profileData != null) {
            specialization = (String) profileData.get("specialization");
            Object fee = profileData.get("consultationFee");
            if (fee instanceof Number) {
                consultationFee = ((Number) fee).intValue();
            }
        }

        return DoctorsListResponse.DoctorInfo.builder()
                .userId(user.getUuid())
                .name(user.getName())
                .profileData(profileData)
                .profileSchemaVersion(member.getProfileSchemaVersion())
                .isProfileComplete(member.getIsProfileComplete())
                .isActive(member.getIsActive())
                .specialization(specialization)
                .consultationFee(consultationFee)
                .build();
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJson(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            log.error("Error parsing JSON", e);
            return null;
        }
    }
}
