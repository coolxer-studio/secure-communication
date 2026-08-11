package com.coolxer.securecommunication;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class SecureResponse {
    private final int status;
    private final String contentType;
    private final byte[] body;

    public SecureResponse(int status, String contentType, byte[] body) {
        this.status = status;
        this.contentType = contentType;
        this.body = Arrays.copyOf(body, body.length);
    }

    public int getStatus() {
        return status;
    }

    public String getContentType() {
        return contentType;
    }

    public byte[] getBody() {
        return Arrays.copyOf(body, body.length);
    }

    public String bodyAsUtf8() {
        return new String(body, StandardCharsets.UTF_8);
    }
}
