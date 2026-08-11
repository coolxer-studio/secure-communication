package com.coolxer.securecommunication.handshake;

import com.coolxer.securecommunication.core.InMemoryInstallationRegistry;
import com.coolxer.securecommunication.core.InMemorySessionRepository;
import com.coolxer.securecommunication.core.P256ServerIdentityProvider;
import com.coolxer.securecommunication.protocol.ProtocolConstants;
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

import static org.assertj.core.api.Assertions.assertThat;

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
