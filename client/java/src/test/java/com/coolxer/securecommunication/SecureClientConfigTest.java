package com.coolxer.securecommunication;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecureClientConfigTest {
    private static final IdentityStore UNUSED_IDENTITY = ignored -> {
        throw new AssertionError("configuration tests must not load an identity");
    };

    @Test
    void acceptsServerDeviceType() {
        SecureClientConfig config = builder().deviceType("server").build();

        assertEquals("SERVER", config.getDeviceType());
    }

    @Test
    void rejectsUnknownDeviceType() {
        assertThrows(IllegalArgumentException.class,
                () -> builder().deviceType("DESKTOP").build());
    }

    @Test
    void acceptsHttpAndHttpsAndRejectsOtherUrlForms() {
        assertDoesNotThrow(() -> builder().baseUrl(URI.create("http://192.0.2.10:8080")).build());
        assertDoesNotThrow(() -> builder().baseUrl(URI.create("https://synap.example.test")).build());
        assertThrows(IllegalArgumentException.class,
                () -> builder().baseUrl(URI.create("ftp://synap.example.test")).build());
        assertThrows(IllegalArgumentException.class,
                () -> builder().baseUrl(URI.create("https://user:secret@synap.example.test")).build());
        assertThrows(IllegalArgumentException.class,
                () -> builder().baseUrl(URI.create("https://synap.example.test?x=1")).build());
        assertThrows(IllegalArgumentException.class,
                () -> builder().baseUrl(URI.create("https://synap.example.test#fragment")).build());
    }

    private static SecureClientConfig.Builder builder() {
        return SecureClientConfig.builder()
                .baseUrl(URI.create("https://synap.example.test"))
                .appId("synap-server-agent")
                .serverTrustAnchors(Map.of("server-key", "unused"))
                .identityStore(UNUSED_IDENTITY);
    }
}
