package com.coolxer.securecommunication.core;

import com.coolxer.securecommunication.protocol.ProtocolConstants;
import com.coolxer.securecommunication.protocol.SecureErrorCode;
import com.coolxer.securecommunication.protocol.SecureProtocolException;
import com.coolxer.securecommunication.spi.AlgorithmProvider;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;

public final class AesGcmAlgorithmProvider implements AlgorithmProvider {
    private static final int KEY_BYTES = 32;

    @Override
    public String suite() {
        return ProtocolConstants.INTERNATIONAL_SUITE;
    }

    @Override
    public byte[] seal(byte[] key, byte[] nonce, byte[] aad, byte[] plaintext)
            throws SecureProtocolException {
        return crypt(Cipher.ENCRYPT_MODE, key, nonce, aad, plaintext);
    }

    @Override
    public byte[] open(byte[] key, byte[] nonce, byte[] aad, byte[] ciphertextAndTag)
            throws SecureProtocolException {
        if (ciphertextAndTag == null || ciphertextAndTag.length < ProtocolConstants.TAG_BYTES) {
            throw new SecureProtocolException(SecureErrorCode.INVALID_ENVELOPE);
        }
        return crypt(Cipher.DECRYPT_MODE, key, nonce, aad, ciphertextAndTag);
    }

    private byte[] crypt(int mode, byte[] key, byte[] nonce, byte[] aad, byte[] input)
            throws SecureProtocolException {
        if (key == null || key.length != KEY_BYTES
                || nonce == null || nonce.length != ProtocolConstants.NONCE_BYTES) {
            throw new SecureProtocolException(SecureErrorCode.INVALID_ENVELOPE);
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(ProtocolConstants.TAG_BYTES * 8, nonce));
            cipher.updateAAD(aad);
            return cipher.doFinal(input);
        } catch (AEADBadTagException exception) {
            throw new SecureProtocolException(
                    SecureErrorCode.AUTHENTICATION_FAILED, exception);
        } catch (GeneralSecurityException exception) {
            SecureErrorCode code = mode == Cipher.DECRYPT_MODE
                    ? SecureErrorCode.AUTHENTICATION_FAILED
                    : SecureErrorCode.INTERNAL_ERROR;
            throw new SecureProtocolException(code, exception);
        }
    }
}
