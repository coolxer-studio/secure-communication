package com.coolxer.securecommunication.internal;

import com.coolxer.securecommunication.SecureError;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

final class SecureSession {
    static final long MAX_SEQUENCE = 9_007_199_254_740_991L;
    final String keyId;
    final String sessionId;
    final byte[] requestKey;
    final byte[] responseKey;
    final byte[] requestPrefix;
    final byte[] responsePrefix;
    final long expiresAt;
    private final AtomicLong nextSequence = new AtomicLong(1);

    SecureSession(String keyId, String sessionId, byte[] material, long expiresAt) {
        this.keyId = keyId;
        this.sessionId = sessionId;
        this.requestKey = Arrays.copyOfRange(material, 0, 32);
        this.responseKey = Arrays.copyOfRange(material, 32, 64);
        this.requestPrefix = Arrays.copyOfRange(material, 64, 68);
        this.responsePrefix = Arrays.copyOfRange(material, 68, 72);
        this.expiresAt = expiresAt;
    }

    long takeSequence() throws SecureError {
        while (true) {
            long current = nextSequence.get();
            if (current > MAX_SEQUENCE) {
                throw new SecureError("SC_SEQUENCE_EXHAUSTED", "Session sequence is exhausted");
            }
            if (nextSequence.compareAndSet(current, current + 1)) return current;
        }
    }
}
