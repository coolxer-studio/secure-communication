package com.coolxer.securecommunication.core;

import com.coolxer.securecommunication.spi.SessionKeys;
import com.coolxer.securecommunication.spi.SessionRepository;

import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Single-node session store for development and tests. Cluster deployments replace it. */
public final class InMemorySessionRepository implements SessionRepository {
    private final ConcurrentHashMap<String, PendingSession> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SessionKeys> active = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemorySessionRepository() {
        this(Clock.systemUTC());
    }

    public InMemorySessionRepository(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void savePending(PendingSession value) {
        pending.put(key(value.keys().keyId(), value.keys().sessionId()), value);
    }

    @Override
    public Optional<PendingSession> findPending(String keyId, String sessionId) {
        PendingSession value = pending.get(key(keyId, sessionId));
        if (value != null && !value.expiresAt().isAfter(clock.instant())) {
            pending.remove(key(keyId, sessionId));
            return Optional.empty();
        }
        return Optional.ofNullable(value);
    }

    @Override
    public void activate(String keyId, String sessionId) {
        PendingSession value = pending.remove(key(keyId, sessionId));
        if (value != null && value.expiresAt().isAfter(clock.instant())) {
            active.put(key(keyId, sessionId), value.keys());
        }
    }

    @Override
    public void remove(String keyId, String sessionId) {
        pending.remove(key(keyId, sessionId));
        active.remove(key(keyId, sessionId));
    }

    @Override
    public Optional<SessionKeys> findSession(String keyId, String sessionId) {
        SessionKeys value = active.get(key(keyId, sessionId));
        if (value != null && !value.expiresAt().isAfter(clock.instant())) {
            active.remove(key(keyId, sessionId));
            return Optional.empty();
        }
        return Optional.ofNullable(value);
    }

    private static String key(String keyId, String sessionId) {
        return keyId + '\u0000' + sessionId;
    }
}
