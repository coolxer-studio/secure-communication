package com.coolxer.securecommunication.handshake;

import com.coolxer.securecommunication.core.InMemoryInstallationRegistry;
import com.coolxer.securecommunication.core.InMemorySessionRepository;
import com.coolxer.securecommunication.core.P256ServerIdentityProvider;
import com.coolxer.securecommunication.protocol.ProtocolConstants;
import com.coolxer.securecommunication.protocol.SecureErrorCode;
import com.coolxer.securecommunication.protocol.SecureProtocolException;
import com.coolxer.securecommunication.spi.EnrollmentTokenService;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HandshakeServiceTest {
    @Test
    void h5CompletesProofOfPossessionAndActivatesSession() throws Exception {
        KeyPair serverIdentity = keyPair();
        KeyPair clientEphemeral = keyPair();
        KeyPair installation = keyPair();
        Clock clock = Clock.fixed(Instant.parse("2026-08-11T04:00:00Z"), ZoneOffset.UTC);
        InMemorySessionRepository sessions = new InMemorySessionRepository(clock);
        InMemoryInstallationRegistry installations = new InMemoryInstallationRegistry();
        HandshakeService service = new HandshakeService(
                new P256ServerIdentityProvider(
                        "server-key-1", serverIdentity.getPrivate(), serverIdentity.getPublic()),
                sessions,
                installations,
                rejectingEnrollment(),
                ignored -> { },
                Duration.ofMinutes(10),
                Duration.ofMinutes(2),
                clock);
        HandshakeRequest request = new HandshakeRequest(
                1,
                ProtocolConstants.INTERNATIONAL_SUITE,
                "test-h5",
                "installation-1",
                "H5",
                encode(clientEphemeral.getPublic().getEncoded()),
                encode(installation.getPublic().getEncoded()),
                null,
                clock.millis());

        HandshakeResponse start = service.start(
                request, "https://app.example.test", "203.0.113.8");
        byte[] transcriptHash = HandshakeService.transcriptHash(
                request,
                clientEphemeral.getPublic().getEncoded(),
                installation.getPublic().getEncoded(),
                decode(start.serverIdentityPublicKey()),
                decode(start.serverEphemeralPublicKey()),
                start.kid(), start.sid(), start.createdAt(), start.expiresAt());
        Signature proof = Signature.getInstance("SHA256withECDSAinP1363Format");
        proof.initSign(installation.getPrivate());
        proof.update(transcriptHash);

        HandshakeFinishResponse finish = service.finish(new HandshakeFinishRequest(
                start.kid(), start.sid(), encode(proof.sign())));

        assertThat(finish.active()).isTrue();
        assertThat(sessions.findSession(start.kid(), start.sid())).isPresent();
        assertThat(installations.find("test-h5", "installation-1")).isPresent();
        assertThat(installations.find("test-h5", "installation-1").orElseThrow())
                .containsExactly(installation.getPublic().getEncoded());
    }

    @Test
    void serverConsumesEnrollmentTokenAndRegisteredIdentityReconnectsWithoutToken()
            throws Exception {
        KeyPair serverIdentity = keyPair();
        KeyPair installation = keyPair();
        Clock clock = Clock.fixed(Instant.parse("2026-08-11T04:00:00Z"), ZoneOffset.UTC);
        InMemorySessionRepository sessions = new InMemorySessionRepository(clock);
        InMemoryInstallationRegistry installations = new InMemoryInstallationRegistry();
        OneTimeEnrollmentTokens tokens = new OneTimeEnrollmentTokens();
        HandshakeService service = service(
                serverIdentity, sessions, installations, tokens, clock);
        String token = tokens.issue("synap-agent", "SERVER", Duration.ofMinutes(1));
        HandshakeRequest request = serverRequest(
                "installation-server-1", keyPair(), installation, token, clock);

        HandshakeResponse start = service.start(request, null, "127.0.0.1");
        finish(service, request, start, installation);

        assertThat(installations.find("synap-agent", "installation-server-1"))
                .hasValue(installation.getPublic().getEncoded());

        HandshakeRequest reconnect = serverRequest(
                "installation-server-1", keyPair(), installation, null, clock);
        assertThat(service.start(reconnect, null, "127.0.0.1")).isNotNull();
    }

    @Test
    void serverRejectsMissingMismatchedAndConsumedEnrollmentTokens() throws Exception {
        KeyPair serverIdentity = keyPair();
        KeyPair installation = keyPair();
        Clock clock = Clock.fixed(Instant.parse("2026-08-11T04:00:00Z"), ZoneOffset.UTC);
        InMemorySessionRepository sessions = new InMemorySessionRepository(clock);
        InMemoryInstallationRegistry installations = new InMemoryInstallationRegistry();
        OneTimeEnrollmentTokens tokens = new OneTimeEnrollmentTokens();
        HandshakeService service = service(
                serverIdentity, sessions, installations, tokens, clock);

        assertEnrollmentRequired(() -> service.start(serverRequest(
                "server-missing", keyPair(), installation, null, clock),
                null, "127.0.0.1"));

        String hostToken = tokens.issue("synap-agent", "HOST", Duration.ofMinutes(1));
        assertEnrollmentRequired(() -> service.start(serverRequest(
                "server-mismatched", keyPair(), installation, hostToken, clock),
                null, "127.0.0.1"));

        String serverToken = tokens.issue("synap-agent", "SERVER", Duration.ofMinutes(1));
        service.start(serverRequest(
                "server-first", keyPair(), installation, serverToken, clock),
                null, "127.0.0.1");
        assertEnrollmentRequired(() -> service.start(serverRequest(
                "server-second", keyPair(), installation, serverToken, clock),
                null, "127.0.0.1"));
    }

    @Test
    void existingNativeDeviceTypesRemainSupportedAndUnknownTypeIsRejected() throws Exception {
        KeyPair serverIdentity = keyPair();
        Clock clock = Clock.fixed(Instant.parse("2026-08-11T04:00:00Z"), ZoneOffset.UTC);
        InMemorySessionRepository sessions = new InMemorySessionRepository(clock);
        InMemoryInstallationRegistry installations = new InMemoryInstallationRegistry();
        OneTimeEnrollmentTokens tokens = new OneTimeEnrollmentTokens();
        HandshakeService service = service(
                serverIdentity, sessions, installations, tokens, clock);

        for (String deviceType : new String[]{"HOST", "ANDROID", "IOS", "EMULATOR"}) {
            String token = tokens.issue("synap-agent", deviceType, Duration.ofMinutes(1));
            HandshakeRequest request = nativeRequest(
                    deviceType.toLowerCase(), deviceType, keyPair(), keyPair(), token, clock);
            assertThat(service.start(request, null, "127.0.0.1")).isNotNull();
        }

        HandshakeRequest unknown = nativeRequest(
                "unknown", "DESKTOP", keyPair(), keyPair(), "token", clock);
        SecureProtocolException error = assertThrows(
                SecureProtocolException.class,
                () -> service.start(unknown, null, "127.0.0.1"));
        assertThat(error.errorCode()).isEqualTo(SecureErrorCode.HANDSHAKE_FAILED);
    }

    private static HandshakeService service(
            KeyPair serverIdentity,
            InMemorySessionRepository sessions,
            InMemoryInstallationRegistry installations,
            EnrollmentTokenService tokens,
            Clock clock) {
        return new HandshakeService(
                new P256ServerIdentityProvider(
                        "server-key-1", serverIdentity.getPrivate(), serverIdentity.getPublic()),
                sessions,
                installations,
                tokens,
                ignored -> { },
                Duration.ofMinutes(10),
                Duration.ofMinutes(2),
                clock);
    }

    private static HandshakeRequest serverRequest(
            String deviceId,
            KeyPair clientEphemeral,
            KeyPair installation,
            String token,
            Clock clock) {
        return nativeRequest(
                deviceId, "SERVER", clientEphemeral, installation, token, clock);
    }

    private static HandshakeRequest nativeRequest(
            String deviceId,
            String deviceType,
            KeyPair clientEphemeral,
            KeyPair installation,
            String token,
            Clock clock) {
        return new HandshakeRequest(
                1,
                ProtocolConstants.INTERNATIONAL_SUITE,
                "synap-agent",
                deviceId,
                deviceType,
                encode(clientEphemeral.getPublic().getEncoded()),
                encode(installation.getPublic().getEncoded()),
                token,
                clock.millis());
    }

    private static void finish(
            HandshakeService service,
            HandshakeRequest request,
            HandshakeResponse start,
            KeyPair installation) throws Exception {
        byte[] transcriptHash = HandshakeService.transcriptHash(
                request,
                decode(request.clientEphemeralPublicKey()),
                installation.getPublic().getEncoded(),
                decode(start.serverIdentityPublicKey()),
                decode(start.serverEphemeralPublicKey()),
                start.kid(), start.sid(), start.createdAt(), start.expiresAt());
        Signature proof = Signature.getInstance("SHA256withECDSAinP1363Format");
        proof.initSign(installation.getPrivate());
        proof.update(transcriptHash);
        assertThat(service.finish(new HandshakeFinishRequest(
                start.kid(), start.sid(), encode(proof.sign()))).active()).isTrue();
    }

    private static void assertEnrollmentRequired(CheckedOperation operation) throws Exception {
        SecureProtocolException error = assertThrows(
                SecureProtocolException.class, operation::run);
        assertThat(error.errorCode()).isEqualTo(SecureErrorCode.ENROLLMENT_REQUIRED);
    }

    private static EnrollmentTokenService rejectingEnrollment() {
        return new EnrollmentTokenService() {
            @Override
            public String issue(String appId, String deviceType, Duration ttl)
                    throws SecureProtocolException {
                throw new AssertionError("H5 must not issue enrollment tokens");
            }

            @Override
            public void consume(String token, String appId, String deviceId, String deviceType)
                    throws SecureProtocolException {
                throw new AssertionError("H5 must not consume enrollment tokens");
            }
        };
    }

    @FunctionalInterface
    private interface CheckedOperation {
        void run() throws Exception;
    }

    private static final class OneTimeEnrollmentTokens implements EnrollmentTokenService {
        private final Map<String, String> tokens = new ConcurrentHashMap<>();

        @Override
        public String issue(String appId, String deviceType, Duration ttl) {
            String token = UUID.randomUUID().toString();
            tokens.put(token, appId + '\n' + deviceType);
            return token;
        }

        @Override
        public void consume(String token, String appId, String deviceId, String deviceType)
                throws SecureProtocolException {
            if (!tokens.remove(token, appId + '\n' + deviceType)) {
                throw new SecureProtocolException(SecureErrorCode.ENROLLMENT_REQUIRED);
            }
        }
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static byte[] decode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }
}
