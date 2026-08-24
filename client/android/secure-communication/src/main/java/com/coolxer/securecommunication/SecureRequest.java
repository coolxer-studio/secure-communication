package com.coolxer.securecommunication;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SecureRequest {
    private final String method;
    private final String logicalPath;
    private final String contentType;
    private final byte[] body;
    private final Map<String, String> protectedHeaders;
    private final String requestId;

    public SecureRequest(String logicalPath) {
        this("GET", logicalPath, "application/octet-stream", Collections.emptyMap(),
                new byte[0], null);
    }

    public SecureRequest(
            String method, String logicalPath, String contentType,
            Map<String, String> protectedHeaders, byte[] body, String requestId) {
        this.method = method == null || method.isEmpty() ? "GET" : method.toUpperCase();
        if (!this.method.matches("[A-Z]{3,16}")) {
            throw new IllegalArgumentException("Invalid method");
        }
        if (logicalPath == null || !logicalPath.startsWith("/")
                || logicalPath.contains("://") || logicalPath.contains("#")
                || logicalPath.contains("\r") || logicalPath.contains("\n")) {
            throw new IllegalArgumentException("Invalid logicalPath");
        }
        this.logicalPath = logicalPath;
        this.contentType = contentType == null
                ? "application/octet-stream"
                : contentType;
        String normalizedContentType = this.contentType.split(";", 2)[0]
                .trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalizedContentType.matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")) {
            throw new IllegalArgumentException("Invalid contentType");
        }
        this.body = body == null ? new byte[0] : Arrays.copyOf(body, body.length);
        this.protectedHeaders = Collections.unmodifiableMap(
                new LinkedHashMap<>(protectedHeaders == null
                        ? Collections.emptyMap() : protectedHeaders));
        for (Map.Entry<String, String> header : this.protectedHeaders.entrySet()) {
            if (header.getKey() == null
                    || !header.getKey().toLowerCase(java.util.Locale.ROOT)
                    .matches("[a-z0-9-]{1,64}")
                    || header.getValue() == null || header.getValue().length() > 8192
                    || header.getValue().contains("\r") || header.getValue().contains("\n")) {
                throw new IllegalArgumentException("Invalid protected header");
            }
        }
        this.requestId = requestId == null || requestId.isEmpty() ? null : requestId;
        if (this.requestId != null && !this.requestId.matches("[\\x21-\\x7e]{1,128}")) {
            throw new IllegalArgumentException("Invalid requestId");
        }
    }

    public static SecureRequest json(String method, String path, String json) {
        return new SecureRequest(method, path, "application/json", Collections.emptyMap(),
                json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8), null);
    }

    public String getMethod() {
        return method;
    }

    public String getLogicalPath() {
        return logicalPath;
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
