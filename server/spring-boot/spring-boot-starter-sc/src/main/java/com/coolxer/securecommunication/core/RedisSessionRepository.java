package com.coolxer.securecommunication.core;

import com.coolxer.securecommunication.protocol.SecureErrorCode;
import com.coolxer.securecommunication.protocol.SecureProtocolException;
import com.coolxer.securecommunication.spi.SessionKeys;
import com.coolxer.securecommunication.spi.SessionRecordProtector;
import com.coolxer.securecommunication.spi.SessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/** Redis session repository with application-layer encryption for all key material. */
public final class RedisSessionRepository implements SessionRepository {
    private final StringRedisTemplate redis;
    private final String prefix;
    private final SessionRecordProtector protector;
    private final ObjectMapper mapper = new ObjectMapper();

    public RedisSessionRepository(
            StringRedisTemplate redis, String prefix, SessionRecordProtector protector) {
        this.redis = redis; this.prefix = prefix; this.protector = protector;
    }

    @Override public void savePending(PendingSession pending) throws SecureProtocolException {
        Record record = Record.from(pending, false);
        write(key("pending", pending.keys().keyId(), pending.keys().sessionId()),
                record, pending.expiresAt());
    }

    @Override public Optional<PendingSession> findPending(String keyId, String sessionId)
            throws SecureProtocolException {
        return read(key("pending", keyId, sessionId)).map(Record::toPending);
    }

    @Override public void activate(String keyId, String sessionId) throws SecureProtocolException {
        String pendingKey = key("pending", keyId, sessionId);
        Record record = read(pendingKey).orElseThrow(
                () -> new SecureProtocolException(SecureErrorCode.HANDSHAKE_FAILED));
        write(key("active", keyId, sessionId), record.asActive(), record.expiresAtInstant());
        redis.delete(pendingKey);
    }

    @Override public void remove(String keyId, String sessionId) {
        redis.delete(key("pending", keyId, sessionId));
        redis.delete(key("active", keyId, sessionId));
    }

    @Override public Optional<SessionKeys> findSession(String keyId, String sessionId)
            throws SecureProtocolException {
        return read(key("active", keyId, sessionId)).map(Record::toKeys);
    }

    private Optional<Record> read(String key) throws SecureProtocolException {
        try {
            String encoded = redis.opsForValue().get(key);
            if (encoded == null) return Optional.empty();
            byte[] record = protector.unprotect(Base64.getUrlDecoder().decode(encoded));
            Record value = mapper.readValue(record, Record.class);
            if (value.expiresAtInstant().isBefore(Instant.now())) {
                redis.delete(key); return Optional.empty();
            }
            return Optional.of(value);
        } catch (SecureProtocolException exception) { throw exception;
        } catch (Exception exception) {
            throw new SecureProtocolException(SecureErrorCode.KEY_PROVIDER_UNAVAILABLE, exception);
        }
    }

    private void write(String key, Record record, Instant expiresAt)
            throws SecureProtocolException {
        try {
            Duration ttl = Duration.between(Instant.now(), expiresAt);
            if (ttl.isNegative() || ttl.isZero()) {
                throw new SecureProtocolException(SecureErrorCode.UNKNOWN_SESSION);
            }
            byte[] protectedRecord = protector.protect(mapper.writeValueAsBytes(record));
            redis.opsForValue().set(key,
                    Base64.getUrlEncoder().withoutPadding().encodeToString(protectedRecord), ttl);
        } catch (SecureProtocolException exception) { throw exception;
        } catch (Exception exception) {
            throw new SecureProtocolException(SecureErrorCode.KEY_PROVIDER_UNAVAILABLE, exception);
        }
    }

    private String key(String state, String keyId, String sessionId) {
        return prefix + ':' + state + ':' + keyId + ':' + sessionId;
    }

    private record Record(
            String keyId, String sessionId, String suite,
            String requestKey, String responseKey,
            String requestPrefix, String responsePrefix,
            long expiresAt, boolean revoked, boolean active,
            String appId, String deviceId, String deviceType,
            String installationPublicKey, String transcriptHash,
            boolean registerInstallation) {
        static Record from(PendingSession pending, boolean active) {
            SessionKeys keys = pending.keys();
            return new Record(keys.keyId(), keys.sessionId(), keys.suite(),
                    enc(keys.requestKey()), enc(keys.responseKey()),
                    enc(keys.requestNoncePrefix()), enc(keys.responseNoncePrefix()),
                    keys.expiresAt().toEpochMilli(), keys.revoked(), active,
                    pending.appId(), pending.deviceId(), pending.deviceType(),
                    enc(pending.installationPublicKey()), enc(pending.transcriptHash()),
                    pending.registerInstallation());
        }
        Record asActive() {
            return new Record(keyId, sessionId, suite, requestKey, responseKey,
                    requestPrefix, responsePrefix, expiresAt, revoked, true,
                    appId, deviceId, deviceType, installationPublicKey,
                    transcriptHash, registerInstallation);
        }
        Instant expiresAtInstant() { return Instant.ofEpochMilli(expiresAt); }
        SessionKeys toKeys() {
            return new SessionKeys(keyId, sessionId, suite, dec(requestKey),
                    dec(responseKey), dec(requestPrefix), dec(responsePrefix),
                    expiresAtInstant(), revoked);
        }
        PendingSession toPending() {
            return new PendingSession(toKeys(), appId, deviceId, deviceType,
                    dec(installationPublicKey), dec(transcriptHash),
                    expiresAtInstant(), registerInstallation);
        }
        private static String enc(byte[] value) {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
        }
        private static byte[] dec(String value) {
            return Base64.getUrlDecoder().decode(value);
        }
    }
}
