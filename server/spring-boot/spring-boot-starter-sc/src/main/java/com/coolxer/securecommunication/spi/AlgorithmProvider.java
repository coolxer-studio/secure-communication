package com.coolxer.securecommunication.spi;

import com.coolxer.securecommunication.protocol.SecureProtocolException;

public interface AlgorithmProvider {
    String suite();

    byte[] seal(byte[] key, byte[] nonce, byte[] aad, byte[] plaintext)
            throws SecureProtocolException;

    byte[] open(byte[] key, byte[] nonce, byte[] aad, byte[] ciphertextAndTag)
            throws SecureProtocolException;
}
