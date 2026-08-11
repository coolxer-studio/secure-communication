package com.coolxer.securecommunication.protocol;

public final class ProtocolConstants {
    public static final int VERSION = 1;
    public static final String ENVELOPE_MEDIA_TYPE = "application/sc-envelope+json";
    public static final String PROTECTED_MEDIA_TYPE = "application/sc-protected+json";
    public static final String OUTER_METHOD = "POST";
    public static final String INTERNATIONAL_SUITE =
            "P256_HKDF_SHA256_AES256_GCM";
    public static final String GM_SUITE = "SM2_SM3_SM4_GCM";
    public static final String MESSAGE_ENDPOINT = "/sc/v1/message";
    public static final String HANDSHAKE_ENDPOINT = "/sc/v1/handshake";
    public static final String HANDSHAKE_FINISH_ENDPOINT = "/sc/v1/handshake/finish";
    public static final int NONCE_BYTES = 12;
    public static final int TAG_BYTES = 16;

    private ProtocolConstants() {
    }
}
