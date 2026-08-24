package com.coolxer.securecommunication.handshake;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = false)
public record HandshakeRequest(
        int v,
        String suite,
        String appId,
        String deviceId,
        String deviceType,
        String clientEphemeralPublicKey,
        String installationPublicKey,
        String enrollmentToken,
        long timestamp) {
}
