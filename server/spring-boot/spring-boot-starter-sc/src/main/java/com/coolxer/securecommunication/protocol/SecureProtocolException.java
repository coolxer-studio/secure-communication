package com.coolxer.securecommunication.protocol;

public final class SecureProtocolException extends Exception {
    private final SecureErrorCode errorCode;

    public SecureProtocolException(SecureErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public SecureProtocolException(SecureErrorCode errorCode, Throwable cause) {
        super(errorCode.message(), cause);
        this.errorCode = errorCode;
    }

    public SecureErrorCode errorCode() {
        return errorCode;
    }
}
