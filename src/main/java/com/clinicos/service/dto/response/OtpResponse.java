package com.clinicos.service.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OtpResponse {

    private String requestId;
    private Integer expiresInSeconds;    // default: 300
    private Integer retryAfterSeconds;   // default: 30

    // For dev/test environments only - remove in production
    private String devOtp;
}
