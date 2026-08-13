package com.coolxer.securecommunication.internal;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;

public final class JsonSupport {
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private JsonSupport() { }

    public static byte[] write(Object value) throws IOException {
        return MAPPER.writeValueAsBytes(value);
    }

    public static <T> T readStrict(byte[] value, Class<T> type) throws IOException {
        return MAPPER.readValue(value, type);
    }
}
