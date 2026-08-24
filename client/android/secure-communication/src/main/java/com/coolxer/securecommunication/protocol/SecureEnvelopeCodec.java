package com.coolxer.securecommunication.internal.protocol;

import android.util.Base64;

import com.coolxer.securecommunication.SecureError;
import com.coolxer.securecommunication.SecureRequest;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

public final class SecureEnvelopeCodec {
    public static final String ENVELOPE_MEDIA_TYPE = "application/sc-envelope+json";
    private static final String PROTECTED_MEDIA_TYPE = "application/sc-protected+json";
    private static final String MESSAGE_ENDPOINT = "/sc/v1/message";
    private static final Set<String> FIELDS = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList("v", "suite", "kid", "sid", "ts", "seq", "rid",
                    "m", "p", "cty", "st", "nonce", "ct")));

    private final SecureSession session;
    private final SequenceStore sequences;
    private final Clock clock;
    private final long allowedClockSkewMillis;

    public SecureEnvelopeCodec(
            SecureSession session,
            SequenceStore sequences,
            Clock clock,
            long allowedClockSkewMillis) {
        if (session == null || sequences == null || clock == null
                || !SecureSession.INTERNATIONAL_SUITE.equals(session.getSuite())
                || allowedClockSkewMillis < 0) {
            throw new IllegalArgumentException("Invalid v1 codec configuration");
        }
        this.session = session;
        this.sequences = sequences;
        this.clock = clock;
        this.allowedClockSkewMillis = allowedClockSkewMillis;
    }

    public EncodedRequest encode(SecureRequest request) throws SecureError {
        long timestamp = clock.epochMillis();
        if (timestamp >= session.getExpiresAtEpochMillis()) {
            throw new SecureError("SC_UNKNOWN_SESSION", "Session has expired");
        }
        long sequence = sequences.next(session.getSessionId());
        String method = normalizeMethod(request.getMethod());
        String path = normalizePath(request.getLogicalPath());
        String contentType = normalizeContentType(request.getContentType());
        String requestId = request.getRequestId();
        if (!requestId.matches("[\\x21-\\x7e]{1,128}")) {
            throw new SecureError("SC_INVALID_ENVELOPE", "Request ID is invalid");
        }
        byte[] nonce = nonce(session.getRequestNoncePrefix(), sequence);
        JSONObject envelope = envelope(
                timestamp, sequence, requestId, "POST", MESSAGE_ENDPOINT,
                PROTECTED_MEDIA_TYPE, 0, encode(nonce), "");
        byte[] ciphertext = crypt(
                Cipher.ENCRYPT_MODE,
                session.getRequestKey(),
                nonce,
                aad("request", envelope),
                protectedPayload(request));
        try {
            envelope.put("ct", encode(ciphertext));
            return new EncodedRequest(
                    envelope.toString(), sequence, requestId, method, path, contentType);
        } catch (JSONException exception) {
            throw new SecureError(
                    "SC_INTERNAL_ERROR", "Unable to encode envelope", 0, null, exception);
        }
    }

    public DecodedResponse decode(
            String encoded, long expectedSequence, String expectedRequestId)
            throws SecureError {
        JSONObject envelope;
        try {
            envelope = new JSONObject(encoded);
            validateFields(envelope);
            if (envelope.getInt("v") != 1) {
                throw new SecureError(
                        "SC_UNSUPPORTED_VERSION", "Envelope version is unsupported");
            }
            if (!session.getSuite().equals(envelope.getString("suite"))
                    || !session.getKeyId().equals(envelope.getString("kid"))
                    || !session.getSessionId().equals(envelope.getString("sid"))) {
                throw new SecureError("SC_UNKNOWN_SESSION", "Envelope session does not match");
            }
            long timestamp = envelope.getLong("ts");
            long sequence = envelope.getLong("seq");
            String requestId = envelope.getString("rid");
            String method = normalizeMethod(envelope.getString("m"));
            String path = normalizePath(envelope.getString("p"));
            String contentType = normalizeContentType(envelope.getString("cty"));
            int status = envelope.getInt("st");
            if (sequence != expectedSequence
                    || !requestId.equals(expectedRequestId)
                    || !method.equals("POST")
                    || !path.equals(MESSAGE_ENDPOINT)
                    || !contentType.equals(PROTECTED_MEDIA_TYPE)) {
                throw new SecureError(
                        "SC_ROUTE_MISMATCH", "Response does not match the request");
            }
            long now = clock.epochMillis();
            if (timestamp < now - allowedClockSkewMillis
                    || timestamp > now + allowedClockSkewMillis) {
                throw new SecureError(
                        "SC_REQUEST_EXPIRED", "Response is outside the accepted time window");
            }
            if (status < 100 || status > 599) {
                throw new SecureError(
                        "SC_INVALID_ENVELOPE", "Response status is invalid");
            }
            byte[] expectedNonce = nonce(session.getResponseNoncePrefix(), sequence);
            byte[] receivedNonce = decode(envelope.getString("nonce"));
            if (!constantTimeEquals(expectedNonce, receivedNonce)) {
                throw new SecureError("SC_INVALID_ENVELOPE", "Response nonce is invalid");
            }
            byte[] plaintext = crypt(
                    Cipher.DECRYPT_MODE,
                    session.getResponseKey(),
                    receivedNonce,
                    aad("response", envelope),
                    decode(envelope.getString("ct")));
            return new DecodedResponse(status, contentType, plaintext);
        } catch (SecureError error) {
            throw error;
        } catch (JSONException | IllegalArgumentException exception) {
            throw new SecureError(
                    "SC_INVALID_ENVELOPE", "Response envelope is invalid",
                    0, null, exception);
        }
    }

    private JSONObject envelope(
            long timestamp,
            long sequence,
            String requestId,
            String method,
            String path,
            String contentType,
            int status,
            String nonce,
            String ciphertext) throws SecureError {
        try {
            return new JSONObject()
                    .put("v", 1)
                    .put("suite", session.getSuite())
                    .put("kid", session.getKeyId())
                    .put("sid", session.getSessionId())
                    .put("ts", timestamp)
                    .put("seq", sequence)
                    .put("rid", requestId)
                    .put("m", method)
                    .put("p", path)
                    .put("cty", contentType)
                    .put("st", status)
                    .put("nonce", nonce)
                    .put("ct", ciphertext);
        } catch (JSONException exception) {
            throw new SecureError(
                    "SC_INTERNAL_ERROR", "Unable to encode envelope", 0, null, exception);
        }
    }

    private static byte[] crypt(
            int mode,
            javax.crypto.SecretKey key,
            byte[] nonce,
            byte[] aad,
            byte[] input) throws SecureError {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad);
            return cipher.doFinal(input);
        } catch (AEADBadTagException exception) {
            throw new SecureError(
                    "SC_AUTHENTICATION_FAILED", "Response authentication failed",
                    0, null, exception);
        } catch (GeneralSecurityException exception) {
            throw new SecureError(
                    "SC_CRYPTO_FAILED", "Cryptographic operation failed",
                    0, null, exception);
        }
    }

    private static byte[] protectedPayload(SecureRequest request) throws SecureError {
        try {
            JSONObject headers = new JSONObject();
            for (java.util.Map.Entry<String, String> value
                    : request.getProtectedHeaders().entrySet()) {
                String name = value.getKey().toLowerCase(Locale.ROOT);
                if (!name.matches("[a-z0-9-]{1,64}")
                        || value.getValue().contains("\r")
                        || value.getValue().contains("\n")) {
                    throw new SecureError(
                            "SC_INVALID_ENVELOPE", "Protected header is invalid");
                }
                headers.put(name, value.getValue());
            }
            return new JSONObject()
                    .put("method", normalizeMethod(request.getMethod()))
                    .put("path", normalizePath(request.getLogicalPath()))
                    .put("contentType", normalizeContentType(request.getContentType()))
                    .put("headers", headers)
                    .put("body", encode(request.getBody()))
                    .toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException exception) {
            throw new SecureError(
                    "SC_INTERNAL_ERROR", "Unable to encode protected payload",
                    0, null, exception);
        }
    }

    private static byte[] aad(String direction, JSONObject envelope)
            throws SecureError {
        try {
            String value = "SC1\n"
                    + direction + "\n"
                    + envelope.getString("suite") + "\n"
                    + envelope.getString("kid") + "\n"
                    + envelope.getString("sid") + "\n"
                    + envelope.getLong("ts") + "\n"
                    + envelope.getLong("seq") + "\n"
                    + envelope.getString("rid") + "\n"
                    + envelope.getString("m") + "\n"
                    + envelope.getString("p") + "\n"
                    + envelope.getString("cty");
            if ("response".equals(direction)) {
                value += "\n" + envelope.getInt("st");
            }
            return value.getBytes(StandardCharsets.UTF_8);
        } catch (JSONException exception) {
            throw new SecureError(
                    "SC_INVALID_ENVELOPE", "Envelope AAD is invalid",
                    0, null, exception);
        }
    }

    private static byte[] nonce(byte[] prefix, long sequence) {
        return ByteBuffer.allocate(12).put(prefix).putLong(sequence).array();
    }

    private static String encode(byte[] value) {
        return Base64.encodeToString(
                value, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    private static byte[] decode(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid Base64URL");
        }
        return Base64.decode(value, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    private static boolean constantTimeEquals(byte[] left, byte[] right) {
        if (left.length != right.length) {
            return false;
        }
        int difference = 0;
        for (int index = 0; index < left.length; index += 1) {
            difference |= left[index] ^ right[index];
        }
        return difference == 0;
    }

    private static void validateFields(JSONObject envelope) throws JSONException {
        Set<String> actual = new HashSet<>();
        java.util.Iterator<String> keys = envelope.keys();
        while (keys.hasNext()) {
            actual.add(keys.next());
        }
        if (!FIELDS.equals(actual)) {
            throw new JSONException("Unexpected envelope fields");
        }
    }

    static String normalizeMethod(String value) {
        String method = value == null ? "GET" : value.toUpperCase(Locale.ROOT);
        if (!method.matches("[A-Z]{3,16}")) {
            throw new IllegalArgumentException("Invalid method");
        }
        return method;
    }

    static String normalizeContentType(String value) {
        String contentType = value == null || value.trim().isEmpty()
                ? "application/octet-stream"
                : value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!contentType.matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")) {
            throw new IllegalArgumentException("Invalid content type");
        }
        return contentType;
    }

    static String normalizePath(String value) {
        if (value == null || !value.startsWith("/") || value.contains("#")
                || value.contains("://") || value.contains("\r")
                || value.contains("\n") || value.contains(" ")
                || value.indexOf('?') != value.lastIndexOf('?')) {
            throw new IllegalArgumentException("Invalid path");
        }
        String[] split = value.split("\\?", 2);
        String path = uppercasePercentHex(split[0]);
        if (split.length == 1 || split[1].isEmpty()) {
            return path;
        }
        List<String> pairs = new ArrayList<>();
        for (String pair : split[1].split("&", -1)) {
            if (!pair.isEmpty()) {
                pairs.add(uppercasePercentHex(pair));
            }
        }
        Collections.sort(pairs, new Comparator<String>() {
            @Override
            public int compare(String left, String right) {
                int name = queryName(left).compareTo(queryName(right));
                return name == 0
                        ? queryValue(left).compareTo(queryValue(right))
                        : name;
            }
        });
        if (pairs.isEmpty()) {
            return path;
        }
        StringBuilder query = new StringBuilder(path).append('?');
        for (int index = 0; index < pairs.size(); index += 1) {
            if (index > 0) {
                query.append('&');
            }
            query.append(pairs.get(index));
        }
        return query.toString();
    }

    private static String uppercasePercentHex(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index += 1) {
            char current = value.charAt(index);
            if (current == '%') {
                if (index + 2 >= value.length()
                        || Character.digit(value.charAt(index + 1), 16) < 0
                        || Character.digit(value.charAt(index + 2), 16) < 0) {
                    throw new IllegalArgumentException("Invalid percent encoding");
                }
                result.append('%')
                        .append(Character.toUpperCase(value.charAt(++index)))
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

    public interface Clock {
        long epochMillis();
    }

    public static final class EncodedRequest {
        private final String body;
        private final long sequence;
        private final String requestId;
        private final String method;
        private final String path;
        private final String contentType;

        EncodedRequest(
                String body, long sequence, String requestId,
                String method, String path, String contentType) {
            this.body = body;
            this.sequence = sequence;
            this.requestId = requestId;
            this.method = method;
            this.path = path;
            this.contentType = contentType;
        }

        public String getBody() { return body; }
        public long getSequence() { return sequence; }
        public String getRequestId() { return requestId; }
        public String getMethod() { return method; }
        public String getPath() { return path; }
        public String getContentType() { return contentType; }
    }

    public static final class DecodedResponse {
        private final int status;
        private final String contentType;
        private final byte[] body;

        DecodedResponse(int status, String contentType, byte[] body) {
            this.status = status;
            this.contentType = contentType;
            this.body = Arrays.copyOf(body, body.length);
        }

        public int getStatus() { return status; }
        public String getContentType() { return contentType; }
        public byte[] getBody() { return Arrays.copyOf(body, body.length); }
    }
}
