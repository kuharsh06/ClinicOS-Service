package com.clinicos.service.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateMemberRequest {

    private String name;
    private List<String> roles;
    private Boolean isActive;
    private String assignedDoctorId;  // null = clear assignment
}
