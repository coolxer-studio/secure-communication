package com.coolxer.securecommunication.internal.protocol;

import java.util.Arrays;
import javax.crypto.SecretKey;

public final class SecureSession {
    public static final String INTERNATIONAL_SUITE =
            "P256_HKDF_SHA256_AES256_GCM";

    private final String keyId;
    private final String sessionId;
    private final String suite;
    private final SecretKey requestKey;
    private final SecretKey responseKey;
    private final byte[] requestNoncePrefix;
    private final byte[] responseNoncePrefix;
    private final long expiresAtEpochMillis;

    public SecureSession(
            String keyId,
            String sessionId,
            String suite,
            SecretKey requestKey,
            SecretKey responseKey,
            byte[] requestNoncePrefix,
            byte[] responseNoncePrefix,
            long expiresAtEpochMillis) {
        if (keyId == null || sessionId == null || requestKey == null || responseKey == null
                || requestNoncePrefix == null || requestNoncePrefix.length != 4
                || responseNoncePrefix == null || responseNoncePrefix.length != 4) {
            throw new IllegalArgumentException("Invalid secure session");
        }
        this.keyId = keyId;
        this.sessionId = sessionId;
        this.suite = suite;
        this.requestKey = requestKey;
        this.responseKey = responseKey;
        this.requestNoncePrefix = Arrays.copyOf(requestNoncePrefix, 4);
        this.responseNoncePrefix = Arrays.copyOf(responseNoncePrefix, 4);
        this.expiresAtEpochMillis = expiresAtEpochMillis;
    }

    public String getKeyId() { return keyId; }
    public String getSessionId() { return sessionId; }
    public String getSuite() { return suite; }
    public SecretKey getRequestKey() { return requestKey; }
    public SecretKey getResponseKey() { return responseKey; }
    public byte[] getRequestNoncePrefix() {
        return Arrays.copyOf(requestNoncePrefix, 4);
    }
    public byte[] getResponseNoncePrefix() {
        return Arrays.copyOf(responseNoncePrefix, 4);
    }
    public long getExpiresAtEpochMillis() { return expiresAtEpochMillis; }
}
