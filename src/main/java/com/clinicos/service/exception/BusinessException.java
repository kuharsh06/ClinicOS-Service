package com.clinicos.service.exception;

import lombok.Getter;

/**
 * Exception for business logic errors.
 * Maps to HTTP 400 Bad Request.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final String code;
    private final boolean retryable;

    public BusinessException(String code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public BusinessException(String code, String message) {
        this(code, message, false);  // default: not retryable
    }

    public BusinessException(String message) {
        this("BUSINESS_ERROR", message, false);
    }
}
