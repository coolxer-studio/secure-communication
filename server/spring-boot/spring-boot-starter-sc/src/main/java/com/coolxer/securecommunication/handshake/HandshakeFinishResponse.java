package com.coolxer.securecommunication.handshake;

public record HandshakeFinishResponse(boolean active, long expiresAt) {
}
