package com.coolxer.securecommunication.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Plaintext carried inside an authenticated envelope. */
public record ProtectedPayload(
        String method,
        String path,
        String contentType,
        Map<String, String> headers,
        byte[] body) {
    public ProtectedPayload {
        headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        body = body.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }
}
