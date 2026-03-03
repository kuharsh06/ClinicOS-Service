package com.clinicos.service.security;

import com.clinicos.service.exception.InsufficientPermissionException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Aspect that enforces permission-based access control.
 * Intercepts methods annotated with @RequirePermission and verifies
 * the authenticated user has the required permissions.
 */
@Aspect
@Component
@Slf4j
public class PermissionAspect {

    @Before("@annotation(com.clinicos.service.security.RequirePermission)")
    public void checkPermission(JoinPoint joinPoint) {
        // Get the annotation
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        RequirePermission annotation = method.getAnnotation(RequirePermission.class);

        if (annotation == null) {
            return;
        }

        String[] requiredPermissions = annotation.value();

        // Get current authentication
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new InsufficientPermissionException(
                    requiredPermissions[0],
                    "User is not authenticated"
            );
        }

        Object principal = authentication.getPrincipal();
        if (!(principal instanceof CustomUserDetails)) {
            throw new InsufficientPermissionException(
                    requiredPermissions[0],
                    "Invalid authentication principal"
            );
        }

        CustomUserDetails userDetails = (CustomUserDetails) principal;

        // Check if user has ALL required permissions
        for (String permission : requiredPermissions) {
            if (!userDetails.hasPermission(permission)) {
                log.warn("User {} denied access to {} - missing permission: {}",
                        userDetails.getUuid(),
                        method.getName(),
                        permission);

                // Pass denied permission to AuditInterceptor via request attribute
                ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
                if (attrs != null) {
                    attrs.getRequest().setAttribute("audit.deniedPermission", permission);
                }

                throw new InsufficientPermissionException(permission);
            }
        }

        log.debug("User {} granted access to {} with permissions: {}",
                userDetails.getUuid(),
                method.getName(),
                Arrays.toString(requiredPermissions));
    }
}
