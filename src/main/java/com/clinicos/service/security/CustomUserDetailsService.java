package com.clinicos.service.security;

import com.clinicos.service.entity.*;
import com.clinicos.service.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;
    private final OrgMemberRepository orgMemberRepository;
    private final OrgMemberRoleRepository orgMemberRoleRepository;
    private final RolePermissionRepository rolePermissionRepository;

    @Override
    public UserDetails loadUserByUsername(String phone) throws UsernameNotFoundException {
        User user = userRepository.findByPhone(phone)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with phone: " + phone));

        return buildUserDetails(user, null);
    }

    @Transactional(readOnly = true)
    public UserDetails loadUserById(Integer userId, String deviceId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with id: " + userId));

        return buildUserDetails(user, deviceId);
    }

    @Transactional(readOnly = true)
    public UserDetails loadUserByUuid(String uuid, String deviceId) {
        User user = userRepository.findByUuid(uuid)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with uuid: " + uuid));

        return buildUserDetails(user, deviceId);
    }

    /**
     * Build CustomUserDetails with full org context, roles, and permissions
     */
    private CustomUserDetails buildUserDetails(User user, String deviceId) {
        // Find user's active org membership
        List<OrgMember> memberships = orgMemberRepository.findByUserId(user.getId());
        OrgMember activeMembership = memberships.stream()
                .filter(m -> m.getIsActive() && m.getDeletedAt() == null)
                .findFirst()
                .orElse(null);

        if (activeMembership == null) {
            // User has no org - return basic details
            return new CustomUserDetails(user, deviceId);
        }

        // Get org info
        Integer orgId = activeMembership.getOrganization().getId();
        String orgUuid = activeMembership.getOrganization().getUuid();

        // Get roles
        List<OrgMemberRole> memberRoles = orgMemberRoleRepository
                .findByOrgMemberIdWithRoles(activeMembership.getId());
        List<String> roles = memberRoles.stream()
                .map(omr -> omr.getRole().getName())
                .collect(Collectors.toList());

        // Get permissions (union of all role permissions)
        Set<String> permissions = new HashSet<>();
        for (OrgMemberRole omr : memberRoles) {
            List<RolePermission> rolePerms = rolePermissionRepository
                    .findByRoleIdWithPermissions(omr.getRole().getId());
            for (RolePermission rp : rolePerms) {
                permissions.add(rp.getPermission().getName());
            }
        }

        return new CustomUserDetails(user, deviceId, orgId, orgUuid, roles, permissions);
    }
}
