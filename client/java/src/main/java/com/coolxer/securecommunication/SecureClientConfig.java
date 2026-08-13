package com.coolxer.securecommunication;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Immutable protocol v1 client configuration. */
public final class SecureClientConfig {
    private final URI baseUrl;
    private final String appId;
    private final String deviceType;
    private final Map<String, String> serverTrustAnchors;
    private final IdentityStore identityStore;
    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final Duration allowedClockSkew;
    private final Clock clock;
    private final boolean allowInsecureLoopbackForTesting;

    private SecureClientConfig(Builder builder) {
        this.baseUrl = validateBaseUrl(builder.baseUrl, builder.allowInsecureLoopbackForTesting);
        if (builder.appId == null || !builder.appId.matches("[A-Za-z0-9._:@/-]{1,128}")) {
            throw new IllegalArgumentException("Invalid appId");
        }
        this.appId = builder.appId;
        this.deviceType = (builder.deviceType == null ? "HOST" : builder.deviceType)
                .toUpperCase(Locale.ROOT);
        if (!this.deviceType.matches("H5|HOST|SERVER|ANDROID|IOS|EMULATOR")) {
            throw new IllegalArgumentException("Invalid deviceType");
        }
        if (builder.serverTrustAnchors == null || builder.serverTrustAnchors.isEmpty()) {
            throw new IllegalArgumentException("serverTrustAnchors are required");
        }
        this.serverTrustAnchors = Collections.unmodifiableMap(
                new LinkedHashMap<>(builder.serverTrustAnchors));
        if (builder.identityStore == null) {
            throw new IllegalArgumentException("identityStore is required");
        }
        this.identityStore = builder.identityStore;
        this.httpClient = builder.httpClient;
        this.requestTimeout = requirePositive(builder.requestTimeout, "requestTimeout");
        if (builder.allowedClockSkew == null || builder.allowedClockSkew.isNegative()) {
            throw new IllegalArgumentException("allowedClockSkew must not be negative");
        }
        this.allowedClockSkew = builder.allowedClockSkew;
        this.clock = builder.clock == null ? Clock.systemUTC() : builder.clock;
        this.allowInsecureLoopbackForTesting = builder.allowInsecureLoopbackForTesting;
    }

    public static Builder builder() { return new Builder(); }
    public URI getBaseUrl() { return baseUrl; }
    public String getAppId() { return appId; }
    public String getDeviceType() { return deviceType; }
    public Map<String, String> getServerTrustAnchors() { return serverTrustAnchors; }
    public IdentityStore getIdentityStore() { return identityStore; }
    public HttpClient getHttpClient() { return httpClient; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public Duration getAllowedClockSkew() { return allowedClockSkew; }
    public Clock getClock() { return clock; }
    public boolean isAllowInsecureLoopbackForTesting() { return allowInsecureLoopbackForTesting; }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static URI validateBaseUrl(URI value, boolean allowLoopback) {
        if (value == null || value.getHost() == null || value.getUserInfo() != null
                || value.getFragment() != null || value.getQuery() != null) {
            throw new IllegalArgumentException("Invalid baseUrl");
        }
        String scheme = value.getScheme() == null ? "" : value.getScheme().toLowerCase(Locale.ROOT);
        if ("https".equals(scheme)) return value;
        if (!"http".equals(scheme) || !allowLoopback || !isLoopback(value.getHost())) {
            throw new IllegalArgumentException("baseUrl must use HTTPS");
        }
        return value;
    }

    private static boolean isLoopback(String host) {
        if ("localhost".equalsIgnoreCase(host) || "::1".equals(host)
                || "0:0:0:0:0:0:0:1".equals(host)) return true;
        if (!host.matches("127(?:\\.[0-9]{1,3}){3}")) return false;
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (Exception ignored) {
            return false;
        }
    }

    public static final class Builder {
        private URI baseUrl;
        private String appId;
        private String deviceType = "HOST";
        private Map<String, String> serverTrustAnchors;
        private IdentityStore identityStore;
        private HttpClient httpClient;
        private Duration requestTimeout = Duration.ofSeconds(15);
        private Duration allowedClockSkew = Duration.ofMinutes(2);
        private Clock clock = Clock.systemUTC();
        private boolean allowInsecureLoopbackForTesting;

        public Builder baseUrl(URI value) { this.baseUrl = value; return this; }
        public Builder appId(String value) { this.appId = value; return this; }
        public Builder deviceType(String value) { this.deviceType = value; return this; }
        public Builder serverTrustAnchors(Map<String, String> value) { this.serverTrustAnchors = value; return this; }
        public Builder identityStore(IdentityStore value) { this.identityStore = value; return this; }
        public Builder httpClient(HttpClient value) { this.httpClient = value; return this; }
        public Builder requestTimeout(Duration value) { this.requestTimeout = value; return this; }
        public Builder allowedClockSkew(Duration value) { this.allowedClockSkew = value; return this; }
        public Builder clock(Clock value) { this.clock = value; return this; }
        public Builder allowInsecureLoopbackForTesting(boolean value) { this.allowInsecureLoopbackForTesting = value; return this; }
        public SecureClientConfig build() { return new SecureClientConfig(this); }
    }
}
