package com.coolxer.securecommunication.core;

import com.coolxer.securecommunication.protocol.SecureErrorCode;
import com.coolxer.securecommunication.protocol.SecureProtocolException;
import com.coolxer.securecommunication.spi.SessionRecordProtector;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;

public final class AesGcmSessionRecordProtector implements SessionRecordProtector {
    private static final byte[] AAD = "SC1-REDIS-SESSION".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public AesGcmSessionRecordProtector(byte[] key) {
        if (key == null || key.length != 32) {
            throw new IllegalArgumentException("Session record key must contain 32 bytes");
        }
        this.key = new SecretKeySpec(key.clone(), "AES");
    }

    @Override public byte[] protect(byte[] plaintext) throws SecureProtocolException {
        try {
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(AAD);
            byte[] ciphertext = cipher.doFinal(plaintext);
            byte[] result = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, result, 0, nonce.length);
            System.arraycopy(ciphertext, 0, result, nonce.length, ciphertext.length);
            return result;
        } catch (Exception exception) {
            throw new SecureProtocolException(SecureErrorCode.KEY_PROVIDER_UNAVAILABLE, exception);
        }
    }

    @Override public byte[] unprotect(byte[] record) throws SecureProtocolException {
        try {
            if (record == null || record.length < 29) throw new IllegalArgumentException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, record, 0, 12));
            cipher.updateAAD(AAD);
            return cipher.doFinal(record, 12, record.length - 12);
        } catch (Exception exception) {
            throw new SecureProtocolException(SecureErrorCode.KEY_PROVIDER_UNAVAILABLE, exception);
        }
    }
}
