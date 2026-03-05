package com.clinicos.service.service.sms;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SmsResult {
    private final boolean success;
    private final String requestId;
    private final int statusCode;
    private final String errorMessage;

    public static SmsResult success(String requestId) {
        return SmsResult.builder().success(true).requestId(requestId).build();
    }

    public static SmsResult failure(int statusCode, String errorMessage) {
        return SmsResult.builder().success(false).statusCode(statusCode).errorMessage(errorMessage).build();
    }
}
