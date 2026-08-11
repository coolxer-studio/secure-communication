package com.coolxer.securecommunication.spi;

import com.coolxer.securecommunication.protocol.SecureProtocolException;

@FunctionalInterface
public interface HandshakeAuthorizer {
    void authorize(HandshakeContext context) throws SecureProtocolException;

    record HandshakeContext(
            String appId,
            String deviceId,
            String deviceType,
            String origin,
            String remoteAddress,
            boolean registeredInstallation) {
    }
}
