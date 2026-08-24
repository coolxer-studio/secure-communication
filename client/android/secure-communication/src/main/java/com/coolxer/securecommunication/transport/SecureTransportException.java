package com.coolxer.securecommunication.internal.transport;

import com.coolxer.securecommunication.SecureError;

import java.io.IOException;

public final class SecureTransportException extends IOException {
    private final SecureError secureError;

    SecureTransportException(SecureError secureError) {
        super(secureError.getMessage(), secureError);
        this.secureError = secureError;
    }

    public SecureError getSecureError() {
        return secureError;
    }
}
