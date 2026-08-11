package com.coolxer.securecommunication.handshake;

public record HandshakeResponse(
        int v,
        String suite,
        String kid,
        String sid,
        String serverIdentityPublicKey,
        String serverEphemeralPublicKey,
        long createdAt,
        long expiresAt,
        String signature) {
}
