package com.coolxer.securecommunication;

import android.content.Context;
import android.os.Looper;
import android.util.Base64;

import com.coolxer.securecommunication.identity.AndroidIdentityKeyStore;
import com.coolxer.securecommunication.internal.protocol.InternationalHandshake;
import com.coolxer.securecommunication.internal.protocol.SecureEnvelopeCodec;
import com.coolxer.securecommunication.internal.protocol.SecureSession;
import com.coolxer.securecommunication.internal.protocol.SequenceStore;
import com.coolxer.securecommunication.internal.transport.SecureTransportException;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Call;
import okhttp3.ConnectionSpec;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.TlsVersion;

/** Unified protocol v1 client API. Business requests are never retried. */
public final class SecureClient {
    private static final String SUITE = SecureSession.INTERNATIONAL_SUITE;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final Object stateLock = new Object();
    private final SecureClientConfig config;
    private final OkHttpClient transport;
    private final HttpUrl baseUrl;
    private final IdentityStore identityStore;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private String enrollmentToken;
    private SecureSession session;
    private SecureCommunicationClient messageClient;
    private FutureTask<Void> initializeTask;
    private long nextSequence;
    private long generation;

    public SecureClient(Context context, SecureClientConfig config) throws SecureError {
        if (context == null || config == null) {
            throw new SecureError("SC_INVALID_CONFIGURATION", "Invalid client configuration");
        }
        this.config = config;
        this.baseUrl = HttpUrl.parse(config.getBaseUrl());
        this.identityStore = config.getIdentityStore() == null
                ? new AndroidIdentityKeyStore(context) : config.getIdentityStore();
        OkHttpClient base = config.getHttpClient() == null
                ? new OkHttpClient() : config.getHttpClient();
        OkHttpClient.Builder transportBuilder = base.newBuilder()
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .callTimeout(config.getRequestTimeoutMillis(), TimeUnit.MILLISECONDS);
        if ("https".equals(baseUrl.scheme())) {
            ConnectionSpec tls = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                    .tlsVersions(TlsVersion.TLS_1_2, TlsVersion.TLS_1_3).build();
            transportBuilder.connectionSpecs(Collections.singletonList(tls));
        } else {
            transportBuilder.connectionSpecs(Collections.singletonList(ConnectionSpec.CLEARTEXT));
        }
        this.transport = transportBuilder.build();
    }

    public void enroll(String token) throws SecureError {
        if ("H5".equals(config.getDeviceType())) {
            throw new SecureError("SC_ENROLLMENT_NOT_SUPPORTED", "H5 enrollment uses Origin policy");
        }
        if (token == null || token.trim().isEmpty()) {
            throw new SecureError("SC_ENROLLMENT_REQUIRED", "Enrollment token is required");
        }
        synchronized (stateLock) { enrollmentToken = token; }
    }

    public void initialize() throws SecureError {
        rejectMainThread();
        FutureTask<Void> task;
        synchronized (stateLock) {
            if (session != null && System.currentTimeMillis() < session.getExpiresAtEpochMillis()) {
                return;
            }
            if (initializeTask == null) {
                final long expectedGeneration = generation;
                initializeTask = new FutureTask<>(() -> {
                    performInitialize(expectedGeneration);
                    return null;
                });
                executor.execute(initializeTask);
            }
            task = initializeTask;
        }
        try {
            task.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SecureError("SC_REQUEST_CANCELLED", "Initialization wait was cancelled",
                    0, null, exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof SecureError) throw (SecureError) cause;
            throw handshakeFailure(cause);
        } finally {
            synchronized (stateLock) {
                if (initializeTask == task && task.isDone()) initializeTask = null;
            }
        }
    }

    private void performInitialize(long expectedGeneration) throws SecureError {
        String token;
        synchronized (stateLock) { token = enrollmentToken; }
        try {
            InstallationIdentity identity = identityStore.loadOrCreate(config.getAppId());
            KeyPair ephemeral = InternationalHandshake.createEphemeralKeyPair();
            JSONObject start = new JSONObject()
                    .put("v", 1).put("suite", SUITE).put("appId", config.getAppId())
                    .put("deviceId", identity.deviceId()).put("deviceType", config.getDeviceType())
                    .put("clientEphemeralPublicKey", encode(ephemeral.getPublic().getEncoded()))
                    .put("installationPublicKey", encode(identity.publicKeySpki()))
                    .put("enrollmentToken", token == null ? JSONObject.NULL : token)
                    .put("timestamp", System.currentTimeMillis());
            JSONObject response = postJson("/sc/v1/handshake", start);
            if (response.getInt("v") != 1 || !SUITE.equals(response.getString("suite"))) {
                throw handshakeFailure(null);
            }
            String keyId = response.getString("kid");
            byte[] pinned = decode(config.getServerTrustAnchors().get(keyId));
            byte[] serverIdentity = decode(response.getString("serverIdentityPublicKey"));
            if (pinned == null || serverIdentity == null
                    || !MessageDigest.isEqual(pinned, serverIdentity)) {
                throw handshakeFailure(null);
            }
            byte[] serverEphemeral = decode(response.getString("serverEphemeralPublicKey"));
            String sessionId = response.getString("sid");
            long createdAt = response.getLong("createdAt");
            long expiresAt = response.getLong("expiresAt");
            byte[] transcriptHash = transcriptHash(start, identity.deviceId(), serverIdentity,
                    serverEphemeral, keyId, sessionId, createdAt, expiresAt);
            PublicKey identityKey = ecPublicKey(serverIdentity);
            if (!InternationalHandshake.verifyTranscriptSignature(
                    identityKey, transcriptHash, decode(response.getString("signature")))) {
                throw handshakeFailure(null);
            }
            SecureSession established = InternationalHandshake.deriveSession(
                    keyId, sessionId, ephemeral.getPrivate(), ecPublicKey(serverEphemeral),
                    transcriptHash, expiresAt);
            JSONObject completed = postJson("/sc/v1/handshake/finish", new JSONObject()
                    .put("kid", keyId).put("sid", sessionId)
                    .put("proof", encode(identity.sign(transcriptHash))));
            if (!completed.getBoolean("active")) throw handshakeFailure(null);
            synchronized (stateLock) {
                if (generation != expectedGeneration) {
                    throw new SecureError("SC_REQUEST_CANCELLED", "Initialization was invalidated");
                }
                session = established;
                nextSequence = 0;
                SequenceStore sequences = ignored -> {
                    synchronized (stateLock) {
                        if (nextSequence == Long.MAX_VALUE) {
                            throw new SecureError("SC_SEQUENCE_EXHAUSTED", "Session sequence is exhausted");
                        }
                        return ++nextSequence;
                    }
                };
                SecureEnvelopeCodec codec = new SecureEnvelopeCodec(established, sequences,
                        System::currentTimeMillis, config.getAllowedClockSkewMillis());
                messageClient = new SecureCommunicationClient(baseUrl, transport, codec);
                if (equalToken(enrollmentToken, token)) enrollmentToken = null;
            }
        } catch (SecureError error) {
            clearFailedSession(expectedGeneration);
            throw error;
        } catch (SocketTimeoutException exception) {
            clearFailedSession(expectedGeneration);
            throw new SecureError("SC_REQUEST_TIMEOUT", "Secure handshake timed out",
                    0, null, exception);
        } catch (Exception exception) {
            clearFailedSession(expectedGeneration);
            throw handshakeFailure(exception);
        }
    }

    public SecureResponse request(SecureRequest request) throws SecureError {
        return executeRequest(request, null);
    }

    public SecureCall newCall(SecureRequest request) {
        if (request == null) throw new IllegalArgumentException("request is required");
        return new RealSecureCall(request);
    }

    private SecureResponse executeRequest(SecureRequest request, RealSecureCall owner)
            throws SecureError {
        if (request == null) throw new IllegalArgumentException("request is required");
        rejectMainThread();
        initialize();
        SecureCommunicationClient client;
        synchronized (stateLock) { client = messageClient; }
        if (owner != null && owner.isCanceled()) {
            throw new SecureError("SC_REQUEST_CANCELLED", "Secure request was cancelled");
        }
        SecureRequest requestForCall = request.getRequestId() == null
                ? new SecureRequest(request.getMethod(), request.getLogicalPath(),
                request.getContentType(), request.getProtectedHeaders(), request.getBody(),
                java.util.UUID.randomUUID().toString())
                : request;
        Call call = client.newCall(requestForCall);
        if (owner != null) owner.setActiveCall(call);
        try (Response response = call.execute()) {
            ResponseBody responseBody = response.body();
            byte[] bytes = responseBody == null ? new byte[0] : responseBody.bytes();
            return new SecureResponse(response.code(),
                    response.header("Content-Type", "application/octet-stream"), bytes);
        } catch (SecureTransportException exception) {
            SecureError error = exception.getSecureError();
            if ("SC_UNKNOWN_SESSION".equals(error.getCode())) closeSession();
            throw error;
        } catch (SocketTimeoutException exception) {
            throw new SecureError("SC_REQUEST_TIMEOUT", "Secure request timed out",
                    0, null, exception);
        } catch (InterruptedIOException exception) {
            String code = owner != null && owner.isCanceled()
                    ? "SC_REQUEST_CANCELLED" : "SC_REQUEST_TIMEOUT";
            throw new SecureError(code, "Secure request did not complete", 0, null, exception);
        } catch (IOException exception) {
            if (owner != null && owner.isCanceled()) {
                throw new SecureError("SC_REQUEST_CANCELLED", "Secure request was cancelled",
                        0, null, exception);
            }
            throw new SecureError("SC_NETWORK_FAILED", "Network request failed",
                    0, null, exception);
        } finally {
            if (owner != null) owner.setActiveCall(null);
        }
    }

    public void closeSession() {
        synchronized (stateLock) {
            generation++;
            session = null;
            messageClient = null;
            nextSequence = 0;
        }
    }

    private JSONObject postJson(String path, JSONObject body) throws Exception {
        HttpUrl endpoint = baseUrl.resolve(path);
        Request request = new Request.Builder().url(endpoint)
                .post(RequestBody.create(body.toString(), JSON))
                .header("Accept", "application/json").build();
        try (Response response = transport.newCall(request).execute()) {
            String content = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                String code = "SC_HANDSHAKE_FAILED";
                String traceId = null;
                try {
                    JSONObject error = new JSONObject(content);
                    code = error.optString("code", code);
                    traceId = error.optString("traceId", null);
                } catch (Exception ignored) { }
                throw new SecureError(code, "Secure handshake failed",
                        response.code(), traceId, null);
            }
            return new JSONObject(content);
        }
    }

    private byte[] transcriptHash(JSONObject request, String deviceId, byte[] serverIdentity,
            byte[] serverEphemeral, String keyId, String sessionId,
            long createdAt, long expiresAt) throws Exception {
        String transcript = String.join("\n", "SC1-HANDSHAKE", "1", SUITE,
                config.getAppId(), deviceId, config.getDeviceType(),
                request.getString("clientEphemeralPublicKey"),
                request.getString("installationPublicKey"), encode(serverIdentity),
                encode(serverEphemeral), keyId, sessionId, String.valueOf(createdAt),
                String.valueOf(expiresAt));
        return MessageDigest.getInstance("SHA-256")
                .digest(transcript.getBytes(StandardCharsets.UTF_8));
    }

    private void clearFailedSession(long expectedGeneration) {
        synchronized (stateLock) {
            if (generation == expectedGeneration) {
                session = null;
                messageClient = null;
                nextSequence = 0;
            }
        }
    }

    private static boolean equalToken(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static void rejectMainThread() throws SecureError {
        if (Looper.myLooper() != null && Looper.myLooper() == Looper.getMainLooper()) {
            throw new SecureError("SC_MAIN_THREAD_NETWORK", "Network calls are forbidden on the main thread");
        }
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

    private final class RealSecureCall implements SecureCall {
        private final SecureRequest request;
        private final AtomicBoolean executed = new AtomicBoolean();
        private volatile boolean canceled;
        private volatile Call activeCall;
        private volatile Thread runner;

        RealSecureCall(SecureRequest request) { this.request = request; }

        @Override public SecureResponse execute() throws SecureError {
            if (!executed.compareAndSet(false, true)) {
                throw new IllegalStateException("SecureCall may only be executed once");
            }
            if (canceled) throw new SecureError("SC_REQUEST_CANCELLED", "Secure request was cancelled");
            runner = Thread.currentThread();
            try { return executeRequest(request, this); }
            finally { runner = null; }
        }

        @Override public void enqueue(final Callback callback) {
            if (callback == null) throw new IllegalArgumentException("callback is required");
            executor.execute(() -> {
                try { callback.onResponse(execute()); }
                catch (SecureError error) { callback.onFailure(error); }
                catch (RuntimeException error) {
                    callback.onFailure(new SecureError("SC_NETWORK_FAILED", "Secure call failed",
                            0, null, error));
                }
            });
        }

        @Override public void cancel() {
            canceled = true;
            Call call = activeCall;
            if (call != null) call.cancel();
            Thread thread = runner;
            if (thread != null) thread.interrupt();
        }

        @Override public boolean isCanceled() { return canceled; }
        void setActiveCall(Call call) {
            activeCall = call;
            if (canceled && call != null) call.cancel();
        }
    }
}
