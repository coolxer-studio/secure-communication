package com.coolxer.securecommunication.internal;

import com.coolxer.securecommunication.InstallationIdentity;
import com.coolxer.securecommunication.ExecutionOptions;
import com.coolxer.securecommunication.SecureClient;
import com.coolxer.securecommunication.SecureClientConfig;
import com.coolxer.securecommunication.SecureClients;
import com.coolxer.securecommunication.SecureError;
import com.coolxer.securecommunication.SecureRequest;
import com.coolxer.securecommunication.SecureResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class SecureClientIntegrationTest {
    private MockProtocolServer server;

    @BeforeEach void start() throws Exception { server = new MockProtocolServer(); }
    @AfterEach void stop() { if (server != null) server.close(); }

    @Test
    void sharesConcurrentHandshakeAndUsesUniqueSequences() throws Exception {
        MemoryIdentity identity = new MemoryIdentity();
        SecureClientConfig config = SecureClientConfig.builder()
                .baseUrl(server.baseUrl()).appId("java-host").deviceType("HOST")
                .serverTrustAnchors(Map.of(server.keyId(), server.publicKeyBase64()))
                .identityStore(appId -> identity).build();
        try (SecureClient client = SecureClients.create(config)) {
            client.enroll("single-use-token");
            List<CompletableFuture<Void>> initializers = new ArrayList<>();
            for (int index = 0; index < 8; index++) initializers.add(client.initializeAsync());
            CompletableFuture.allOf(initializers.toArray(CompletableFuture[]::new)).join();
            assertEquals(1, server.handshakeCount.get());

            List<CompletableFuture<SecureResponse>> requests = new ArrayList<>();
            for (int index = 0; index < 12; index++) {
                String body = "{\"index\":" + index + "}";
                requests.add(client.requestAsync(SecureRequest.builder().method("POST")
                        .logicalPath("/events/upload?z=2&a=1")
                        .contentType("application/json")
                        .protectedHeaders(Map.of("Code", "java-17"))
                        .body(body.getBytes(StandardCharsets.UTF_8)).build()));
            }
            CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).join();
            for (CompletableFuture<SecureResponse> request : requests) {
                assertEquals(200, request.join().getStatus());
                assertEquals("application/json", request.join().getContentType());
                assertTrue(request.join().bodyAsUtf8().contains("index"));
            }
            assertEquals(12, server.sequences.size());
            assertTrue(server.sequences.contains(1L));
            assertTrue(server.sequences.contains(12L));
            assertEquals(1, server.handshakeCount.get());
        }
    }

    @Test
    void mapsPerRequestDeadlineToStableTimeoutError() throws Exception {
        MemoryIdentity identity = new MemoryIdentity();
        SecureClientConfig config = SecureClientConfig.builder()
                .baseUrl(server.baseUrl()).appId("timeout-host")
                .serverTrustAnchors(Map.of(server.keyId(), server.publicKeyBase64()))
                .identityStore(appId -> identity).build();
        try (SecureClient client = SecureClients.create(config)) {
            client.enroll("single-use-token");
            client.initialize();
            server.messageDelayMillis = 250;
            CompletionException failure = assertThrows(CompletionException.class,
                    () -> client.requestAsync(SecureRequest.builder()
                            .method("POST").logicalPath("/events/upload?z=2&a=1")
                            .contentType("application/json")
                            .protectedHeaders(Map.of("code", "java-17"))
                            .body("{}".getBytes(StandardCharsets.UTF_8)).build(),
                            ExecutionOptions.builder().timeout(Duration.ofMillis(50)).build())
                            .join());
            assertTrue(failure.getCause() instanceof SecureError);
            assertEquals("SC_REQUEST_TIMEOUT", ((SecureError) failure.getCause()).getCode());
        }
    }

    private static final class MemoryIdentity implements InstallationIdentity {
        private final KeyPair pair;
        private final String deviceId = UUID.randomUUID().toString();
        private MemoryIdentity() throws SecureError { pair = CryptoSupport.generateP256(); }
        @Override public String deviceId() { return deviceId; }
        @Override public byte[] publicKeySpki() { return pair.getPublic().getEncoded(); }
        @Override public byte[] sign(byte[] data) throws SecureError {
            return CryptoSupport.signP1363(pair.getPrivate(), data);
        }
    }

    private static final class MockProtocolServer implements AutoCloseable {
        private final String keyId = "test-key-2026";
        private final KeyPair identity = CryptoSupport.generateP256();
        private final HttpServer server;
        private final AtomicInteger handshakeCount = new AtomicInteger();
        private final Set<Long> sequences = ConcurrentHashMap.newKeySet();
        private volatile long messageDelayMillis;
        private volatile Session session;

        private MockProtocolServer() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/sc/v1/handshake", this::handshake);
            server.createContext("/sc/v1/handshake/finish", this::finish);
            server.createContext("/sc/v1/message", this::message);
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
        }

        URI baseUrl() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"); }
        String keyId() { return keyId; }
        String publicKeyBase64() { return CryptoSupport.encode(identity.getPublic().getEncoded()); }

        private void handshake(HttpExchange exchange) throws IOException {
            try {
                handshakeCount.incrementAndGet();
                ProtocolModels.HandshakeStartRequest request = JsonSupport.readStrict(
                        exchange.getRequestBody().readAllBytes(),
                        ProtocolModels.HandshakeStartRequest.class);
                KeyPair ephemeral = CryptoSupport.generateP256();
                String sessionId = UUID.randomUUID().toString();
                long created = System.currentTimeMillis();
                long expires = created + 60_000;
                byte[] serverIdentity = identity.getPublic().getEncoded();
                byte[] serverEphemeral = ephemeral.getPublic().getEncoded();
                String transcript = String.join("\n", "SC1-HANDSHAKE", "1",
                        ProtocolCodec.SUITE, request.appId(), request.deviceId(),
                        request.deviceType(), request.clientEphemeralPublicKey(),
                        request.installationPublicKey(), CryptoSupport.encode(serverIdentity),
                        CryptoSupport.encode(serverEphemeral), keyId, sessionId,
                        String.valueOf(created), String.valueOf(expires));
                byte[] transcriptHash = CryptoSupport.sha256(
                        transcript.getBytes(StandardCharsets.UTF_8));
                PublicKey clientEphemeral = CryptoSupport.parseP256Public(
                        CryptoSupport.decode(request.clientEphemeralPublicKey(), false));
                byte[] material = CryptoSupport.deriveMaterial(
                        ephemeral.getPrivate(), clientEphemeral, transcriptHash,
                        ProtocolCodec.SUITE, sessionId);
                session = new Session(sessionId, expires, transcriptHash,
                        CryptoSupport.parseP256Public(CryptoSupport.decode(
                                request.installationPublicKey(), false)), material);
                ProtocolModels.HandshakeStartResponse response =
                        new ProtocolModels.HandshakeStartResponse(
                                1, ProtocolCodec.SUITE, keyId, sessionId,
                                CryptoSupport.encode(serverIdentity),
                                CryptoSupport.encode(serverEphemeral), created, expires,
                                CryptoSupport.encode(CryptoSupport.signP1363(
                                        identity.getPrivate(), transcriptHash)));
                send(exchange, 200, "application/json", JsonSupport.write(response));
            } catch (Exception error) {
                sendFailure(exchange, error);
            }
        }

        private void finish(HttpExchange exchange) throws IOException {
            try {
                ProtocolModels.HandshakeFinishRequest request = JsonSupport.readStrict(
                        exchange.getRequestBody().readAllBytes(),
                        ProtocolModels.HandshakeFinishRequest.class);
                Session current = session;
                if (!current.id.equals(request.sid()) || !CryptoSupport.verifyP1363(
                        current.installation, current.transcriptHash,
                        CryptoSupport.decode(request.proof(), false))) {
                    throw new IllegalStateException("invalid client proof");
                }
                send(exchange, 200, "application/json", JsonSupport.write(
                        new ProtocolModels.HandshakeFinishResponse(true, current.expiresAt)));
            } catch (Exception error) {
                sendFailure(exchange, error);
            }
        }

        private void message(HttpExchange exchange) throws IOException {
            try {
                if (messageDelayMillis > 0) Thread.sleep(messageDelayMillis);
                Session current = session;
                ProtocolModels.Envelope request = JsonSupport.readStrict(
                        exchange.getRequestBody().readAllBytes(), ProtocolModels.Envelope.class);
                sequences.add(request.seq());
                byte[] requestNonce = CryptoSupport.nonce(
                        Arrays.copyOfRange(current.material, 64, 68), request.seq());
                byte[] plaintext = CryptoSupport.crypt(Cipher.DECRYPT_MODE,
                        Arrays.copyOfRange(current.material, 0, 32), requestNonce,
                        ProtocolCodec.aad("request", request),
                        CryptoSupport.decode(request.ciphertext(), false));
                ProtocolModels.ProtectedRequest logical = JsonSupport.readStrict(
                        plaintext, ProtocolModels.ProtectedRequest.class);
                if (!"/events/upload?a=1&z=2".equals(logical.path())
                        || !"java-17".equals(logical.headers().get("code"))) {
                    throw new IllegalStateException("logical request mismatch");
                }
                long timestamp = System.currentTimeMillis();
                byte[] responseNonce = CryptoSupport.nonce(
                        Arrays.copyOfRange(current.material, 68, 72), request.seq());
                ProtocolModels.Envelope unsigned = new ProtocolModels.Envelope(
                        1, ProtocolCodec.SUITE, keyId, current.id, timestamp, request.seq(),
                        request.rid(), "POST", ProtocolCodec.MESSAGE_ENDPOINT,
                        ProtocolCodec.PROTECTED_MEDIA_TYPE, 200,
                        CryptoSupport.encode(responseNonce), "");
                ProtocolModels.ProtectedResponse result = new ProtocolModels.ProtectedResponse(
                        logical.contentType(), logical.body());
                byte[] ciphertext = CryptoSupport.crypt(Cipher.ENCRYPT_MODE,
                        Arrays.copyOfRange(current.material, 32, 64), responseNonce,
                        ProtocolCodec.aad("response", unsigned), JsonSupport.write(result));
                ProtocolModels.Envelope response = new ProtocolModels.Envelope(
                        unsigned.v(), unsigned.suite(), unsigned.kid(), unsigned.sid(),
                        unsigned.ts(), unsigned.seq(), unsigned.rid(), unsigned.m(), unsigned.p(),
                        unsigned.cty(), unsigned.st(), unsigned.nonce(),
                        CryptoSupport.encode(ciphertext));
                send(exchange, 200, ProtocolCodec.ENVELOPE_MEDIA_TYPE, JsonSupport.write(response));
            } catch (Exception error) {
                sendFailure(exchange, error);
            }
        }

        private static void sendFailure(HttpExchange exchange, Exception error) throws IOException {
            send(exchange, 500, "application/json", ("{\"code\":\"SC_TEST_FAILED\","
                    + "\"message\":\"test server failed\",\"traceId\":\"test\"}")
                    .getBytes(StandardCharsets.UTF_8));
        }

        private static void send(
                HttpExchange exchange, int status, String type, byte[] body) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }

        @Override public void close() { server.stop(0); }

        private record Session(
                String id, long expiresAt, byte[] transcriptHash,
                PublicKey installation, byte[] material) { }
    }
}
