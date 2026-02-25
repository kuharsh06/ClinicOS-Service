package com.clinicos.service.enums;

public enum QueueEntryState {
    WAITING("waiting"),
    CALLED("now_serving"),
    COMPLETED("completed"),
    REMOVED("removed"),
    STASHED("stashed");

    private final String value;

    QueueEntryState(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static QueueEntryState fromValue(String value) {
        for (QueueEntryState state : values()) {
            if (state.value.equalsIgnoreCase(value)) {
                return state;
            }
        }
        throw new IllegalArgumentException("Unknown QueueEntryState: " + value);
    }
}
