package com.coolxer.securecommunication.core;

import com.coolxer.securecommunication.protocol.AadFactory;
import com.coolxer.securecommunication.protocol.Direction;
import com.coolxer.securecommunication.protocol.RequestMetadata;
import com.coolxer.securecommunication.protocol.SecureEnvelope;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AesGcmVectorTest {
    private static final String SUITE = "P256_HKDF_SHA256_AES256_GCM";
    private static final String COMBINED =
            "y43V6x8Hp9N--JdIn9atb-inacLk7T5Q9B7Jzez-vufGdNrX0f2xKw";

    @Test
    void matchesCrossLanguageRequestVector() throws Exception {
        AesGcmAlgorithmProvider provider = new AesGcmAlgorithmProvider();
        SecureEnvelope envelope = new SecureEnvelope(
                1, SUITE, "test-key-2026-01", "test-session-0001",
                1785283200000L, 1, "request-0001", "POST", "/sc/v1/message",
                "application/sc-protected+json", 0,
                "oKGiowAAAAAAAAAB", COMBINED);
        RequestMetadata metadata = new RequestMetadata(
                "POST", "/sc/v1/message", "application/sc-protected+json");
        byte[] key = hex(
                "000102030405060708090a0b0c0d0e0f"
                        + "101112131415161718191a1b1c1d1e1f");
        byte[] nonce = Base64.getUrlDecoder().decode(envelope.getNonce());
        byte[] aad = AadFactory.create(Direction.REQUEST, envelope, metadata);
        byte[] plaintext = "{\"message\":\"你好🌍\"}".getBytes(StandardCharsets.UTF_8);

        byte[] sealed = provider.seal(key, nonce, aad, plaintext);

        assertEquals(COMBINED,
                Base64.getUrlEncoder().withoutPadding().encodeToString(sealed));
        assertArrayEquals(plaintext, provider.open(key, nonce, aad, sealed));
    }

    private static byte[] hex(String value) {
        return java.util.HexFormat.of().parseHex(value);
    }
}
