package com.clinicos.service.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to enforce permission-based access control on endpoints.
 *
 * Usage:
 * @RequirePermission("queue:add_patient")
 * @RequirePermission({"queue:view", "patient:view"})  // requires ALL permissions
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {

    /**
     * The permission(s) required to access this endpoint.
     * If multiple permissions are specified, ALL must be present (AND logic).
     */
    String[] value();
}
