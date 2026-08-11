package com.coolxer.securecommunication;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SecureRequest {
    private final String method;
    private final String path;
    private final String contentType;
    private final byte[] body;
    private final Map<String, String> protectedHeaders;
    private final String requestId;

    public SecureRequest(String method, String path, String contentType, byte[] body) {
        this(method, path, contentType, Collections.emptyMap(), body,
                java.util.UUID.randomUUID().toString());
    }

    public SecureRequest(
            String method, String path, String contentType,
            Map<String, String> protectedHeaders, byte[] body) {
        this(method, path, contentType, protectedHeaders, body,
                java.util.UUID.randomUUID().toString());
    }

    public SecureRequest(
            String method, String path, String contentType,
            Map<String, String> protectedHeaders, byte[] body, String requestId) {
        this.method = method == null ? "GET" : method;
        this.path = path;
        this.contentType = contentType == null
                ? "application/octet-stream"
                : contentType;
        this.body = body == null ? new byte[0] : Arrays.copyOf(body, body.length);
        this.protectedHeaders = Collections.unmodifiableMap(
                new LinkedHashMap<>(protectedHeaders == null
                        ? Collections.emptyMap() : protectedHeaders));
        this.requestId = requestId == null || requestId.isEmpty()
                ? java.util.UUID.randomUUID().toString() : requestId;
    }

    public static SecureRequest json(String method, String path, String json) {
        return new SecureRequest(
                method, path, "application/json",
                json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8));
    }

    public String getMethod() {
        return method;
    }

    public String getPath() {
        return path;
    }

    public String getContentType() {
        return contentType;
    }

    public byte[] getBody() {
        return Arrays.copyOf(body, body.length);
    }

    public Map<String, String> getProtectedHeaders() {
        return protectedHeaders;
    }

    public String getRequestId() {
        return requestId;
    }
}
