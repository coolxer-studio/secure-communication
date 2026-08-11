package com.coolxer.securecommunication.core;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class InternationalSessionKeyDeriverTest {
    @Test
    void matchesRfc5869Sha256CaseOne() throws Exception {
        HexFormat hex = HexFormat.of();
        byte[] actual = InternationalSessionKeyDeriver.hkdf(
                hex.parseHex("0b".repeat(22)),
                hex.parseHex("000102030405060708090a0b0c"),
                hex.parseHex("f0f1f2f3f4f5f6f7f8f9"),
                42);

        assertArrayEquals(
                hex.parseHex(
                        "3cb25f25faacd57a90434f64d0362f2a"
                                + "2d2d0a90cf1a5a4c5db02d56ecc4c5bf"
                                + "34007208d5b887185865"),
                actual);
    }
}
