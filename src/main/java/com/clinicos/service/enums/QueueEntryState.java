package com.clinicos.service.enums;

public enum QueueEntryState {
    WAITING("waiting"),
    CALLED("called"),
    IN_CONSULTATION("in_consultation"),
    COMPLETED("completed"),
    REMOVED("removed"),
    STASHED("stashed"),
    STEPPED_OUT("stepped_out");

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
