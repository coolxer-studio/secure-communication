package com.coolxer.securecommunication.spi;

import com.coolxer.securecommunication.protocol.SecureProtocolException;

public interface ServerIdentityProvider {
    String keyId();

    byte[] signTranscript(byte[] transcriptHash) throws SecureProtocolException;

    byte[] encodedPublicKey();
}
