package com.coolxer.securecommunication.core;

import com.coolxer.securecommunication.protocol.Direction;
import com.coolxer.securecommunication.protocol.SecureErrorCode;
import com.coolxer.securecommunication.protocol.SecureProtocolException;
import com.coolxer.securecommunication.spi.ReplayProtector;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryReplayProtector implements ReplayProtector {
    private final ConcurrentHashMap<String, Long> acceptedUntil = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryReplayProtector() {
        this(Clock.systemUTC());
    }

    public InMemoryReplayProtector(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean claim(String sessionId, Direction direction, long sequence, Duration ttl)
            throws SecureProtocolException {
        if (sessionId == null || direction == null || sequence < 1
                || ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new SecureProtocolException(SecureErrorCode.INTERNAL_ERROR);
        }
        long now = clock.millis();
        long expiresAt;
        try {
            expiresAt = Math.addExact(now, ttl.toMillis());
        } catch (ArithmeticException exception) {
            throw new SecureProtocolException(SecureErrorCode.INTERNAL_ERROR, exception);
        }
        String key = sessionId + '\u0000' + direction.value() + '\u0000' + sequence;
        final boolean[] claimed = {false};
        acceptedUntil.compute(key, (ignored, existingExpiry) -> {
            if (existingExpiry == null || existingExpiry <= now) {
                claimed[0] = true;
                return expiresAt;
            }
            return existingExpiry;
        });
        if ((acceptedUntil.size() & 0x3ff) == 0) {
            acceptedUntil.entrySet().removeIf(entry -> entry.getValue() <= now);
        }
        return claimed[0];
    }
}
