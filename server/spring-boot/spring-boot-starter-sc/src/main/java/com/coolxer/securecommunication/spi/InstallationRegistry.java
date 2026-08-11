package com.coolxer.securecommunication.spi;

import com.coolxer.securecommunication.protocol.SecureProtocolException;

import java.util.Optional;

public interface InstallationRegistry {
    Optional<byte[]> find(String appId, String deviceId) throws SecureProtocolException;

    void register(String appId, String deviceId, String deviceType, byte[] encodedPublicKey)
            throws SecureProtocolException;
}
