package com.coolxer.securecommunication.spi;

import com.coolxer.securecommunication.protocol.SecureProtocolException;

import java.time.Instant;
import java.util.Optional;

public interface SessionRepository extends KeyProvider {
    void savePending(PendingSession pending) throws SecureProtocolException;

    Optional<PendingSession> findPending(String keyId, String sessionId)
            throws SecureProtocolException;

    void activate(String keyId, String sessionId) throws SecureProtocolException;

    void remove(String keyId, String sessionId) throws SecureProtocolException;

    record PendingSession(
            SessionKeys keys,
            String appId,
            String deviceId,
            String deviceType,
            byte[] installationPublicKey,
            byte[] transcriptHash,
            Instant expiresAt,
            boolean registerInstallation) {
        public PendingSession {
            installationPublicKey = installationPublicKey.clone();
            transcriptHash = transcriptHash.clone();
        }

        @Override public byte[] installationPublicKey() {
            return installationPublicKey.clone();
        }

        @Override public byte[] transcriptHash() {
            return transcriptHash.clone();
        }
    }
}
