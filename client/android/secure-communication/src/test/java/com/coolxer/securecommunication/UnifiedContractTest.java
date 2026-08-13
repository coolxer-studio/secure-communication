package com.coolxer.securecommunication;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

public class UnifiedContractTest {
    @Test public void requestUsesContractDefaultsAndDefensiveBodyCopies() {
        SecureRequest request = new SecureRequest("/health");
        assertEquals("GET", request.getMethod());
        assertEquals("/health", request.getLogicalPath());
        assertEquals("application/octet-stream", request.getContentType());
        assertArrayEquals(new byte[0], request.getBody());
        assertNull(request.getRequestId());
    }

    @Test public void configAcceptsServerAndLoopbackOnlyForTests() {
        SecureClientConfig config = SecureClientConfig.builder()
                .baseUrl("http://127.0.0.1:8080")
                .appId("agent")
                .deviceType("SERVER")
                .serverTrustAnchors(Collections.singletonMap("kid", "spki"))
                .allowInsecureLoopbackForTesting(true)
                .build();
        assertEquals("SERVER", config.getDeviceType());
        try {
            SecureClientConfig.builder().baseUrl("http://example.test")
                    .appId("agent").serverTrustAnchors(
                            Collections.singletonMap("kid", "spki"))
                    .allowInsecureLoopbackForTesting(true).build();
            fail("non-loopback HTTP must be rejected");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void errorExposesStableFields() {
        SecureError error = new SecureError("SC_TEST", "test", 409, "trace", null);
        assertEquals("SC_TEST", error.getCode());
        assertEquals(409, error.getHttpStatus());
        assertEquals("trace", error.getTraceId());
    }
}
