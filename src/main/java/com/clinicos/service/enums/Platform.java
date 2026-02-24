package com.clinicos.service.enums;

public enum Platform {
    ANDROID("android"),
    IOS("ios"),
    WEB("web");

    private final String value;

    Platform(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static Platform fromValue(String value) {
        for (Platform platform : values()) {
            if (platform.value.equalsIgnoreCase(value)) {
                return platform;
            }
        }
        throw new IllegalArgumentException("Unknown Platform: " + value);
    }
}
