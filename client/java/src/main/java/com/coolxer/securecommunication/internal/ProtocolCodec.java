package com.coolxer.securecommunication.internal;

import com.coolxer.securecommunication.SecureError;
import com.coolxer.securecommunication.SecureRequest;
import com.coolxer.securecommunication.SecureResponse;

import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

final class ProtocolCodec {
    static final int VERSION = 1;
    static final String SUITE = "P256_HKDF_SHA256_AES256_GCM";
    static final String ENVELOPE_MEDIA_TYPE = "application/sc-envelope+json";
    static final String PROTECTED_MEDIA_TYPE = "application/sc-protected+json";
    static final String MESSAGE_ENDPOINT = "/sc/v1/message";

    private final SecureSession session;
    private final java.time.Clock clock;
    private final long allowedClockSkewMillis;

    ProtocolCodec(SecureSession session, java.time.Clock clock, long allowedClockSkewMillis) {
        this.session = session;
        this.clock = clock;
        this.allowedClockSkewMillis = allowedClockSkewMillis;
    }

    EncodedRequest encode(SecureRequest request) throws SecureError {
        try {
            long now = clock.millis();
            if (now >= session.expiresAt) {
                throw new SecureError("SC_UNKNOWN_SESSION", "Session has expired");
            }
            long sequence = session.takeSequence();
            String path = normalizePath(request.getLogicalPath());
            byte[] nonce = CryptoSupport.nonce(session.requestPrefix, sequence);
            ProtocolModels.Envelope unsigned = new ProtocolModels.Envelope(
                    VERSION, SUITE, session.keyId, session.sessionId, now, sequence,
                    request.getRequestId(), "POST", MESSAGE_ENDPOINT, PROTECTED_MEDIA_TYPE,
                    0, CryptoSupport.encode(nonce), "");
            ProtocolModels.ProtectedRequest payload = new ProtocolModels.ProtectedRequest(
                    request.getMethod(), path, request.getContentType(),
                    request.getProtectedHeaders(), CryptoSupport.encode(request.getBody()));
            byte[] ciphertext = CryptoSupport.crypt(Cipher.ENCRYPT_MODE, session.requestKey,
                    nonce, aad("request", unsigned), JsonSupport.write(payload));
            ProtocolModels.Envelope envelope = new ProtocolModels.Envelope(
                    unsigned.v(), unsigned.suite(), unsigned.kid(), unsigned.sid(),
                    unsigned.ts(), unsigned.seq(), unsigned.rid(), unsigned.m(), unsigned.p(),
                    unsigned.cty(), unsigned.st(), unsigned.nonce(), CryptoSupport.encode(ciphertext));
            return new EncodedRequest(JsonSupport.write(envelope), sequence, request.getRequestId());
        } catch (SecureError error) {
            throw error;
        } catch (IllegalArgumentException exception) {
            throw new SecureError("SC_INVALID_ENVELOPE", "Logical request is invalid",
                    0, null, exception);
        } catch (Exception exception) {
            throw new SecureError("SC_INTERNAL_ERROR", "Unable to encode secure request",
                    0, null, exception);
        }
    }

    SecureResponse decode(byte[] encoded, long expectedSequence, String expectedRequestId)
            throws SecureError {
        try {
            ProtocolModels.Envelope envelope = JsonSupport.readStrict(
                    encoded, ProtocolModels.Envelope.class);
            validateEnvelope(envelope, expectedSequence, expectedRequestId);
            byte[] expectedNonce = CryptoSupport.nonce(session.responsePrefix, expectedSequence);
            byte[] receivedNonce = CryptoSupport.decode(envelope.nonce(), false);
            if (!CryptoSupport.constantTimeEquals(expectedNonce, receivedNonce)) {
                throw invalidEnvelope(null);
            }
            byte[] plaintext = CryptoSupport.crypt(Cipher.DECRYPT_MODE, session.responseKey,
                    expectedNonce, aad("response", envelope),
                    CryptoSupport.decode(envelope.ciphertext(), false));
            ProtocolModels.ProtectedResponse response = JsonSupport.readStrict(
                    plaintext, ProtocolModels.ProtectedResponse.class);
            String contentType = normalizeContentType(response.contentType());
            return new SecureResponse(envelope.st(), contentType,
                    CryptoSupport.decode(response.body(), true));
        } catch (SecureError error) {
            throw error;
        } catch (Exception exception) {
            throw invalidEnvelope(exception);
        }
    }

    private void validateEnvelope(
            ProtocolModels.Envelope envelope, long expectedSequence, String expectedRequestId)
            throws SecureError {
        if (envelope.v() != VERSION) {
            throw new SecureError("SC_UNSUPPORTED_VERSION", "Envelope version is unsupported");
        }
        if (!SUITE.equals(envelope.suite())) {
            throw new SecureError("SC_UNSUPPORTED_SUITE", "Envelope suite is unsupported");
        }
        if (!session.keyId.equals(envelope.kid()) || !session.sessionId.equals(envelope.sid())) {
            throw new SecureError("SC_UNKNOWN_SESSION", "Envelope session does not match");
        }
        if (envelope.seq() != expectedSequence || !expectedRequestId.equals(envelope.rid())
                || !"POST".equals(envelope.m()) || !MESSAGE_ENDPOINT.equals(envelope.p())
                || !PROTECTED_MEDIA_TYPE.equals(envelope.cty())) {
            throw new SecureError("SC_ROUTE_MISMATCH", "Response does not match the request");
        }
        if (envelope.st() < 100 || envelope.st() > 599) throw invalidEnvelope(null);
        long now = clock.millis();
        if (envelope.ts() < now - allowedClockSkewMillis
                || envelope.ts() > now + allowedClockSkewMillis) {
            throw new SecureError("SC_REQUEST_EXPIRED", "Response timestamp is outside the allowed window");
        }
        if (envelope.nonce() == null || envelope.ciphertext() == null) throw invalidEnvelope(null);
    }

    static byte[] aad(String direction, ProtocolModels.Envelope envelope) {
        String value = String.join("\n", "SC1", direction, envelope.suite(), envelope.kid(),
                envelope.sid(), String.valueOf(envelope.ts()), String.valueOf(envelope.seq()),
                envelope.rid(), envelope.m(), envelope.p(), envelope.cty());
        if ("response".equals(direction)) value += "\n" + envelope.st();
        return value.getBytes(StandardCharsets.UTF_8);
    }

    static String normalizePath(String value) {
        if (value == null || !value.startsWith("/") || value.contains("#")
                || value.contains("://") || value.contains("\r") || value.contains("\n")
                || value.contains(" ") || value.indexOf('?') != value.lastIndexOf('?')) {
            throw new IllegalArgumentException("Invalid logical path");
        }
        String[] split = value.split("\\?", 2);
        String path = uppercasePercentHex(split[0]);
        if (split.length == 1 || split[1].isEmpty()) return path;
        List<String> pairs = new ArrayList<>();
        for (String pair : split[1].split("&", -1)) {
            if (!pair.isEmpty()) pairs.add(uppercasePercentHex(pair));
        }
        pairs.sort(Comparator.comparing(ProtocolCodec::queryName)
                .thenComparing(ProtocolCodec::queryValue));
        return pairs.isEmpty() ? path : path + "?" + String.join("&", pairs);
    }

    private static String normalizeContentType(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Response content type is required");
        }
        String contentType = value.split(";", 2)[0].trim().toLowerCase(java.util.Locale.ROOT);
        if (!contentType.matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")) {
            throw new IllegalArgumentException("Invalid content type");
        }
        return contentType;
    }

    private static String uppercasePercentHex(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '%') {
                if (index + 2 >= value.length()
                        || Character.digit(value.charAt(index + 1), 16) < 0
                        || Character.digit(value.charAt(index + 2), 16) < 0) {
                    throw new IllegalArgumentException("Invalid percent encoding");
                }
                result.append('%').append(Character.toUpperCase(value.charAt(++index)))
                        .append(Character.toUpperCase(value.charAt(++index)));
            } else {
                result.append(current);
            }
        }
        return result.toString();
    }

    private static String queryName(String pair) {
        int index = pair.indexOf('=');
        return index < 0 ? pair : pair.substring(0, index);
    }

    private static String queryValue(String pair) {
        int index = pair.indexOf('=');
        return index < 0 ? "" : pair.substring(index + 1);
    }

    private static SecureError invalidEnvelope(Throwable cause) {
        return new SecureError("SC_INVALID_ENVELOPE", "Secure response is invalid",
                0, null, cause);
    }

    record EncodedRequest(byte[] body, long sequence, String requestId) { }
}
