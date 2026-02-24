package com.clinicos.service.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {

    private String accessToken;
    private String refreshToken;
    private Long expiresAt;  // Unix ms
    private AuthUser user;
    private Boolean isNewUser;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuthUser {
        private String userId;           // uuid
        private String phone;
        private String name;             // null for new users
        private String orgId;            // null if no org yet
        private List<String> roles;      // ['admin', 'doctor'] or ['assistant']
        private List<String> permissions; // derived from roles
        private Boolean isProfileComplete;
        private String assignedDoctorId;   // for assistants (null for non-assistants)
        private String assignedDoctorName; // for assistants (null for non-assistants)
    }
}
