package com.coolxer.securecommunication.core;

import com.coolxer.securecommunication.spi.KeyProvider;
import com.coolxer.securecommunication.spi.SessionKeys;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test/local provider. Production applications must supply a KMS/HSM-backed KeyProvider.
 */
public final class InMemoryKeyProvider implements KeyProvider {
    private final ConcurrentHashMap<String, SessionKeys> sessions = new ConcurrentHashMap<>();

    public void put(SessionKeys session) {
        sessions.put(key(session.keyId(), session.sessionId()), session);
    }

    public void revoke(String keyId, String sessionId) {
        sessions.remove(key(keyId, sessionId));
    }

    @Override
    public Optional<SessionKeys> findSession(String keyId, String sessionId) {
        return Optional.ofNullable(sessions.get(key(keyId, sessionId)));
    }

    private static String key(String keyId, String sessionId) {
        return keyId + '\u0000' + sessionId;
    }
}
