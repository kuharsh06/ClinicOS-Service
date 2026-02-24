package com.clinicos.service.enums;

public enum QueueStatus {
    ACTIVE("active"),
    PAUSED("paused"),
    ENDED("ended");

    private final String value;

    QueueStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static QueueStatus fromValue(String value) {
        for (QueueStatus status : values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown QueueStatus: " + value);
    }
}
