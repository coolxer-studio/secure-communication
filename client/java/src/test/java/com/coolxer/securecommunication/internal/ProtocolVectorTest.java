package com.coolxer.securecommunication.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ProtocolVectorTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void matchesRequestAndResponseVectors() throws Exception {
        verify("aes-256-gcm-request.json");
        verify("aes-256-gcm-response.json");
    }

    @Test
    void canonicalizesQueryAndRejectsUnsafePaths() {
        assertEquals("/cross/info?lang=zh&x=1",
                ProtocolCodec.normalizePath("/cross/info?x=1&lang=zh"));
        assertEquals("/encoded/%E4%BD%A0?x=%2F",
                ProtocolCodec.normalizePath("/encoded/%e4%bd%a0?x=%2f"));
        assertThrows(IllegalArgumentException.class,
                () -> ProtocolCodec.normalizePath("https://example.test/private"));
        assertThrows(IllegalArgumentException.class,
                () -> ProtocolCodec.normalizePath("/private#fragment"));
    }

    @Test
    void strictJsonRejectsUnknownAndDuplicateFields() {
        record Value(String name) { }
        assertThrows(Exception.class, () -> JsonSupport.readStrict(
                "{\"name\":\"a\",\"extra\":1}".getBytes(StandardCharsets.UTF_8), Value.class));
        assertThrows(Exception.class, () -> JsonSupport.readStrict(
                "{\"name\":\"a\",\"name\":\"b\"}".getBytes(StandardCharsets.UTF_8), Value.class));
    }

    @Test
    void rejectsTamperedCiphertext() throws Exception {
        byte[] key = new byte[32];
        byte[] nonce = new byte[12];
        byte[] aad = "authenticated".getBytes(StandardCharsets.UTF_8);
        byte[] ciphertext = CryptoSupport.crypt(Cipher.ENCRYPT_MODE, key, nonce, aad,
                "secret".getBytes(StandardCharsets.UTF_8));
        ciphertext[0] ^= 1;
        com.coolxer.securecommunication.SecureError error = assertThrows(
                com.coolxer.securecommunication.SecureError.class,
                () -> CryptoSupport.crypt(Cipher.DECRYPT_MODE, key, nonce, aad, ciphertext));
        assertEquals("SC_AUTHENTICATION_FAILED", error.getCode());
    }

    private static void verify(String name) throws Exception {
        JsonNode vector = JSON.readTree(Files.readAllBytes(
                Path.of("../../protocol/test-vectors", name)));
        byte[] key = HexFormat.of().parseHex(vector.get("keyHex").asText());
        byte[] prefix = HexFormat.of().parseHex(vector.get("noncePrefixHex").asText());
        long sequence = vector.get("sequence").asLong();
        byte[] nonce = CryptoSupport.nonce(prefix, sequence);
        assertEquals(vector.get("nonceBase64Url").asText(), CryptoSupport.encode(nonce));
        ProtocolModels.Envelope envelope = new ProtocolModels.Envelope(
                1, vector.get("suite").asText(), vector.get("kid").asText(),
                vector.get("sid").asText(), vector.get("timestamp").asLong(), sequence,
                vector.get("requestId").asText(), vector.get("method").asText(),
                vector.get("path").asText(), vector.get("contentType").asText(),
                vector.get("logicalStatus").asInt(), CryptoSupport.encode(nonce), "");
        byte[] aad = ProtocolCodec.aad(vector.get("direction").asText(), envelope);
        assertEquals(vector.get("aadUtf8").asText(), new String(aad, StandardCharsets.UTF_8));
        byte[] plaintext = vector.get("plaintextUtf8").asText().getBytes(StandardCharsets.UTF_8);
        byte[] ciphertext = CryptoSupport.crypt(Cipher.ENCRYPT_MODE, key, nonce, aad, plaintext);
        assertEquals(vector.get("combinedCiphertextBase64Url").asText(),
                CryptoSupport.encode(ciphertext));
        assertArrayEquals(plaintext,
                CryptoSupport.crypt(Cipher.DECRYPT_MODE, key, nonce, aad, ciphertext));
    }
}
