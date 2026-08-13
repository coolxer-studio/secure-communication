package com.coolxer.securecommunication;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Immutable logical request protected inside a protocol v1 envelope. */
public final class SecureRequest {
    private final String method;
    private final String logicalPath;
    private final String contentType;
    private final Map<String, String> protectedHeaders;
    private final byte[] body;
    private final String requestId;

    private SecureRequest(Builder builder) {
        this.method = normalizeMethod(builder.method);
        this.logicalPath = requirePath(builder.logicalPath);
        this.contentType = normalizeContentType(builder.contentType);
        this.protectedHeaders = normalizeHeaders(builder.protectedHeaders);
        this.body = builder.body == null ? new byte[0] : Arrays.copyOf(builder.body, builder.body.length);
        this.requestId = normalizeRequestId(builder.requestId);
    }

    public static Builder builder() { return new Builder(); }

    public static SecureRequest json(String method, String logicalPath, String json) {
        return builder().method(method).logicalPath(logicalPath)
                .contentType("application/json")
                .body(json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8))
                .build();
    }

    public String getMethod() { return method; }
    public String getLogicalPath() { return logicalPath; }
    public String getContentType() { return contentType; }
    public Map<String, String> getProtectedHeaders() { return protectedHeaders; }
    public byte[] getBody() { return Arrays.copyOf(body, body.length); }
    public String getRequestId() { return requestId; }

    static String normalizeMethod(String value) {
        String method = value == null ? "GET" : value.toUpperCase(Locale.ROOT);
        if (!method.matches("[A-Z]{3,16}")) {
            throw new IllegalArgumentException("Invalid logical method");
        }
        return method;
    }

    static String normalizeContentType(String value) {
        String contentType = value == null || value.isBlank()
                ? "application/octet-stream"
                : value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!contentType.matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")) {
            throw new IllegalArgumentException("Invalid content type");
        }
        return contentType;
    }

    private static String requirePath(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("logicalPath is required");
        }
        return value;
    }

    private static Map<String, String> normalizeHeaders(Map<String, String> values) {
        Map<String, String> result = new LinkedHashMap<>();
        if (values == null) return Collections.emptyMap();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String name = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT);
            String value = entry.getValue();
            if (!name.matches("[a-z0-9-]{1,64}") || value == null || value.length() > 8192
                    || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("Invalid protected header");
            }
            result.put(name, value);
        }
        return Collections.unmodifiableMap(result);
    }

    private static String normalizeRequestId(String value) {
        String id = value == null || value.isEmpty() ? UUID.randomUUID().toString() : value;
        if (!id.matches("[\\x21-\\x7e]{1,128}")) {
            throw new IllegalArgumentException("Invalid request ID");
        }
        return id;
    }

    public static final class Builder {
        private String method = "GET";
        private String logicalPath;
        private String contentType = "application/octet-stream";
        private Map<String, String> protectedHeaders = Collections.emptyMap();
        private byte[] body = new byte[0];
        private String requestId;

        public Builder method(String value) { this.method = value; return this; }
        public Builder logicalPath(String value) { this.logicalPath = value; return this; }
        public Builder contentType(String value) { this.contentType = value; return this; }
        public Builder protectedHeaders(Map<String, String> value) { this.protectedHeaders = value; return this; }
        public Builder body(byte[] value) { this.body = value == null ? null : Arrays.copyOf(value, value.length); return this; }
        public Builder requestId(String value) { this.requestId = value; return this; }
        public SecureRequest build() { return new SecureRequest(this); }
    }
}
