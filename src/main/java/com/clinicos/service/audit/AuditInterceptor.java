package com.clinicos.service.audit;

import com.clinicos.service.entity.AuditLog;
import com.clinicos.service.enums.AuditStatus;
import com.clinicos.service.repository.AuditLogRepository;
import com.clinicos.service.security.CustomUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.pattern.PathPattern;

import java.util.Map;

/**
 * Audit logging interceptor for DPDP Rule 6 compliance.
 * Logs every audited endpoint access (success, denied, not found) to audit_logs table.
 * Runs after request completion — does not slow down the response.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuditInterceptor implements HandlerInterceptor {

    private final AuditLogRepository auditLogRepository;

    @Override
    @SuppressWarnings("unchecked")
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        try {
            // 1. Get URI pattern (template with placeholders like /v1/orgs/{orgId}/patients/{patientId}/thread)
            String pattern = resolvePattern(request);
            if (pattern == null) {
                return; // No pattern matched — skip (e.g., static resources, error pages)
            }

            // 2. Look up in action mapping
            String key = request.getMethod() + ":" + pattern;
            AuditActionMapping.ActionConfig config = AuditActionMapping.MAPPINGS.get(key);
            if (config == null) {
                return; // Not an audited endpoint — silently skip
            }

            // 3. Get user context (null for public endpoints like OTP)
            String userId = null;
            String orgId = null;
            String deviceId = null;
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof CustomUserDetails userDetails) {
                userId = userDetails.getUuid();
                orgId = userDetails.getOrgUuid();
                deviceId = userDetails.getDeviceId();
            }

            // 4. Extract resourceId from path variables
            String resourceId = null;
            if (config.resourceIdParam() != null) {
                Map<String, String> pathVars = (Map<String, String>)
                        request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
                if (pathVars != null) {
                    resourceId = pathVars.get(config.resourceIdParam());
                }
            }

            // 5. Determine status from HTTP response code
            AuditStatus status = mapStatus(response.getStatus());

            // 6. Get denied reason (set by PermissionAspect via request attribute)
            String deniedReason = null;
            if (status == AuditStatus.DENIED) {
                Object deniedPerm = request.getAttribute("audit.deniedPermission");
                if (deniedPerm != null) {
                    deniedReason = "INSUFFICIENT_PERMISSION: " + deniedPerm;
                }
            }

            // 7. Get client IP (handle nginx X-Forwarded-For proxy)
            String ipAddress = resolveIpAddress(request);

            // 8. Device ID fallback to header if not in auth context
            if (deviceId == null) {
                deviceId = request.getHeader("X-Device-Id");
            }

            // 9. User-Agent
            String userAgent = request.getHeader("User-Agent");
            if (userAgent != null && userAgent.length() > 500) {
                userAgent = userAgent.substring(0, 500);
            }

            // 10. Save audit log
            AuditLog auditLog = AuditLog.builder()
                    .userId(userId)
                    .orgId(orgId)
                    .action(config.action().name())
                    .resourceType(config.action().getResourceType())
                    .resourceId(resourceId)
                    .status(status.name())
                    .deniedReason(deniedReason)
                    .endpoint(pattern)
                    .ipAddress(ipAddress)
                    .deviceId(deviceId)
                    .userAgent(userAgent)
                    .build();

            auditLogRepository.save(auditLog);

        } catch (Exception auditEx) {
            // Audit logging must never break the actual request
            log.error("Audit logging failed: {}", auditEx.getMessage());
        }
    }

    private String resolvePattern(HttpServletRequest request) {
        Object rawPattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (rawPattern instanceof String s) {
            return s;
        } else if (rawPattern instanceof PathPattern pp) {
            return pp.getPatternString();
        }
        return null;
    }

    private AuditStatus mapStatus(int httpStatus) {
        if (httpStatus >= 200 && httpStatus < 300) {
            return AuditStatus.SUCCESS;
        } else if (httpStatus == 403) {
            return AuditStatus.DENIED;
        } else if (httpStatus == 404) {
            return AuditStatus.NOT_FOUND;
        } else {
            return AuditStatus.ERROR;
        }
    }

    private String resolveIpAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty()) {
            // X-Forwarded-For can contain multiple IPs: "client, proxy1, proxy2"
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
