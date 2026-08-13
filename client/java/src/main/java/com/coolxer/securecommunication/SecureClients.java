package com.coolxer.securecommunication;

import com.coolxer.securecommunication.internal.DefaultSecureClient;

/** Factory for high-level clients. */
public final class SecureClients {
    private SecureClients() {}
    public static SecureClient create(SecureClientConfig config) throws SecureError {
        return new DefaultSecureClient(config);
    }
}
