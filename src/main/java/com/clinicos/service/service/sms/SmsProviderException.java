package com.clinicos.service.service.sms;

import lombok.Getter;

@Getter
public class SmsProviderException extends RuntimeException {
    private final int statusCode;
    private final boolean retryable;

    public SmsProviderException(String message, int statusCode, boolean retryable) {
        super(message);
        this.statusCode = statusCode;
        this.retryable = retryable;
    }

    public SmsProviderException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
        this.retryable = false;
    }
}
