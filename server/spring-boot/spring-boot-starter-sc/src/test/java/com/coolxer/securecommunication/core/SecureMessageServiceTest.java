package com.coolxer.securecommunication.core;

import com.coolxer.securecommunication.protocol.ProtocolConstants;
import com.coolxer.securecommunication.protocol.SecureErrorCode;
import com.coolxer.securecommunication.protocol.SecureProtocolException;
import com.coolxer.securecommunication.spi.SessionKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecureMessageServiceTest {
    private static final long NOW = 1785283200000L;
    private static final byte[] REQUEST_KEY = java.util.HexFormat.of().parseHex(
            "000102030405060708090a0b0c0d0e0f"
                    + "101112131415161718191a1b1c1d1e1f");
    private static final byte[] RESPONSE_KEY = java.util.HexFormat.of().parseHex(
            "1f1e1d1c1b1a19181716151413121110"
                    + "0f0e0d0c0b0a09080706050403020100");
    private static final byte[] VECTOR = (
            "{\"v\":1,\"suite\":\"P256_HKDF_SHA256_AES256_GCM\","
                    + "\"kid\":\"test-key-2026-01\",\"sid\":\"test-session-0001\","
                    + "\"ts\":1785283200000,\"seq\":1,\"rid\":\"request-0001\","
                    + "\"m\":\"POST\",\"p\":\"/sc/v1/message\","
                    + "\"cty\":\"application/sc-protected+json\",\"st\":0,"
                    + "\"nonce\":\"oKGiowAAAAAAAAAB\","
                    + "\"ct\":\"y43V6x8Hp9N--JdIn9atb-inacLk7T5Q9B7Jzez-vufGdNrX0f2xKw\"}")
            .getBytes(StandardCharsets.UTF_8);

    private SecureMessageService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);
        InMemoryKeyProvider keys = new InMemoryKeyProvider();
        keys.put(new SessionKeys(
                "test-key-2026-01",
                "test-session-0001",
                ProtocolConstants.INTERNATIONAL_SUITE,
                REQUEST_KEY,
                RESPONSE_KEY,
                new byte[]{(byte) 0xa0, (byte) 0xa1, (byte) 0xa2, (byte) 0xa3},
                new byte[]{(byte) 0xb0, (byte) 0xb1, (byte) 0xb2, (byte) 0xb3},
                Instant.ofEpochMilli(NOW).plus(Duration.ofHours(1)),
                false));
        DefaultSecurityPolicy policy = new DefaultSecurityPolicy(
                Set.of(ProtocolConstants.INTERNATIONAL_SUITE),
                true,
                Duration.ofMinutes(5),
                Duration.ofMinutes(10),
                4096,
                2048,
                clock);
        service = new SecureMessageService(
                new JacksonEnvelopeCodec(),
                keys,
                new InMemoryReplayProtector(clock),
                policy,
                List.of(new AesGcmAlgorithmProvider()));
    }

    @Test
    void opensVectorAndRejectsReplay() throws Exception {
        SecureMessageService.OpenedRequest opened = service.openRequest(VECTOR);

        assertArrayEquals(
                "{\"message\":\"你好🌍\"}".getBytes(StandardCharsets.UTF_8),
                opened.plaintext());

        SecureProtocolException replay = assertThrows(
                SecureProtocolException.class,
                () -> service.openRequest(VECTOR));
        assertEquals(SecureErrorCode.REPLAY_DETECTED, replay.errorCode());
    }

    @Test
    void rejectsRouteAndCiphertextTamperingBeforeController() {
        byte[] routeTampered = new String(VECTOR, StandardCharsets.UTF_8)
                .replace("\"m\":\"POST\"", "\"m\":\"GET\"")
                .getBytes(StandardCharsets.UTF_8);
        SecureProtocolException route = assertThrows(
                SecureProtocolException.class,
                () -> service.openRequest(routeTampered));
        assertEquals(SecureErrorCode.ROUTE_MISMATCH, route.errorCode());

        byte[] tampered = new String(VECTOR, StandardCharsets.UTF_8)
                .replace("y43V6", "z43V6")
                .getBytes(StandardCharsets.UTF_8);
        SecureProtocolException authentication = assertThrows(
                SecureProtocolException.class,
                () -> service.openRequest(tampered));
        assertEquals(SecureErrorCode.AUTHENTICATION_FAILED, authentication.errorCode());
    }

    @Test
    void strictCodecRejectsUnknownAndDuplicateFields() {
        byte[] unknown = new String(VECTOR, StandardCharsets.UTF_8)
                .replace("\"v\":1", "\"v\":1,\"debug\":true")
                .getBytes(StandardCharsets.UTF_8);
        byte[] duplicate = new String(VECTOR, StandardCharsets.UTF_8)
                .replace("\"v\":1", "\"v\":1,\"v\":1")
                .getBytes(StandardCharsets.UTF_8);

        assertEquals(SecureErrorCode.INVALID_ENVELOPE,
                assertThrows(SecureProtocolException.class,
                        () -> service.openRequest(unknown))
                        .errorCode());
        assertEquals(SecureErrorCode.INVALID_ENVELOPE,
                assertThrows(SecureProtocolException.class,
                        () -> service.openRequest(duplicate))
                        .errorCode());
    }

    @Test
    void rejectsExpiredWrongNonceAndOversizedEnvelopes() {
        byte[] expired = new String(VECTOR, StandardCharsets.UTF_8)
                .replace("1785283200000", "1785282000000")
                .getBytes(StandardCharsets.UTF_8);
        assertEquals(SecureErrorCode.REQUEST_EXPIRED,
                assertThrows(SecureProtocolException.class,
                        () -> service.openRequest(expired)).errorCode());

        byte[] wrongNonce = new String(VECTOR, StandardCharsets.UTF_8)
                .replace("oKGiowAAAAAAAAAB", "oKGiowAAAAAAAAAC")
                .getBytes(StandardCharsets.UTF_8);
        assertEquals(SecureErrorCode.INVALID_ENVELOPE,
                assertThrows(SecureProtocolException.class,
                        () -> service.openRequest(wrongNonce)).errorCode());

        assertEquals(SecureErrorCode.PAYLOAD_TOO_LARGE,
                assertThrows(SecureProtocolException.class,
                        () -> service.openRequest(new byte[4097])).errorCode());
    }

    @Test
    void sealsTheCrossLanguageResponseVector() throws Exception {
        SecureMessageService.OpenedRequest opened = service.openRequest(VECTOR);
        byte[] encoded = service.sealResponse(
                opened,
                "{\"ok\":true}".getBytes(StandardCharsets.UTF_8),
                "application/json;charset=utf-8",
                200);
        JsonNode envelope = new ObjectMapper().readTree(encoded);

        assertEquals("sLGyswAAAAAAAAAB", envelope.get("nonce").asText());
        assertEquals(
                "2Q9-vvRfgB3ZDenLUzdtXout2_wtOCcXZu6C",
                envelope.get("ct").asText());
        assertEquals(200, envelope.get("st").asInt());
        assertEquals("POST", envelope.get("m").asText());
        assertEquals("/sc/v1/message", envelope.get("p").asText());
        assertEquals("request-0001", envelope.get("rid").asText());
    }
}
