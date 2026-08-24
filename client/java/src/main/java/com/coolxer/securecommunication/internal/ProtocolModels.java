package com.coolxer.securecommunication.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

final class ProtocolModels {
    private ProtocolModels() { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record HandshakeStartRequest(
            int v, String suite, String appId, String deviceId, String deviceType,
            String clientEphemeralPublicKey, String installationPublicKey,
            String enrollmentToken, long timestamp) { }

    record HandshakeStartResponse(
            int v, String suite, String kid, String sid,
            String serverIdentityPublicKey, String serverEphemeralPublicKey,
            long createdAt, long expiresAt, String signature) { }

    record HandshakeFinishRequest(String kid, String sid, String proof) { }
    record HandshakeFinishResponse(boolean active, long expiresAt) { }

    record Envelope(
            int v, String suite, String kid, String sid, long ts, long seq,
            String rid, String m, String p, String cty, int st,
            String nonce, @JsonProperty("ct") String ciphertext) { }

    record ProtectedRequest(
            String method, String path, String contentType,
            Map<String, String> headers, String body) { }

    record ProtectedResponse(String contentType, String body) { }

    record RemoteError(String code, String message, String traceId) { }
}
