package com.coolxer.securecommunication.internal;

import com.coolxer.securecommunication.ExecutionOptions;
import com.coolxer.securecommunication.InstallationIdentity;
import com.coolxer.securecommunication.SecureClient;
import com.coolxer.securecommunication.SecureClientConfig;
import com.coolxer.securecommunication.SecureError;
import com.coolxer.securecommunication.SecureRequest;
import com.coolxer.securecommunication.SecureResponse;

import javax.net.ssl.SSLParameters;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PublicKey;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

public final class DefaultSecureClient implements SecureClient {
    private static final int MAX_HANDSHAKE_RESPONSE_BYTES = 256 * 1024;
    private static final int MAX_ENVELOPE_RESPONSE_BYTES = 2 * 1024 * 1024;
    private final Object stateLock = new Object();
    private final SecureClientConfig config;
    private final HttpClient transport;
    private String enrollmentToken;
    private SecureSession session;
    private CompletableFuture<Void> initialization;

    public DefaultSecureClient(SecureClientConfig config) throws SecureError {
        if (config == null) {
            throw new SecureError("SC_INVALID_CONFIGURATION", "Client configuration is required");
        }
        this.config = config;
        HttpClient supplied = config.getHttpClient();
        if (supplied != null && supplied.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new SecureError("SC_INVALID_CONFIGURATION", "HTTP redirects must be disabled");
        }
        if (supplied != null) {
            this.transport = supplied;
        } else {
            SSLParameters tls = new SSLParameters();
            tls.setProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
            this.transport = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(config.getRequestTimeout())
                    .sslParameters(tls)
                    .version(HttpClient.Version.HTTP_2)
                    .build();
        }
    }

    @Override
    public void enroll(String token) throws SecureError {
        if (token == null || token.isBlank()) {
            throw new SecureError("SC_ENROLLMENT_REQUIRED", "Enrollment token is required");
        }
        synchronized (stateLock) {
            enrollmentToken = token;
        }
    }

    @Override public void initialize() throws SecureError { initialize(ExecutionOptions.defaults()); }

    @Override
    public void initialize(ExecutionOptions options) throws SecureError {
        await(initializeAsync(options));
    }

    @Override public CompletableFuture<Void> initializeAsync() {
        return initializeAsync(ExecutionOptions.defaults());
    }

    @Override
    public CompletableFuture<Void> initializeAsync(ExecutionOptions options) {
        return mirror(initializeShared(options == null ? ExecutionOptions.defaults() : options));
    }

    @Override public SecureResponse request(SecureRequest request) throws SecureError {
        return request(request, ExecutionOptions.defaults());
    }

    @Override
    public SecureResponse request(SecureRequest request, ExecutionOptions options) throws SecureError {
        return await(requestAsync(request, options));
    }

    @Override public CompletableFuture<SecureResponse> requestAsync(SecureRequest request) {
        return requestAsync(request, ExecutionOptions.defaults());
    }

    @Override
    public CompletableFuture<SecureResponse> requestAsync(
            SecureRequest request, ExecutionOptions options) {
        if (request == null) return failed(new SecureError(
                "SC_INVALID_ENVELOPE", "Secure request is required"));
        ExecutionOptions effective = options == null ? ExecutionOptions.defaults() : options;
        OperationFuture<SecureResponse> result = new OperationFuture<>();
        CompletableFuture<Void> ready = initializeShared(effective);
        ready.whenComplete((ignored, initializationFailure) -> {
            if (result.isCancelled()) return;
            if (initializationFailure != null) {
                result.completeExceptionally(asSecureError(initializationFailure));
                return;
            }
            SecureSession current;
            synchronized (stateLock) { current = session; }
            if (current == null) {
                result.completeExceptionally(new SecureError(
                        "SC_UNKNOWN_SESSION", "Secure session is unavailable"));
                return;
            }
            CompletableFuture<SecureResponse> message = sendMessage(current, request, effective);
            result.setActive(message);
            message.whenComplete((response, failure) -> {
                if (failure == null) result.complete(response);
                else result.completeExceptionally(asSecureError(failure));
            });
        });
        return result;
    }

    @Override
    public void closeSession() {
        synchronized (stateLock) {
            session = null;
        }
    }

    private CompletableFuture<Void> initializeShared(ExecutionOptions options) {
        synchronized (stateLock) {
            if (session != null && config.getClock().millis() < session.expiresAt) {
                return CompletableFuture.completedFuture(null);
            }
            if (initialization != null) return initialization;
            String token = enrollmentToken;
            CompletableFuture<Void> created = CompletableFuture
                    .supplyAsync(() -> loadIdentity())
                    .thenCompose(identity -> establish(identity, token, options))
                    .thenAccept(established -> {
                        synchronized (stateLock) {
                            session = established;
                            if (Objects.equals(enrollmentToken, token)) enrollmentToken = null;
                        }
                    });
            initialization = created;
            created.whenComplete((ignored, failure) -> {
                synchronized (stateLock) {
                    if (initialization == created) initialization = null;
                    if (failure != null) session = null;
                }
            });
            return created;
        }
    }

    private InstallationIdentity loadIdentity() {
        try {
            return config.getIdentityStore().loadOrCreate(config.getAppId());
        } catch (SecureError error) {
            throw new CompletionException(error);
        }
    }

    private CompletableFuture<SecureSession> establish(
            InstallationIdentity identity, String token, ExecutionOptions options) {
        final KeyPair ephemeral;
        final byte[] installation;
        try {
            if (!validId(identity.deviceId())) throw handshakeFailure(null);
            ephemeral = CryptoSupport.generateP256();
            installation = identity.publicKeySpki();
            CryptoSupport.parseP256Public(installation);
        } catch (SecureError error) {
            return failed(handshakeFailure(error));
        }
        long timestamp = config.getClock().millis();
        ProtocolModels.HandshakeStartRequest startRequest =
                new ProtocolModels.HandshakeStartRequest(
                        ProtocolCodec.VERSION, ProtocolCodec.SUITE,
                        config.getAppId(), identity.deviceId(), config.getDeviceType(),
                        CryptoSupport.encode(ephemeral.getPublic().getEncoded()),
                        CryptoSupport.encode(installation), token, timestamp);
        return postJson("/sc/v1/handshake", startRequest,
                ProtocolModels.HandshakeStartResponse.class, options, "SC_HANDSHAKE_FAILED")
                .thenCompose(start -> finishHandshake(
                        identity, ephemeral, installation, startRequest, start, options));
    }

    private CompletableFuture<SecureSession> finishHandshake(
            InstallationIdentity identity, KeyPair ephemeral, byte[] installation,
            ProtocolModels.HandshakeStartRequest request,
            ProtocolModels.HandshakeStartResponse response,
            ExecutionOptions options) {
        try {
            validateHandshakeResponse(response);
            String pinnedValue = config.getServerTrustAnchors().get(response.kid());
            if (pinnedValue == null) throw handshakeFailure(null);
            byte[] serverIdentity = CryptoSupport.decode(response.serverIdentityPublicKey(), false);
            byte[] pinned = CryptoSupport.decode(pinnedValue, false);
            if (!CryptoSupport.constantTimeEquals(serverIdentity, pinned)) throw handshakeFailure(null);
            byte[] serverEphemeral = CryptoSupport.decode(response.serverEphemeralPublicKey(), false);
            byte[] transcriptHash = transcriptHash(
                    request, installation, serverIdentity, serverEphemeral, response);
            PublicKey signingKey = CryptoSupport.parseP256Public(serverIdentity);
            if (!CryptoSupport.verifyP1363(signingKey, transcriptHash,
                    CryptoSupport.decode(response.signature(), false))) {
                throw handshakeFailure(null);
            }
            byte[] material = CryptoSupport.deriveMaterial(
                    ephemeral.getPrivate(), CryptoSupport.parseP256Public(serverEphemeral),
                    transcriptHash, ProtocolCodec.SUITE, response.sid());
            byte[] proof = identity.sign(transcriptHash);
            if (proof == null || proof.length != 64) throw handshakeFailure(null);
            ProtocolModels.HandshakeFinishRequest finish =
                    new ProtocolModels.HandshakeFinishRequest(
                            response.kid(), response.sid(), CryptoSupport.encode(proof));
            return postJson("/sc/v1/handshake/finish", finish,
                    ProtocolModels.HandshakeFinishResponse.class, options, "SC_HANDSHAKE_FAILED")
                    .thenApply(completed -> {
                        try {
                            if (!completed.active() || completed.expiresAt() <= config.getClock().millis()
                                    || completed.expiresAt() > response.expiresAt()) {
                                throw new CompletionException(handshakeFailure(null));
                            }
                            return new SecureSession(
                                    response.kid(), response.sid(), material, completed.expiresAt());
                        } finally {
                            Arrays.fill(material, (byte) 0);
                        }
                    });
        } catch (SecureError | IllegalArgumentException error) {
            return failed(error instanceof SecureError secure
                    ? handshakeFailure(secure) : handshakeFailure(error));
        }
    }

    private CompletableFuture<SecureResponse> sendMessage(
            SecureSession current, SecureRequest request, ExecutionOptions options) {
        final ProtocolCodec.EncodedRequest encoded;
        try {
            ProtocolCodec codec = new ProtocolCodec(current, config.getClock(),
                    config.getAllowedClockSkew().toMillis());
            encoded = codec.encode(request);
            HttpRequest transportRequest = requestBuilder(
                    ProtocolCodec.MESSAGE_ENDPOINT, options)
                    .header("Content-Type", ProtocolCodec.ENVELOPE_MEDIA_TYPE)
                    .header("Accept", ProtocolCodec.ENVELOPE_MEDIA_TYPE)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(encoded.body())).build();
            return transport.sendAsync(transportRequest, HttpResponse.BodyHandlers.ofByteArray())
                    .handle((response, failure) -> {
                        if (failure != null) throw new CompletionException(networkFailure(failure));
                        if (response.body().length > MAX_ENVELOPE_RESPONSE_BYTES) {
                            throw new CompletionException(new SecureError(
                                    "SC_INVALID_ENVELOPE", "Secure response exceeds the size limit"));
                        }
                        String type = response.headers().firstValue("Content-Type").orElse("")
                                .toLowerCase(Locale.ROOT);
                        if (!type.startsWith(ProtocolCodec.ENVELOPE_MEDIA_TYPE)) {
                            throw new CompletionException(remoteError(response, "SC_TRANSPORT_FAILED"));
                        }
                        try {
                            return codec.decode(response.body(), encoded.sequence(), encoded.requestId());
                        } catch (SecureError error) {
                            throw new CompletionException(error);
                        }
                    });
        } catch (SecureError error) {
            return failed(error);
        }
    }

    private <I, O> CompletableFuture<O> postJson(
            String path, I input, Class<O> outputType, ExecutionOptions options,
            String fallbackCode) {
        try {
            HttpRequest request = requestBuilder(path, options)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(JsonSupport.write(input))).build();
            return transport.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                    .handle((response, failure) -> {
                        if (failure != null) throw new CompletionException(networkFailure(failure));
                        if (response.body().length > MAX_HANDSHAKE_RESPONSE_BYTES) {
                            throw new CompletionException(handshakeFailure(null));
                        }
                        if (response.statusCode() / 100 != 2) {
                            throw new CompletionException(remoteError(response, fallbackCode));
                        }
                        try {
                            return JsonSupport.readStrict(response.body(), outputType);
                        } catch (Exception exception) {
                            throw new CompletionException(handshakeFailure(exception));
                        }
                    });
        } catch (Exception exception) {
            return failed(handshakeFailure(exception));
        }
    }

    private HttpRequest.Builder requestBuilder(String path, ExecutionOptions options) {
        Duration timeout = options.getTimeout() == null
                ? config.getRequestTimeout() : options.getTimeout();
        return HttpRequest.newBuilder(config.getBaseUrl().resolve(path)).timeout(timeout);
    }

    private void validateHandshakeResponse(ProtocolModels.HandshakeStartResponse response)
            throws SecureError {
        if (response == null || response.v() != ProtocolCodec.VERSION
                || !ProtocolCodec.SUITE.equals(response.suite())
                || !validId(response.kid()) || !validId(response.sid())
                || response.createdAt() <= 0 || response.expiresAt() <= response.createdAt()
                || response.createdAt() < config.getClock().millis()
                        - config.getAllowedClockSkew().toMillis()
                || response.createdAt() > config.getClock().millis()
                        + config.getAllowedClockSkew().toMillis()) {
            throw handshakeFailure(null);
        }
    }

    private byte[] transcriptHash(
            ProtocolModels.HandshakeStartRequest request, byte[] installation,
            byte[] serverIdentity, byte[] serverEphemeral,
            ProtocolModels.HandshakeStartResponse response) throws SecureError {
        String transcript = String.join("\n", "SC1-HANDSHAKE", "1", ProtocolCodec.SUITE,
                config.getAppId(), identityValue(request.deviceId()), config.getDeviceType(),
                request.clientEphemeralPublicKey(), CryptoSupport.encode(installation),
                CryptoSupport.encode(serverIdentity), CryptoSupport.encode(serverEphemeral),
                response.kid(), response.sid(), String.valueOf(response.createdAt()),
                String.valueOf(response.expiresAt()));
        return CryptoSupport.sha256(transcript.getBytes(StandardCharsets.UTF_8));
    }

    private static String identityValue(String value) throws SecureError {
        if (!validId(value)) throw handshakeFailure(null);
        return value;
    }

    private static boolean validId(String value) {
        return value != null && value.matches("[A-Za-z0-9._:@/-]{1,128}");
    }

    private SecureError remoteError(HttpResponse<byte[]> response, String fallbackCode) {
        try {
            ProtocolModels.RemoteError remote = JsonSupport.readStrict(
                    response.body(), ProtocolModels.RemoteError.class);
            String code = remote.code() == null || remote.code().isBlank()
                    ? fallbackCode : remote.code();
            return new SecureError(code, "Secure transport failed", response.statusCode(),
                    remote.traceId(), null);
        } catch (Exception ignored) {
            return new SecureError(fallbackCode, "Secure transport failed",
                    response.statusCode(),
                    response.headers().firstValue("X-Trace-Id").orElse(null), null);
        }
    }

    private static SecureError networkFailure(Throwable failure) {
        Throwable value = unwrap(failure);
        if (value instanceof HttpTimeoutException) {
            return new SecureError("SC_REQUEST_TIMEOUT", "Secure request timed out",
                    0, null, value);
        }
        if (value instanceof CancellationException) {
            return new SecureError("SC_REQUEST_CANCELLED", "Secure request was cancelled",
                    0, null, value);
        }
        return new SecureError("SC_NETWORK_FAILED", "Network request failed",
                0, null, value);
    }

    private static SecureError handshakeFailure(Throwable cause) {
        return new SecureError("SC_HANDSHAKE_FAILED", "Secure handshake failed",
                0, null, cause);
    }

    private static SecureError asSecureError(Throwable failure) {
        Throwable value = unwrap(failure);
        if (value instanceof SecureError error) return error;
        if (value instanceof CancellationException) return networkFailure(value);
        return new SecureError("SC_INTERNAL_ERROR", "Secure client operation failed",
                0, null, value);
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable value = failure;
        while ((value instanceof CompletionException || value instanceof ExecutionException)
                && value.getCause() != null) value = value.getCause();
        return value;
    }

    private static <T> T await(CompletableFuture<T> future) throws SecureError {
        try {
            return future.get();
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new SecureError("SC_REQUEST_CANCELLED", "Secure request was interrupted",
                    0, null, exception);
        } catch (ExecutionException exception) {
            throw asSecureError(exception);
        } catch (CancellationException exception) {
            throw networkFailure(exception);
        }
    }

    private static <T> CompletableFuture<T> mirror(CompletableFuture<T> source) {
        CompletableFuture<T> mirror = new CompletableFuture<>();
        source.whenComplete((value, failure) -> {
            if (failure == null) mirror.complete(value);
            else mirror.completeExceptionally(asSecureError(failure));
        });
        return mirror;
    }

    private static <T> CompletableFuture<T> failed(Throwable failure) {
        CompletableFuture<T> result = new CompletableFuture<>();
        result.completeExceptionally(failure);
        return result;
    }

    private static final class OperationFuture<T> extends CompletableFuture<T> {
        private final AtomicReference<CompletableFuture<?>> active = new AtomicReference<>();
        void setActive(CompletableFuture<?> future) {
            active.set(future);
            if (isCancelled()) future.cancel(true);
        }
        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            CompletableFuture<?> current = active.get();
            if (current != null) current.cancel(mayInterruptIfRunning);
            return super.cancel(mayInterruptIfRunning);
        }
    }
}
