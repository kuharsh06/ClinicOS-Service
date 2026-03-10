package com.clinicos.service.service;

import com.clinicos.service.config.AppMetrics;
import com.clinicos.service.dto.response.AccountDeletionResponse;
import com.clinicos.service.entity.*;
import com.clinicos.service.enums.QueueStatus;
import com.clinicos.service.exception.BusinessException;
import com.clinicos.service.exception.ResourceNotFoundException;
import com.clinicos.service.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private static final int GRACE_PERIOD_DAYS = 30;

    private final UserRepository userRepository;
    private final OrgMemberRepository orgMemberRepository;
    private final OrgMemberRoleRepository orgMemberRoleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final DeviceRepository deviceRepository;
    private final QueueRepository queueRepository;
    private final OrganizationRepository organizationRepository;
    private final AppMetrics appMetrics;

    @Transactional
    public AccountDeletionResponse deleteAccount(Integer userId) {
        // 1. Load user
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId.toString()));

        // Guard: prevent double deletion
        if (user.getDeletedAt() != null) {
            throw new BusinessException("ACCOUNT_ALREADY_DELETED",
                    "Account is already scheduled for deletion");
        }

        // 2. Load all active org memberships
        List<OrgMember> memberships = orgMemberRepository.findByUserId(userId);
        List<OrgMember> activeMemberships = memberships.stream()
                .filter(m -> m.getIsActive() && m.getDeletedAt() == null)
                .toList();

        // 3. Validate preconditions per org
        for (OrgMember membership : activeMemberships) {
            validateCanDeleteFromOrg(membership);
        }

        Instant now = Instant.now();
        Instant scheduledPermanentDeletionAt = now.plus(GRACE_PERIOD_DAYS, ChronoUnit.DAYS);

        // 4. Revoke all refresh tokens
        refreshTokenRepository.revokeAllByUserId(userId);

        // 5. Soft-delete all devices
        List<Device> devices = deviceRepository.findByUserId(userId);
        for (Device device : devices) {
            device.setDeletedAt(now);
        }
        if (!devices.isEmpty()) {
            deviceRepository.saveAll(devices);
        }

        // 6. Deactivate all memberships and handle orphaned orgs
        for (OrgMember membership : activeMemberships) {
            deactivateMembership(membership, now);
            handleOrphanedOrg(membership.getOrganization(), now);
        }

        // 7. Soft-delete user (PII stays during grace period)
        user.setDeletedAt(now);
        user.setScheduledPermanentDeletionAt(scheduledPermanentDeletionAt);
        userRepository.save(user);

        // 8. Record metrics
        appMetrics.recordAuth("account_delete", "success");

        log.info("Account deletion scheduled for user {} — permanent deletion at {}",
                user.getUuid(), scheduledPermanentDeletionAt);

        return AccountDeletionResponse.builder()
                .scheduledPermanentDeletionAt(scheduledPermanentDeletionAt)
                .build();
    }

    private void validateCanDeleteFromOrg(OrgMember membership) {
        Integer orgId = membership.getOrganization().getId();

        // Check for active/paused queues (only relevant if user is a doctor)
        List<Queue> activeQueues = queueRepository.findByDoctorIdAndStatusIn(
                membership.getId(),
                List.of(QueueStatus.ACTIVE, QueueStatus.PAUSED));
        if (!activeQueues.isEmpty()) {
            throw new BusinessException("ACTIVE_QUEUE_EXISTS",
                    "Please end all active queues before deleting your account");
        }

        // Check if sole admin
        List<OrgMemberRole> memberRoles = orgMemberRoleRepository
                .findByOrgMemberIdWithRoles(membership.getId());
        boolean isAdmin = memberRoles.stream()
                .anyMatch(omr -> "admin".equals(omr.getRole().getName())
                        || "owner".equals(omr.getRole().getName()));

        if (isAdmin) {
            List<OrgMember> allActiveMembers = orgMemberRepository.findActiveMembers(orgId);

            boolean otherAdminExists = allActiveMembers.stream()
                    .filter(m -> !m.getId().equals(membership.getId()))
                    .anyMatch(m -> {
                        List<OrgMemberRole> roles = orgMemberRoleRepository
                                .findByOrgMemberIdWithRoles(m.getId());
                        return roles.stream().anyMatch(omr ->
                                "admin".equals(omr.getRole().getName())
                                        || "owner".equals(omr.getRole().getName()));
                    });

            boolean otherMembersExist = allActiveMembers.stream()
                    .anyMatch(m -> !m.getId().equals(membership.getId()));

            if (!otherAdminExists && otherMembersExist) {
                throw new BusinessException("SOLE_ADMIN_CANNOT_DELETE",
                        "You are the sole administrator of organization '"
                                + membership.getOrganization().getName()
                                + "'. Please assign another admin before deleting your account.");
            }
        }
    }

    private void deactivateMembership(OrgMember membership, Instant now) {
        membership.setIsActive(false);
        membership.setLeftAt(now);
        membership.setDeletedAt(now);
        orgMemberRepository.save(membership);

        // Clear assignedDoctor on any assistants assigned to this member
        List<OrgMember> assignedAssistants = orgMemberRepository.findByAssignedDoctorId(membership.getId());
        for (OrgMember assistant : assignedAssistants) {
            assistant.setAssignedDoctor(null);
        }
        if (!assignedAssistants.isEmpty()) {
            orgMemberRepository.saveAll(assignedAssistants);
            log.info("Cleared assignedDoctor for {} assistant(s) of member {}", assignedAssistants.size(), membership.getId());
        }

        List<OrgMemberRole> roles = orgMemberRoleRepository.findByOrgMemberId(membership.getId());
        for (OrgMemberRole role : roles) {
            role.setDeletedAt(now);
        }
        if (!roles.isEmpty()) {
            orgMemberRoleRepository.saveAll(roles);
        }
    }

    private void handleOrphanedOrg(Organization org, Instant now) {
        List<OrgMember> remainingActive = orgMemberRepository.findActiveMembers(org.getId());
        if (remainingActive.isEmpty()) {
            org.setDeletedAt(now);
            organizationRepository.save(org);
            log.info("Organization {} soft-deleted (no remaining members)", org.getUuid());
        }
    }
}
