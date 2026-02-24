package com.clinicos.service.security;

import com.clinicos.service.entity.User;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.*;
import java.util.stream.Collectors;

@Getter
public class CustomUserDetails implements UserDetails {

    private final Integer id;
    private final String uuid;
    private final String phone;
    private final String countryCode;
    private final String name;
    private final String deviceId;

    // Org context
    private final Integer orgId;
    private final String orgUuid;
    private final List<String> roles;
    private final Set<String> permissions;

    public CustomUserDetails(User user, String deviceId) {
        this(user, deviceId, null, null, Collections.emptyList(), Collections.emptySet());
    }

    public CustomUserDetails(User user, String deviceId, Integer orgId, String orgUuid,
                             List<String> roles, Set<String> permissions) {
        this.id = user.getId();
        this.uuid = user.getUuid();
        this.phone = user.getPhone();
        this.countryCode = user.getCountryCode();
        this.name = user.getName();
        this.deviceId = deviceId;
        this.orgId = orgId;
        this.orgUuid = orgUuid;
        this.roles = roles != null ? roles : Collections.emptyList();
        this.permissions = permissions != null ? permissions : Collections.emptySet();
    }

    /**
     * Get user ID (alias for getId)
     */
    public Integer getUserId() {
        return id;
    }

    /**
     * Check if user has the specified permission
     */
    public boolean hasPermission(String permission) {
        return permissions.contains(permission);
    }

    /**
     * Check if user has ALL the specified permissions
     */
    public boolean hasAllPermissions(String... perms) {
        return Arrays.stream(perms).allMatch(permissions::contains);
    }

    /**
     * Check if user has ANY of the specified permissions
     */
    public boolean hasAnyPermission(String... perms) {
        return Arrays.stream(perms).anyMatch(permissions::contains);
    }

    /**
     * Check if user has the specified role
     */
    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // Convert roles to Spring Security authorities
        List<GrantedAuthority> authorities = roles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()))
                .collect(Collectors.toList());

        // Also add permissions as authorities
        permissions.forEach(perm ->
                authorities.add(new SimpleGrantedAuthority(perm)));

        return authorities;
    }

    @Override
    public String getPassword() {
        return null; // OTP-based auth, no password
    }

    @Override
    public String getUsername() {
        return phone;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
