package com.clinicos.service.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OrgMemberResponse {

    private String userId;
    private String phone;
    private String name;
    private List<String> roles;
    private List<String> permissions;
    private Boolean isActive;

    // Profile databag
    private Map<String, Object> profileData;
    private Integer profileSchemaVersion;

    // Derived flags
    private Boolean isProfileComplete;

    // Assistant→Doctor assignment
    private String assignedDoctorId;
    private String assignedDoctorName;

    private String lastActiveAt;
    private String joinedAt;
}
