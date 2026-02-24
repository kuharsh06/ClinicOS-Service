package com.clinicos.service.exception;

import lombok.Getter;

/**
 * Exception thrown when a user lacks the required permission for an operation.
 */
@Getter
public class InsufficientPermissionException extends RuntimeException {

    private final String requiredPermission;

    public InsufficientPermissionException(String requiredPermission) {
        super("Required permission: " + requiredPermission);
        this.requiredPermission = requiredPermission;
    }

    public InsufficientPermissionException(String requiredPermission, String message) {
        super(message);
        this.requiredPermission = requiredPermission;
    }
}
