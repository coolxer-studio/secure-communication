package com.coolxer.securecommunication.core;

import com.coolxer.securecommunication.protocol.Direction;
import com.coolxer.securecommunication.protocol.SecureErrorCode;
import com.coolxer.securecommunication.protocol.SecureProtocolException;
import com.coolxer.securecommunication.spi.ReplayProtector;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * Cluster-safe replay store. Redis SET NX with an expiry performs the claim
 * atomically across application instances.
 */
public final class RedisReplayProtector implements ReplayProtector {
    private final StringRedisTemplate redis;
    private final String keyPrefix;

    public RedisReplayProtector(StringRedisTemplate redis, String keyPrefix) {
        if (redis == null || keyPrefix == null || keyPrefix.isBlank()) {
            throw new IllegalArgumentException("Redis replay configuration is invalid");
        }
        this.redis = redis;
        this.keyPrefix = keyPrefix;
    }

    @Override
    public boolean claim(String sessionId, Direction direction, long sequence, Duration ttl)
            throws SecureProtocolException {
        try {
            String key = keyPrefix + ':' + sessionId + ':' + direction.value() + ':' + sequence;
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, "1", ttl));
        } catch (RuntimeException exception) {
            throw new SecureProtocolException(
                    SecureErrorCode.REPLAY_STORE_UNAVAILABLE, exception);
        }
    }
}
