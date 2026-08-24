package com.coolxer.securecommunication.core;

import com.coolxer.securecommunication.protocol.SecureErrorCode;
import com.coolxer.securecommunication.protocol.SecureProtocolException;
import com.coolxer.securecommunication.spi.ServerIdentityProvider;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;

/** P-256 server identity backed by keys loaded by the hosting application/KMS adapter. */
public final class P256ServerIdentityProvider implements ServerIdentityProvider {
    private final String keyId;
    private final PrivateKey privateKey;
    private final PublicKey publicKey;

    public P256ServerIdentityProvider(
            String keyId, PrivateKey privateKey, PublicKey publicKey) {
        if (keyId == null || keyId.isBlank() || privateKey == null || publicKey == null) {
            throw new IllegalArgumentException("Server identity is incomplete");
        }
        this.keyId = keyId;
        this.privateKey = privateKey;
        this.publicKey = publicKey;
    }

    @Override public String keyId() { return keyId; }

    @Override
    public byte[] signTranscript(byte[] transcriptHash) throws SecureProtocolException {
        try {
            Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
            signature.initSign(privateKey);
            signature.update(transcriptHash);
            return signature.sign();
        } catch (Exception exception) {
            throw new SecureProtocolException(SecureErrorCode.KEY_PROVIDER_UNAVAILABLE, exception);
        }
    }

    @Override public byte[] encodedPublicKey() { return publicKey.getEncoded().clone(); }
}
