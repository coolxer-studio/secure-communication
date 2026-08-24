package com.coolxer.securecommunication.core;

import com.coolxer.securecommunication.spi.KeyProvider;
import com.coolxer.securecommunication.spi.SessionKeys;

import java.util.Optional;

/**
 * Fail-closed default. Applications enable usable v1 sessions by supplying a
 * KMS/HSM-backed KeyProvider bean.
 */
public final class RejectingKeyProvider implements KeyProvider {
    @Override
    public Optional<SessionKeys> findSession(String keyId, String sessionId) {
        return Optional.empty();
    }
}
