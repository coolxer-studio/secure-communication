package com.coolxer.securecommunication.protocol;

public enum Direction {
    REQUEST("request"),
    RESPONSE("response");

    private final String value;

    Direction(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
