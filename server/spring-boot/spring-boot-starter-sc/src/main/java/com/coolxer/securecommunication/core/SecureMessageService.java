package com.coolxer.securecommunication.core;

import com.coolxer.securecommunication.protocol.AadFactory;
import com.coolxer.securecommunication.protocol.Direction;
import com.coolxer.securecommunication.protocol.NonceFactory;
import com.coolxer.securecommunication.protocol.ProtocolConstants;
import com.coolxer.securecommunication.protocol.RequestMetadata;
import com.coolxer.securecommunication.protocol.SecureEnvelope;
import com.coolxer.securecommunication.protocol.SecureErrorCode;
import com.coolxer.securecommunication.protocol.SecureProtocolException;
import com.coolxer.securecommunication.spi.AlgorithmProvider;
import com.coolxer.securecommunication.spi.KeyProvider;
import com.coolxer.securecommunication.spi.ReplayProtector;
import com.coolxer.securecommunication.spi.SecurityPolicy;
import com.coolxer.securecommunication.spi.SessionKeys;

import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

public final class SecureMessageService {
    private static final Pattern IDENTIFIER = Pattern.compile("[\\x21-\\x7e]{1,128}");
    private static final Pattern BASE64URL = Pattern.compile("[A-Za-z0-9_-]+");

    private final EnvelopeCodec envelopeCodec;
    private final KeyProvider keyProvider;
    private final ReplayProtector replayStore;
    private final SecurityPolicy policy;
    private final Map<String, AlgorithmProvider> algorithms;

    public SecureMessageService(
            EnvelopeCodec envelopeCodec,
            KeyProvider keyProvider,
            ReplayProtector replayStore,
            SecurityPolicy policy,
            Collection<AlgorithmProvider> algorithms) {
        this.envelopeCodec = envelopeCodec;
        this.keyProvider = keyProvider;
        this.replayStore = replayStore;
        this.policy = policy;
        this.algorithms = new HashMap<>();
        for (AlgorithmProvider algorithm : algorithms) {
            if (this.algorithms.put(algorithm.suite(), algorithm) != null) {
                throw new IllegalArgumentException("Duplicate algorithm suite: " + algorithm.suite());
            }
        }
    }

    public OpenedRequest openRequest(byte[] encoded)
            throws SecureProtocolException {
        if (encoded == null || encoded.length > policy.maxEnvelopeBytes()) {
            throw new SecureProtocolException(SecureErrorCode.PAYLOAD_TOO_LARGE);
        }
        SecureEnvelope envelope = envelopeCodec.decode(encoded);
        validateEnvelope(envelope);
        SessionKeys session = resolveSession(envelope);
        AlgorithmProvider algorithm = algorithm(envelope.getSuite());
        byte[] nonce = decode(envelope.getNonce(), ProtocolConstants.NONCE_BYTES);
        NonceFactory.requireMatches(session.requestNoncePrefix(), envelope.getSeq(), nonce);
        byte[] ciphertext = decode(envelope.getCt(), -1);
        if (ciphertext.length < ProtocolConstants.TAG_BYTES
                || ciphertext.length > policy.maxPlaintextBytes() + ProtocolConstants.TAG_BYTES) {
            throw new SecureProtocolException(SecureErrorCode.PAYLOAD_TOO_LARGE);
        }
        RequestMetadata metadata = new RequestMetadata(
                envelope.getM(), envelope.getP(), envelope.getCty());
        byte[] plaintext = algorithm.open(
                session.requestKey(), nonce,
                AadFactory.create(Direction.REQUEST, envelope, metadata),
                ciphertext);
        if (plaintext.length > policy.maxPlaintextBytes()) {
            throw new SecureProtocolException(SecureErrorCode.PAYLOAD_TOO_LARGE);
        }
        if (!replayStore.claim(envelope.getSid(), Direction.REQUEST,
                envelope.getSeq(), policy.replayTtl())) {
            throw new SecureProtocolException(SecureErrorCode.REPLAY_DETECTED);
        }
        return new OpenedRequest(envelope, session, metadata, plaintext);
    }

    public byte[] sealResponse(
            OpenedRequest request, byte[] plaintext, String contentType, int status)
            throws SecureProtocolException {
        if (plaintext == null || plaintext.length > policy.maxPlaintextBytes()) {
            throw new SecureProtocolException(SecureErrorCode.PAYLOAD_TOO_LARGE);
        }
        if (status < 100 || status > 599) {
            throw new SecureProtocolException(SecureErrorCode.INTERNAL_ERROR);
        }
        String normalizedContentType = AadFactory.normalizeContentType(contentType);
        SessionKeys session = request.session();
        long timestamp = policy.clock().millis();
        long sequence = request.envelope().getSeq();
        byte[] nonce = NonceFactory.create(session.responseNoncePrefix(), sequence);
        SecureEnvelope unsigned = new SecureEnvelope(
                ProtocolConstants.VERSION,
                session.suite(),
                session.keyId(),
                session.sessionId(),
                timestamp,
                sequence,
                request.envelope().getRid(),
                ProtocolConstants.OUTER_METHOD,
                ProtocolConstants.MESSAGE_ENDPOINT,
                ProtocolConstants.PROTECTED_MEDIA_TYPE,
                status,
                Base64.getUrlEncoder().withoutPadding().encodeToString(nonce),
                "");
        RequestMetadata responseMetadata = new RequestMetadata(
                ProtocolConstants.OUTER_METHOD,
                ProtocolConstants.MESSAGE_ENDPOINT,
                ProtocolConstants.PROTECTED_MEDIA_TYPE);
        byte[] ciphertext = algorithm(session.suite()).seal(
                session.responseKey(), nonce,
                AadFactory.create(Direction.RESPONSE, unsigned, responseMetadata),
                plaintext);
        SecureEnvelope sealed = new SecureEnvelope(
                unsigned.getV(), unsigned.getSuite(), unsigned.getKid(), unsigned.getSid(),
                unsigned.getTs(), unsigned.getSeq(), unsigned.getRid(), unsigned.getM(), unsigned.getP(),
                unsigned.getCty(), unsigned.getSt(), unsigned.getNonce(),
                Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext));
        return envelopeCodec.encode(sealed);
    }

    private void validateEnvelope(SecureEnvelope envelope) throws SecureProtocolException {
        if (envelope.getV() != ProtocolConstants.VERSION) {
            throw new SecureProtocolException(SecureErrorCode.UNSUPPORTED_VERSION);
        }
        if (!policy.allowsSuite(envelope.getSuite())) {
            throw new SecureProtocolException(SecureErrorCode.UNSUPPORTED_SUITE);
        }
        requireIdentifier(envelope.getKid());
        requireIdentifier(envelope.getSid());
        requireIdentifier(envelope.getRid());
        if (envelope.getSeq() < 1 || envelope.getSeq() > 9_007_199_254_740_991L) {
            throw new SecureProtocolException(SecureErrorCode.INVALID_ENVELOPE);
        }
        if (envelope.getSt() != 0) {
            throw new SecureProtocolException(SecureErrorCode.INVALID_ENVELOPE);
        }
        if (!ProtocolConstants.OUTER_METHOD.equals(envelope.getM())
                || !ProtocolConstants.MESSAGE_ENDPOINT.equals(envelope.getP())
                || !ProtocolConstants.PROTECTED_MEDIA_TYPE.equals(envelope.getCty())) {
            throw new SecureProtocolException(SecureErrorCode.ROUTE_MISMATCH);
        }
        long now = policy.clock().millis();
        long skew = policy.clockSkew().toMillis();
        if (envelope.getTs() < now - skew || envelope.getTs() > now + skew) {
            throw new SecureProtocolException(SecureErrorCode.REQUEST_EXPIRED);
        }
    }

    private SessionKeys resolveSession(SecureEnvelope envelope)
            throws SecureProtocolException {
        Optional<SessionKeys> found = keyProvider.findSession(
                envelope.getKid(), envelope.getSid());
        SessionKeys session = found.orElseThrow(
                () -> new SecureProtocolException(SecureErrorCode.UNKNOWN_SESSION));
        Instant now = policy.clock().instant();
        if (session.revoked() || session.expiresAt() == null
                || !session.expiresAt().isAfter(now)
                || !session.suite().equals(envelope.getSuite())) {
            throw new SecureProtocolException(SecureErrorCode.UNKNOWN_SESSION);
        }
        return session;
    }

    private AlgorithmProvider algorithm(String suite) throws SecureProtocolException {
        AlgorithmProvider algorithm = algorithms.get(suite);
        if (algorithm == null) {
            throw new SecureProtocolException(SecureErrorCode.UNSUPPORTED_SUITE);
        }
        return algorithm;
    }

    private static byte[] decode(String value, int expectedSize)
            throws SecureProtocolException {
        if (value == null || value.isEmpty() || !BASE64URL.matcher(value).matches()) {
            throw new SecureProtocolException(SecureErrorCode.INVALID_ENVELOPE);
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            if (expectedSize >= 0 && decoded.length != expectedSize) {
                throw new SecureProtocolException(SecureErrorCode.INVALID_ENVELOPE);
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new SecureProtocolException(SecureErrorCode.INVALID_ENVELOPE, exception);
        }
    }

    private static void requireIdentifier(String value) throws SecureProtocolException {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new SecureProtocolException(SecureErrorCode.INVALID_ENVELOPE);
        }
    }

    public record OpenedRequest(
            SecureEnvelope envelope,
            SessionKeys session,
            RequestMetadata metadata,
            byte[] plaintext) {
    }
}
