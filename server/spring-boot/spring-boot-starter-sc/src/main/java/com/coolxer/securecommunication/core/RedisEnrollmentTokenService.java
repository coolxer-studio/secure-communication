package com.coolxer.securecommunication.core;

import com.coolxer.securecommunication.protocol.SecureErrorCode;
import com.coolxer.securecommunication.protocol.SecureProtocolException;
import com.coolxer.securecommunication.spi.EnrollmentTokenService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

public final class RedisEnrollmentTokenService implements EnrollmentTokenService {
    private static final DefaultRedisScript<Long> CONSUME = new DefaultRedisScript<>(
            "local v=redis.call('GET',KEYS[1]); if v==ARGV[1] then "
                    + "redis.call('DEL',KEYS[1]); return 1 else return 0 end", Long.class);
    private final StringRedisTemplate redis;
    private final String prefix;
    private final SecureRandom random = new SecureRandom();

    public RedisEnrollmentTokenService(StringRedisTemplate redis, String prefix) {
        this.redis = redis; this.prefix = prefix;
    }

    @Override
    public String issue(String appId, String deviceType, Duration ttl)
            throws SecureProtocolException {
        if (ttl == null || ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("Enrollment token TTL must be within one hour");
        }
        try {
            byte[] randomBytes = new byte[32]; random.nextBytes(randomBytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
            redis.opsForValue().set(key(token), appId + "\n" + deviceType, ttl);
            return token;
        } catch (RuntimeException exception) {
            throw new SecureProtocolException(SecureErrorCode.KEY_PROVIDER_UNAVAILABLE, exception);
        }
    }

    @Override
    public void consume(String token, String appId, String deviceId, String deviceType)
            throws SecureProtocolException {
        try {
            Long consumed = redis.execute(
                    CONSUME, List.of(key(token)), appId + "\n" + deviceType);
            if (!Long.valueOf(1).equals(consumed)) {
                throw new SecureProtocolException(SecureErrorCode.ENROLLMENT_REQUIRED);
            }
        } catch (SecureProtocolException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new SecureProtocolException(SecureErrorCode.KEY_PROVIDER_UNAVAILABLE, exception);
        }
    }

    private String key(String token) throws SecureProtocolException {
        try {
            return prefix + ':' + java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(
                            java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new SecureProtocolException(SecureErrorCode.INTERNAL_ERROR, exception);
        }
    }
}
