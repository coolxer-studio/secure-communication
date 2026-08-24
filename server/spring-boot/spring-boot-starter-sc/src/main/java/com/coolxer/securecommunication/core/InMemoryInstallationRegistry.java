package com.coolxer.securecommunication.core;

import com.coolxer.securecommunication.spi.InstallationRegistry;

import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryInstallationRegistry implements InstallationRegistry {
    private final ConcurrentHashMap<String, byte[]> identities = new ConcurrentHashMap<>();

    @Override
    public Optional<byte[]> find(String appId, String deviceId) {
        byte[] value = identities.get(key(appId, deviceId));
        return value == null ? Optional.empty() : Optional.of(value.clone());
    }

    @Override
    public void register(String appId, String deviceId, String deviceType, byte[] publicKey) {
        identities.compute(key(appId, deviceId), (ignored, current) -> {
            if (current != null && !Arrays.equals(current, publicKey)) {
                throw new IllegalStateException("Installation identity is already registered");
            }
            return publicKey.clone();
        });
    }

    private static String key(String appId, String deviceId) {
        return appId + '\u0000' + deviceId;
    }
}
