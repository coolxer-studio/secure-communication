package com.coolxer.securecommunication.spi;

import com.coolxer.securecommunication.protocol.SecureProtocolException;

import java.util.Optional;

public interface KeyProvider {
    Optional<SessionKeys> findSession(String keyId, String sessionId)
            throws SecureProtocolException;
}
