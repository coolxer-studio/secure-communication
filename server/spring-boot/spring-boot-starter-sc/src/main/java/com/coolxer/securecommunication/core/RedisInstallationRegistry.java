package com.coolxer.securecommunication.core;

import com.coolxer.securecommunication.protocol.SecureErrorCode;
import com.coolxer.securecommunication.protocol.SecureProtocolException;
import com.coolxer.securecommunication.spi.InstallationRegistry;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;

public final class RedisInstallationRegistry implements InstallationRegistry {
    private final StringRedisTemplate redis; private final String prefix;
    public RedisInstallationRegistry(StringRedisTemplate redis, String prefix) {
        this.redis = redis; this.prefix = prefix;
    }
    @Override public Optional<byte[]> find(String appId, String deviceId)
            throws SecureProtocolException {
        try {
            String value = redis.opsForValue().get(key(appId, deviceId));
            return value == null ? Optional.empty()
                    : Optional.of(Base64.getUrlDecoder().decode(value));
        } catch (RuntimeException exception) {
            throw new SecureProtocolException(SecureErrorCode.KEY_PROVIDER_UNAVAILABLE, exception);
        }
    }
    @Override public void register(String appId, String deviceId, String deviceType, byte[] key)
            throws SecureProtocolException {
        try {
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(key);
            String redisKey = key(appId, deviceId);
            Boolean created = redis.opsForValue().setIfAbsent(redisKey, encoded);
            if (!Boolean.TRUE.equals(created) && !encoded.equals(redis.opsForValue().get(redisKey))) {
                throw new SecureProtocolException(SecureErrorCode.HANDSHAKE_FAILED);
            }
        } catch (SecureProtocolException exception) { throw exception;
        } catch (RuntimeException exception) {
            throw new SecureProtocolException(SecureErrorCode.KEY_PROVIDER_UNAVAILABLE, exception);
        }
    }
    private String key(String appId, String deviceId) throws SecureProtocolException {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((appId + "\n" + deviceId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return prefix + ':' + java.util.HexFormat.of().formatHex(hash);
        } catch (Exception exception) {
            throw new SecureProtocolException(SecureErrorCode.INTERNAL_ERROR, exception);
        }
    }
}
