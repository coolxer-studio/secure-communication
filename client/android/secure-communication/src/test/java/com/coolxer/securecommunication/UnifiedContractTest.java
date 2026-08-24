package com.coolxer.securecommunication;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import okhttp3.HttpUrl;

public class UnifiedContractTest {
    @Test public void businessTransportMatchesBaseUrlScheme() {
        assertFalse(SecureCommunicationClient.connectionSpecs(
                HttpUrl.parse("http://192.0.2.10:8080")).get(0).isTls());
        assertTrue(SecureCommunicationClient.connectionSpecs(
                HttpUrl.parse("https://example.test")).get(0).isTls());
    }

    @Test public void requestUsesContractDefaultsAndDefensiveBodyCopies() {
        SecureRequest request = new SecureRequest("/health");
        assertEquals("GET", request.getMethod());
        assertEquals("/health", request.getLogicalPath());
        assertEquals("application/octet-stream", request.getContentType());
        assertArrayEquals(new byte[0], request.getBody());
        assertNull(request.getRequestId());
    }

    @Test public void configAcceptsHttpAndHttpsAndRejectsOtherUrlForms() {
        SecureClientConfig config = SecureClientConfig.builder()
                .baseUrl("http://127.0.0.1:8080")
                .appId("agent")
                .deviceType("SERVER")
                .serverTrustAnchors(Collections.singletonMap("kid", "spki"))
                .build();
        assertEquals("SERVER", config.getDeviceType());
        buildConfig("http://192.0.2.10:8080");
        buildConfig("https://example.test");
        for (String baseUrl : new String[]{"ftp://example.test",
                "https://user:secret@example.test", "https://example.test?x=1",
                "https://example.test#fragment"}) {
            try {
                buildConfig(baseUrl);
                org.junit.Assert.fail("invalid base URL must be rejected: " + baseUrl);
            } catch (IllegalArgumentException expected) { }
        }
    }

    private static SecureClientConfig buildConfig(String baseUrl) {
        return SecureClientConfig.builder().baseUrl(baseUrl).appId("agent")
                .serverTrustAnchors(Collections.singletonMap("kid", "spki")).build();
    }

    @Test public void errorExposesStableFields() {
        SecureError error = new SecureError("SC_TEST", "test", 409, "trace", null);
        assertEquals("SC_TEST", error.getCode());
        assertEquals(409, error.getHttpStatus());
        assertEquals("trace", error.getTraceId());
    }
}
