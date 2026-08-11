package com.coolxer.securecommunication.handshake;

import com.coolxer.securecommunication.core.InternationalSessionKeyDeriver;
import com.coolxer.securecommunication.protocol.ProtocolConstants;
import com.coolxer.securecommunication.protocol.SecureErrorCode;
import com.coolxer.securecommunication.protocol.SecureProtocolException;
import com.coolxer.securecommunication.spi.EnrollmentTokenService;
import com.coolxer.securecommunication.spi.HandshakeAuthorizer;
import com.coolxer.securecommunication.spi.InstallationRegistry;
import com.coolxer.securecommunication.spi.ServerIdentityProvider;
import com.coolxer.securecommunication.spi.SessionKeys;
import com.coolxer.securecommunication.spi.SessionRepository;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class HandshakeService {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._:@/-]{1,128}");
    private static final Set<String> DEVICE_TYPES =
            Set.of("H5", "HOST", "ANDROID", "IOS", "EMULATOR");

    private final ServerIdentityProvider serverIdentity;
    private final SessionRepository sessions;
    private final InstallationRegistry installations;
    private final EnrollmentTokenService enrollmentTokens;
    private final HandshakeAuthorizer authorizer;
    private final Duration sessionTtl;
    private final Duration clockSkew;
    private final Clock clock;
    private final InternationalSessionKeyDeriver keyDeriver =
            new InternationalSessionKeyDeriver();

    public HandshakeService(
            ServerIdentityProvider serverIdentity,
            SessionRepository sessions,
            InstallationRegistry installations,
            EnrollmentTokenService enrollmentTokens,
            HandshakeAuthorizer authorizer,
            Duration sessionTtl,
            Duration clockSkew,
            Clock clock) {
        this.serverIdentity = serverIdentity;
        this.sessions = sessions;
        this.installations = installations;
        this.enrollmentTokens = enrollmentTokens;
        this.authorizer = authorizer;
        this.sessionTtl = sessionTtl;
        this.clockSkew = clockSkew;
        this.clock = clock;
    }

    public HandshakeResponse start(
            HandshakeRequest request, String origin, String remoteAddress)
            throws SecureProtocolException {
        validate(request);
        try {
            byte[] clientEphemeralEncoded = decode(request.clientEphemeralPublicKey());
            byte[] installationEncoded = decode(request.installationPublicKey());
            PublicKey clientEphemeral = ecPublicKey(clientEphemeralEncoded);
            ecPublicKey(installationEncoded);
            java.util.Optional<byte[]> registered =
                    installations.find(request.appId(), request.deviceId());
            if (registered.isPresent()
                    && !MessageDigest.isEqual(registered.get(), installationEncoded)) {
                throw failure(SecureErrorCode.HANDSHAKE_FAILED);
            }
            authorizer.authorize(new HandshakeAuthorizer.HandshakeContext(
                    request.appId(), request.deviceId(), request.deviceType(), origin,
                    remoteAddress, registered.isPresent()));
            boolean newInstallation = registered.isEmpty();
            if (newInstallation && !"H5".equals(request.deviceType())) {
                if (request.enrollmentToken() == null
                        || request.enrollmentToken().isBlank()) {
                    throw failure(SecureErrorCode.ENROLLMENT_REQUIRED);
                }
                enrollmentTokens.consume(
                        request.enrollmentToken(), request.appId(), request.deviceId(),
                        request.deviceType());
            }

            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair serverEphemeral = generator.generateKeyPair();
            byte[] serverEphemeralEncoded = serverEphemeral.getPublic().getEncoded();
            byte[] serverIdentityEncoded = serverIdentity.encodedPublicKey();
            String keyId = requireId(serverIdentity.keyId());
            String sessionId = UUID.randomUUID().toString();
            Instant createdAt = clock.instant();
            Instant expiresAt = createdAt.plus(sessionTtl);
            byte[] transcriptHash = transcriptHash(
                    request, clientEphemeralEncoded, installationEncoded,
                    serverIdentityEncoded, serverEphemeralEncoded, keyId, sessionId,
                    createdAt.toEpochMilli(), expiresAt.toEpochMilli());
            byte[] signature = serverIdentity.signTranscript(transcriptHash);
            SessionKeys keys = keyDeriver.derive(
                    keyId, sessionId, serverEphemeral.getPrivate(), clientEphemeral,
                    transcriptHash, expiresAt);
            sessions.savePending(new SessionRepository.PendingSession(
                    keys, request.appId(), request.deviceId(), request.deviceType(),
                    installationEncoded, transcriptHash, expiresAt, newInstallation));
            return new HandshakeResponse(
                    ProtocolConstants.VERSION, ProtocolConstants.INTERNATIONAL_SUITE,
                    keyId, sessionId, encode(serverIdentityEncoded),
                    encode(serverEphemeralEncoded), createdAt.toEpochMilli(),
                    expiresAt.toEpochMilli(), encode(signature));
        } catch (SecureProtocolException exception) {
            throw exception;
        } catch (Exception exception) {
            throw failure(SecureErrorCode.HANDSHAKE_FAILED);
        }
    }

    public HandshakeFinishResponse finish(HandshakeFinishRequest request)
            throws SecureProtocolException {
        try {
            if (request == null || !ID.matcher(request.kid()).matches()
                    || !ID.matcher(request.sid()).matches()) {
                throw failure(SecureErrorCode.HANDSHAKE_FAILED);
            }
            SessionRepository.PendingSession pending = sessions
                    .findPending(request.kid(), request.sid())
                    .orElseThrow(() -> failure(SecureErrorCode.HANDSHAKE_FAILED));
            PublicKey installationKey = ecPublicKey(pending.installationPublicKey());
            byte[] proof = decode(request.proof());
            if (proof.length != 64 || !verifyP1363(
                    installationKey, pending.transcriptHash(), proof)) {
                sessions.remove(request.kid(), request.sid());
                throw failure(SecureErrorCode.HANDSHAKE_FAILED);
            }
            if (pending.registerInstallation()) {
                installations.register(
                        pending.appId(), pending.deviceId(), pending.deviceType(),
                        pending.installationPublicKey());
            }
            sessions.activate(request.kid(), request.sid());
            return new HandshakeFinishResponse(
                    true, pending.expiresAt().toEpochMilli());
        } catch (SecureProtocolException exception) {
            throw exception;
        } catch (Exception exception) {
            throw failure(SecureErrorCode.HANDSHAKE_FAILED);
        }
    }

    public static byte[] transcriptHash(
            HandshakeRequest request,
            byte[] clientEphemeral,
            byte[] installation,
            byte[] serverIdentity,
            byte[] serverEphemeral,
            String keyId,
            String sessionId,
            long createdAt,
            long expiresAt) throws Exception {
        String transcript = String.join("\n",
                "SC1-HANDSHAKE", String.valueOf(ProtocolConstants.VERSION),
                ProtocolConstants.INTERNATIONAL_SUITE, request.appId(),
                request.deviceId(), request.deviceType(), encode(clientEphemeral),
                encode(installation), encode(serverIdentity), encode(serverEphemeral),
                keyId, sessionId, String.valueOf(createdAt), String.valueOf(expiresAt));
        return MessageDigest.getInstance("SHA-256")
                .digest(transcript.getBytes(StandardCharsets.UTF_8));
    }

    private void validate(HandshakeRequest request) throws SecureProtocolException {
        long now = clock.millis();
        if (request == null || request.v() != ProtocolConstants.VERSION
                || !ProtocolConstants.INTERNATIONAL_SUITE.equals(request.suite())
                || !matches(request.appId()) || !matches(request.deviceId())
                || !DEVICE_TYPES.contains(request.deviceType())
                || Math.abs(now - request.timestamp()) > clockSkew.toMillis()) {
            throw failure(SecureErrorCode.HANDSHAKE_FAILED);
        }
    }

    private static boolean matches(String value) {
        return value != null && ID.matcher(value).matches();
    }

    private static String requireId(String value) throws SecureProtocolException {
        if (!matches(value)) throw failure(SecureErrorCode.HANDSHAKE_FAILED);
        return value;
    }

    private static PublicKey ecPublicKey(byte[] encoded) throws Exception {
        return KeyFactory.getInstance("EC")
                .generatePublic(new X509EncodedKeySpec(encoded));
    }

    private static boolean verifyP1363(PublicKey key, byte[] hash, byte[] p1363)
            throws Exception {
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(key);
        verifier.update(hash);
        return verifier.verify(p1363ToDer(p1363));
    }

    private static byte[] p1363ToDer(byte[] value) {
        byte[] r = integer(Arrays.copyOfRange(value, 0, 32));
        byte[] s = integer(Arrays.copyOfRange(value, 32, 64));
        byte[] der = new byte[6 + r.length + s.length];
        int offset = 0;
        der[offset++] = 0x30; der[offset++] = (byte) (der.length - 2);
        der[offset++] = 0x02; der[offset++] = (byte) r.length;
        System.arraycopy(r, 0, der, offset, r.length); offset += r.length;
        der[offset++] = 0x02; der[offset++] = (byte) s.length;
        System.arraycopy(s, 0, der, offset, s.length);
        return der;
    }

    private static byte[] integer(byte[] value) {
        int first = 0;
        while (first < value.length - 1 && value[first] == 0) first++;
        boolean prefix = (value[first] & 0x80) != 0;
        byte[] result = new byte[value.length - first + (prefix ? 1 : 0)];
        System.arraycopy(value, first, result, prefix ? 1 : 0, value.length - first);
        return result;
    }

    private static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static byte[] decode(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid Base64URL");
        }
        return Base64.getUrlDecoder().decode(value);
    }

    private static SecureProtocolException failure(SecureErrorCode code) {
        return new SecureProtocolException(code);
    }
}
