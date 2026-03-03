package com.clinicos.service.service.ai;

import lombok.Getter;

@Getter
public class AiProviderException extends RuntimeException {
    private final int statusCode;
    private final boolean retryable;
    private final int retryDelayMs;

    public AiProviderException(String message, int statusCode, boolean retryable, int retryDelayMs) {
        super(message);
        this.statusCode = statusCode;
        this.retryable = retryable;
        this.retryDelayMs = retryDelayMs;
    }

    public AiProviderException(String message, int statusCode, boolean retryable) {
        this(message, statusCode, retryable, 0);
    }
}
