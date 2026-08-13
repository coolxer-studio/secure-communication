package com.coolxer.securecommunication;

/** Stable, non-sensitive failure returned by the SDK. */
public final class SecureError extends Exception {
    private final String code;
    private final int httpStatus;
    private final String traceId;

    public SecureError(String code, String message) {
        this(code, message, 0, null, null);
    }

    public SecureError(
            String code, String message, int httpStatus, String traceId, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.httpStatus = httpStatus;
        this.traceId = traceId;
    }

    public String getCode() { return code; }
    public int getHttpStatus() { return httpStatus; }
    public String getTraceId() { return traceId; }
}
