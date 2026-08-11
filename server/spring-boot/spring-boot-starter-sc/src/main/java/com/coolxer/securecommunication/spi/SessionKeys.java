package com.coolxer.securecommunication.spi;

import java.time.Instant;
import java.util.Arrays;

public final class SessionKeys {
    private final String keyId;
    private final String sessionId;
    private final String suite;
    private final byte[] requestKey;
    private final byte[] responseKey;
    private final byte[] requestNoncePrefix;
    private final byte[] responseNoncePrefix;
    private final Instant expiresAt;
    private final boolean revoked;

    public SessionKeys(
            String keyId,
            String sessionId,
            String suite,
            byte[] requestKey,
            byte[] responseKey,
            byte[] requestNoncePrefix,
            byte[] responseNoncePrefix,
            Instant expiresAt,
            boolean revoked) {
        this.keyId = requireText(keyId);
        this.sessionId = requireText(sessionId);
        this.suite = requireText(suite);
        this.requestKey = copy(requestKey);
        this.responseKey = copy(responseKey);
        this.requestNoncePrefix = requirePrefix(requestNoncePrefix);
        this.responseNoncePrefix = requirePrefix(responseNoncePrefix);
        this.expiresAt = expiresAt;
        this.revoked = revoked;
    }

    public String keyId() {
        return keyId;
    }

    public String sessionId() {
        return sessionId;
    }

    public String suite() {
        return suite;
    }

    public byte[] requestKey() {
        return copy(requestKey);
    }

    public byte[] responseKey() {
        return copy(responseKey);
    }

    public byte[] requestNoncePrefix() {
        return copy(requestNoncePrefix);
    }

    public byte[] responseNoncePrefix() {
        return copy(responseNoncePrefix);
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public boolean revoked() {
        return revoked;
    }

    private static byte[] requirePrefix(byte[] value) {
        if (value == null || value.length != 4) {
            throw new IllegalArgumentException("Nonce prefix must contain exactly 4 bytes");
        }
        return copy(value);
    }

    private static byte[] copy(byte[] value) {
        if (value == null || value.length == 0) {
            throw new IllegalArgumentException("Key material must not be empty");
        }
        return Arrays.copyOf(value, value.length);
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Session identifier must not be blank");
        }
        return value;
    }
}
