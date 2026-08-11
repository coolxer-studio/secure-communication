package com.coolxer.securecommunication;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import com.coolxer.securecommunication.identity.AndroidIdentityKeyStore;
import com.coolxer.securecommunication.protocol.InternationalHandshake;
import com.coolxer.securecommunication.protocol.SecureEnvelopeCodec;
import com.coolxer.securecommunication.protocol.SecureSession;
import com.coolxer.securecommunication.protocol.SequenceStore;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.ConnectionSpec;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.TlsVersion;

/** High-level protocol v1 client. It never retries business requests. */
public final class SecureClient {
    public static final String SUITE = SecureSession.INTERNATIONAL_SUITE;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final Context context;
    private final Config config;
    private final OkHttpClient transport;
    private final HttpUrl baseUrl;
    private final KeyPair installationIdentity;
    private final String deviceId;
    private String enrollmentToken;
    private SecureSession session;
    private SecureCommunicationClient messageClient;
    private long nextSequence;

    public SecureClient(Context context, Config config) throws SecureError {
        if (context == null || config == null || config.appId == null
                || config.appId.isEmpty() || config.serverTrustAnchors.isEmpty()) {
            throw new SecureError("SC_INVALID_CONFIGURATION", "Invalid client configuration");
        }
        HttpUrl parsed = HttpUrl.parse(config.baseUrl);
        if (parsed == null || !"https".equals(parsed.scheme())) {
            throw new SecureError("SC_INVALID_CONFIGURATION", "baseUrl must use HTTPS");
        }
        this.context = context.getApplicationContext();
        this.config = config;
        this.baseUrl = parsed;
        ConnectionSpec tls = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                .tlsVersions(TlsVersion.TLS_1_2, TlsVersion.TLS_1_3).build();
        this.transport = (config.httpClient == null ? new OkHttpClient() : config.httpClient)
                .newBuilder()
                .retryOnConnectionFailure(false)
                .connectTimeout(config.connectTimeoutMillis, TimeUnit.MILLISECONDS)
                .readTimeout(config.readTimeoutMillis, TimeUnit.MILLISECONDS)
                .writeTimeout(config.writeTimeoutMillis, TimeUnit.MILLISECONDS)
                .connectionSpecs(Collections.singletonList(tls))
                .build();
        try {
            this.installationIdentity = new AndroidIdentityKeyStore().getOrCreate(
                    "sc1.installation." + config.appId);
        } catch (Exception exception) {
            throw new SecureError("SC_IDENTITY_FAILED", "Installation identity is unavailable",
                    0, null, exception);
        }
        SharedPreferences preferences = this.context.getSharedPreferences(
                "secure-communication-v1", Context.MODE_PRIVATE);
        String storedDeviceId = preferences.getString("device." + config.appId, null);
        if (storedDeviceId == null) {
            storedDeviceId = UUID.randomUUID().toString();
            if (!preferences.edit().putString("device." + config.appId, storedDeviceId).commit()) {
                throw new SecureError("SC_IDENTITY_FAILED", "Device identity could not be persisted");
            }
        }
        this.deviceId = storedDeviceId;
    }

    public synchronized void enroll(String token) throws SecureError {
        if (token == null || token.trim().isEmpty()) {
            throw new SecureError("SC_ENROLLMENT_REQUIRED", "Enrollment token is required");
        }
        enrollmentToken = token;
    }

    public synchronized void initialize() throws SecureError {
        if (session != null && System.currentTimeMillis() < session.getExpiresAtEpochMillis()) {
            return;
        }
        KeyPair ephemeral = InternationalHandshake.createEphemeralKeyPair();
        long timestamp = System.currentTimeMillis();
        JSONObject start = new JSONObject();
        try {
            start.put("v", 1).put("suite", SUITE).put("appId", config.appId)
                    .put("deviceId", deviceId).put("deviceType", config.deviceType)
                    .put("clientEphemeralPublicKey", encode(ephemeral.getPublic().getEncoded()))
                    .put("installationPublicKey", encode(installationIdentity.getPublic().getEncoded()))
                    .put("enrollmentToken", enrollmentToken == null ? JSONObject.NULL : enrollmentToken)
                    .put("timestamp", timestamp);
            JSONObject response = postJson("/sc/v1/handshake", start);
            if (response.getInt("v") != 1 || !SUITE.equals(response.getString("suite"))) {
                throw handshakeFailure(null);
            }
            String keyId = response.getString("kid");
            byte[] pinned = decode(config.serverTrustAnchors.get(keyId));
            byte[] serverIdentity = decode(response.getString("serverIdentityPublicKey"));
            if (pinned == null || !MessageDigest.isEqual(pinned, serverIdentity)) {
                throw handshakeFailure(null);
            }
            byte[] serverEphemeral = decode(response.getString("serverEphemeralPublicKey"));
            String sessionId = response.getString("sid");
            long createdAt = response.getLong("createdAt");
            long expiresAt = response.getLong("expiresAt");
            byte[] transcriptHash = transcriptHash(start, serverIdentity, serverEphemeral,
                    keyId, sessionId, createdAt, expiresAt);
            PublicKey identityKey = ecPublicKey(serverIdentity);
            if (!InternationalHandshake.verifyTranscriptSignature(
                    identityKey, transcriptHash, decode(response.getString("signature")))) {
                throw handshakeFailure(null);
            }
            SecureSession established = InternationalHandshake.deriveSession(
                    keyId, sessionId, ephemeral.getPrivate(), ecPublicKey(serverEphemeral),
                    transcriptHash, expiresAt);
            JSONObject finish = new JSONObject()
                    .put("kid", keyId).put("sid", sessionId)
                    .put("proof", encode(signP1363(installationIdentity, transcriptHash)));
            JSONObject completed = postJson("/sc/v1/handshake/finish", finish);
            if (!completed.getBoolean("active")) {
                throw handshakeFailure(null);
            }
            enrollmentToken = null;
            session = established;
            nextSequence = 0;
            SequenceStore sequences = ignored -> {
                synchronized (SecureClient.this) {
                    if (nextSequence == Long.MAX_VALUE) {
                        throw new SecureError("SC_SEQUENCE_EXHAUSTED", "Session sequence is exhausted");
                    }
                    return ++nextSequence;
                }
            };
            SecureEnvelopeCodec codec = new SecureEnvelopeCodec(
                    established, sequences, System::currentTimeMillis, config.allowedClockSkewMillis);
            messageClient = new SecureCommunicationClient(baseUrl, transport, codec);
        } catch (SecureError error) {
            closeSession();
            throw error;
        } catch (Exception exception) {
            closeSession();
            throw handshakeFailure(exception);
        }
    }

    public SecureResponse request(String method, String logicalPath,
            Map<String, String> protectedHeaders, byte[] body, String requestId)
            throws SecureError {
        SecureCommunicationClient client;
        synchronized (this) {
            initialize();
            client = messageClient;
        }
        Map<String, String> headers = new LinkedHashMap<>(protectedHeaders == null
                ? Collections.emptyMap() : protectedHeaders);
        SecureRequest request = new SecureRequest(method, logicalPath,
                "application/json", headers, body, requestId);
        try (Response response = client.newCall(request).execute()) {
            ResponseBody responseBody = response.body();
            byte[] bytes = responseBody == null ? new byte[0] : responseBody.bytes();
            return new SecureResponse(response.code(),
                    response.header("Content-Type", "application/octet-stream"), bytes);
        } catch (IOException exception) {
            throw new SecureError("SC_NETWORK_FAILED", "Network request failed",
                    0, null, exception);
        }
    }

    public synchronized void closeSession() {
        session = null;
        messageClient = null;
        nextSequence = 0;
    }

    private JSONObject postJson(String path, JSONObject body) throws Exception {
        HttpUrl endpoint = baseUrl.resolve(path);
        Request request = new Request.Builder().url(endpoint)
                .post(RequestBody.create(body.toString(), JSON))
                .header("Accept", "application/json").build();
        try (Response response = transport.newCall(request).execute()) {
            String content = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw handshakeFailure(null);
            }
            return new JSONObject(content);
        }
    }

    private byte[] transcriptHash(JSONObject request, byte[] serverIdentity,
            byte[] serverEphemeral, String keyId, String sessionId,
            long createdAt, long expiresAt) throws Exception {
        String transcript = String.join("\n", "SC1-HANDSHAKE", "1", SUITE,
                config.appId, deviceId, config.deviceType,
                request.getString("clientEphemeralPublicKey"),
                request.getString("installationPublicKey"), encode(serverIdentity),
                encode(serverEphemeral), keyId, sessionId, String.valueOf(createdAt),
                String.valueOf(expiresAt));
        return MessageDigest.getInstance("SHA-256")
                .digest(transcript.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] signP1363(KeyPair identity, byte[] hash) throws Exception {
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(identity.getPrivate());
        signer.update(hash);
        return derToP1363(signer.sign());
    }

    private static byte[] derToP1363(byte[] der) {
        if (der.length < 8 || der[0] != 0x30) throw new IllegalArgumentException("Invalid ECDSA signature");
        int offset = 2;
        if (der[offset++] != 0x02) throw new IllegalArgumentException("Invalid ECDSA signature");
        int rLength = der[offset++] & 0xff;
        byte[] r = Arrays.copyOfRange(der, offset, offset + rLength);
        offset += rLength;
        if (der[offset++] != 0x02) throw new IllegalArgumentException("Invalid ECDSA signature");
        int sLength = der[offset++] & 0xff;
        byte[] s = Arrays.copyOfRange(der, offset, offset + sLength);
        byte[] result = new byte[64];
        copyInteger(r, result, 0);
        copyInteger(s, result, 32);
        return result;
    }

    private static void copyInteger(byte[] source, byte[] target, int offset) {
        int first = source.length > 32 && source[0] == 0 ? 1 : 0;
        int length = source.length - first;
        if (length > 32) throw new IllegalArgumentException("Invalid ECDSA integer");
        System.arraycopy(source, first, target, offset + 32 - length, length);
    }

    private static PublicKey ecPublicKey(byte[] encoded) throws Exception {
        return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(encoded));
    }

    private static String encode(byte[] value) {
        return Base64.encodeToString(value, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static byte[] decode(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]+")) return null;
        return Base64.decode(value, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static SecureError handshakeFailure(Throwable cause) {
        return new SecureError("SC_HANDSHAKE_FAILED", "Secure handshake failed", 0, null, cause);
    }

    public static final class Config {
        private final String baseUrl;
        private final String appId;
        private final String deviceType;
        private final Map<String, String> serverTrustAnchors;
        private final OkHttpClient httpClient;
        private final long connectTimeoutMillis;
        private final long readTimeoutMillis;
        private final long writeTimeoutMillis;
        private final long allowedClockSkewMillis;

        public Config(String baseUrl, String appId, Map<String, String> serverTrustAnchors) {
            this(baseUrl, appId, "ANDROID", serverTrustAnchors, null,
                    10_000, 15_000, 15_000, 120_000);
        }

        public Config(String baseUrl, String appId, String deviceType,
                Map<String, String> serverTrustAnchors, OkHttpClient httpClient,
                long connectTimeoutMillis, long readTimeoutMillis,
                long writeTimeoutMillis, long allowedClockSkewMillis) {
            this.baseUrl = baseUrl;
            this.appId = appId;
            this.deviceType = deviceType == null ? "ANDROID" : deviceType.toUpperCase();
            this.serverTrustAnchors = Collections.unmodifiableMap(
                    new LinkedHashMap<>(serverTrustAnchors == null
                            ? Collections.emptyMap() : serverTrustAnchors));
            this.httpClient = httpClient;
            this.connectTimeoutMillis = connectTimeoutMillis;
            this.readTimeoutMillis = readTimeoutMillis;
            this.writeTimeoutMillis = writeTimeoutMillis;
            this.allowedClockSkewMillis = allowedClockSkewMillis;
        }
    }
}
