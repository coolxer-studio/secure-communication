package com.coolxer.securecommunication.core;

import com.coolxer.securecommunication.protocol.ProtectedPayload;
import com.coolxer.securecommunication.protocol.SecureErrorCode;
import com.coolxer.securecommunication.protocol.SecureProtocolException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProtectedPayloadCodecTest {
    private final ProtectedPayloadCodec codec = new ProtectedPayloadCodec();

    @Test
    void decodesCompleteLogicalRequest() throws Exception {
        ProtectedPayload value = codec.decode(("{"
                + "\"method\":\"POST\",\"path\":\"/attach/upload?a=1&b=2\","
                + "\"contentType\":\"application/json\","
                + "\"headers\":{\"code\":\"abc\"},\"body\":\"e30\"}")
                .getBytes(StandardCharsets.UTF_8), 262_144);

        assertEquals("POST", value.method());
        assertEquals("/attach/upload?a=1&b=2", value.path());
        assertEquals("application/json", value.contentType());
        assertEquals("abc", value.headers().get("code"));
        assertArrayEquals("{}".getBytes(StandardCharsets.UTF_8), value.body());
    }

    @Test
    void rejectsUnknownFieldsAndNonCanonicalRoutes() {
        String invalid = "{\"method\":\"POST\",\"path\":\"/attach/upload?b=2&a=1\","
                + "\"contentType\":\"application/json\",\"headers\":{},"
                + "\"body\":\"\"}";
        SecureProtocolException error = assertThrows(SecureProtocolException.class,
                () -> codec.decode(invalid.getBytes(StandardCharsets.UTF_8), 262_144));
        assertEquals(SecureErrorCode.INVALID_ENVELOPE, error.errorCode());
    }
}
