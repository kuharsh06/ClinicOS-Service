package com.clinicos.service.enums;

public enum SmsStatus {
    PENDING("pending"),
    SENT("sent"),
    DELIVERED("delivered"),
    FAILED("failed"),
    DND_BLOCKED("dnd_blocked");

    private final String value;

    SmsStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static SmsStatus fromValue(String value) {
        for (SmsStatus status : values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown SmsStatus: " + value);
    }
}
