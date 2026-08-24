package com.coolxer.securecommunication.handshake;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = false)
public record HandshakeFinishRequest(String kid, String sid, String proof) {
}
