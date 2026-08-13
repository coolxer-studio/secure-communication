package com.coolxer.securecommunication;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

public final class SecureClientConfig {
    private final String baseUrl;
    private final String appId;
    private final String deviceType;
    private final Map<String, String> serverTrustAnchors;
    private final IdentityStore identityStore;
    private final OkHttpClient httpClient;
    private final long requestTimeoutMillis;
    private final long allowedClockSkewMillis;
    private final boolean allowInsecureLoopbackForTesting;

    private SecureClientConfig(Builder builder) {
        HttpUrl parsed = HttpUrl.parse(builder.baseUrl);
        if (parsed == null || parsed.username().length() != 0 || parsed.password().length() != 0
                || parsed.query() != null || parsed.fragment() != null) {
            throw new IllegalArgumentException("Invalid baseUrl");
        }
        boolean loopback = isLoopback(parsed.host());
        if (!"https".equals(parsed.scheme())
                && !(builder.allowInsecureLoopbackForTesting
                && "http".equals(parsed.scheme()) && loopback)) {
            throw new IllegalArgumentException("baseUrl must use HTTPS");
        }
        if (builder.appId == null || !builder.appId.matches("[A-Za-z0-9._:@/-]{1,128}")) {
            throw new IllegalArgumentException("Invalid appId");
        }
        String normalizedDeviceType = (builder.deviceType == null ? "ANDROID" : builder.deviceType)
                .toUpperCase(Locale.ROOT);
        if (!normalizedDeviceType.matches("H5|HOST|SERVER|ANDROID|IOS|EMULATOR")) {
            throw new IllegalArgumentException("Invalid deviceType");
        }
        if (builder.serverTrustAnchors == null || builder.serverTrustAnchors.isEmpty()) {
            throw new IllegalArgumentException("serverTrustAnchors are required");
        }
        if (builder.requestTimeoutMillis <= 0 || builder.allowedClockSkewMillis < 0) {
            throw new IllegalArgumentException("Invalid timeout configuration");
        }
        this.baseUrl = parsed.toString().replaceAll("/+$", "");
        this.appId = builder.appId;
        this.deviceType = normalizedDeviceType;
        this.serverTrustAnchors = Collections.unmodifiableMap(
                new LinkedHashMap<>(builder.serverTrustAnchors));
        this.identityStore = builder.identityStore;
        this.httpClient = builder.httpClient;
        this.requestTimeoutMillis = builder.requestTimeoutMillis;
        this.allowedClockSkewMillis = builder.allowedClockSkewMillis;
        this.allowInsecureLoopbackForTesting = builder.allowInsecureLoopbackForTesting;
    }

    public static Builder builder() { return new Builder(); }
    public String getBaseUrl() { return baseUrl; }
    public String getAppId() { return appId; }
    public String getDeviceType() { return deviceType; }
    public Map<String, String> getServerTrustAnchors() { return serverTrustAnchors; }
    public IdentityStore getIdentityStore() { return identityStore; }
    public OkHttpClient getHttpClient() { return httpClient; }
    public long getRequestTimeoutMillis() { return requestTimeoutMillis; }
    public long getAllowedClockSkewMillis() { return allowedClockSkewMillis; }
    public boolean isAllowInsecureLoopbackForTesting() { return allowInsecureLoopbackForTesting; }

    private static boolean isLoopback(String host) {
        if ("localhost".equalsIgnoreCase(host) || "::1".equals(host)) return true;
        if (!host.matches("127(?:\\.[0-9]{1,3}){3}")) return false;
        String[] parts = host.split("\\.");
        for (String part : parts) {
            if (Integer.parseInt(part) > 255) return false;
        }
        return true;
    }

    public static final class Builder {
        private String baseUrl;
        private String appId;
        private String deviceType = "ANDROID";
        private Map<String, String> serverTrustAnchors;
        private IdentityStore identityStore;
        private OkHttpClient httpClient;
        private long requestTimeoutMillis = 15_000;
        private long allowedClockSkewMillis = 120_000;
        private boolean allowInsecureLoopbackForTesting;

        public Builder baseUrl(String value) { baseUrl = value; return this; }
        public Builder appId(String value) { appId = value; return this; }
        public Builder deviceType(String value) { deviceType = value; return this; }
        public Builder serverTrustAnchors(Map<String, String> value) { serverTrustAnchors = value; return this; }
        public Builder identityStore(IdentityStore value) { identityStore = value; return this; }
        public Builder httpClient(OkHttpClient value) { httpClient = value; return this; }
        public Builder requestTimeoutMillis(long value) { requestTimeoutMillis = value; return this; }
        public Builder allowedClockSkewMillis(long value) { allowedClockSkewMillis = value; return this; }
        public Builder allowInsecureLoopbackForTesting(boolean value) {
            allowInsecureLoopbackForTesting = value; return this;
        }
        public SecureClientConfig build() { return new SecureClientConfig(this); }
    }
}
