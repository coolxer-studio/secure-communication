package com.coolxer.securecommunication.protocol;

public enum SecureErrorCode {
    INVALID_ENVELOPE(400, "SC_INVALID_ENVELOPE", "Secure envelope is invalid"),
    ROUTE_MISMATCH(400, "SC_ROUTE_MISMATCH", "Secure route binding does not match"),
    TLS_REQUIRED(400, "SC_TLS_REQUIRED", "Secure communication requires TLS"),
    UNKNOWN_SESSION(401, "SC_UNKNOWN_SESSION", "Secure session is unavailable"),
    AUTHENTICATION_FAILED(401, "SC_AUTHENTICATION_FAILED", "Secure message authentication failed"),
    HANDSHAKE_FAILED(401, "SC_HANDSHAKE_FAILED", "Secure handshake failed"),
    ENROLLMENT_REQUIRED(401, "SC_ENROLLMENT_REQUIRED", "Installation enrollment is required"),
    REQUEST_EXPIRED(408, "SC_REQUEST_EXPIRED", "Secure request is outside the accepted time window"),
    REPLAY_DETECTED(409, "SC_REPLAY_DETECTED", "Secure request was already accepted"),
    PAYLOAD_TOO_LARGE(413, "SC_PAYLOAD_TOO_LARGE", "Secure payload is too large"),
    UNSUPPORTED_VERSION(426, "SC_UNSUPPORTED_VERSION", "Secure protocol version is unsupported"),
    UNSUPPORTED_SUITE(426, "SC_UNSUPPORTED_SUITE", "Secure algorithm suite is unsupported"),
    KEY_PROVIDER_UNAVAILABLE(503, "SC_KEY_PROVIDER_UNAVAILABLE", "Secure key provider is unavailable"),
    REPLAY_STORE_UNAVAILABLE(503, "SC_REPLAY_STORE_UNAVAILABLE", "Secure replay store is unavailable"),
    INTERNAL_ERROR(500, "SC_INTERNAL_ERROR", "Secure communication failed");

    private final int httpStatus;
    private final String code;
    private final String message;

    SecureErrorCode(int httpStatus, String code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }
}
