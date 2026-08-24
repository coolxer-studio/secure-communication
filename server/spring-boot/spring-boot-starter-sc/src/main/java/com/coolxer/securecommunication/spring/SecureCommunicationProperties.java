package com.coolxer.securecommunication.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

@ConfigurationProperties(prefix = "spring.sc")
public class SecureCommunicationProperties {
    private boolean enabled;
    private V1 v1 = new V1();

    public static class V1 {
        private boolean enabled = true;
        private String prefix = "/sc/v1/message";
        private boolean requireTls;
        private Duration clockSkew = Duration.ofMinutes(5);
        private Duration replayTtl = Duration.ofMinutes(10);
        private Duration sessionTtl = Duration.ofMinutes(10);
        private int maxEnvelopeBytes = 1_400_000;
        private int maxPlaintextBytes = 1_048_576;
        private int maxBodyBytes = 1_048_576;
        private Set<String> allowedSuites = new LinkedHashSet<>(
                Set.of("P256_HKDF_SHA256_AES256_GCM"));

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getPrefix() { return prefix; }
        public void setPrefix(String prefix) { this.prefix = prefix; }
        public boolean isRequireTls() { return requireTls; }
        public void setRequireTls(boolean requireTls) { this.requireTls = requireTls; }
        public Duration getClockSkew() { return clockSkew; }
        public void setClockSkew(Duration clockSkew) { this.clockSkew = clockSkew; }
        public Duration getReplayTtl() { return replayTtl; }
        public void setReplayTtl(Duration replayTtl) { this.replayTtl = replayTtl; }
        public Duration getSessionTtl() { return sessionTtl; }
        public void setSessionTtl(Duration sessionTtl) { this.sessionTtl = sessionTtl; }
        public int getMaxEnvelopeBytes() { return maxEnvelopeBytes; }
        public void setMaxEnvelopeBytes(int maxEnvelopeBytes) { this.maxEnvelopeBytes = maxEnvelopeBytes; }
        public int getMaxPlaintextBytes() { return maxPlaintextBytes; }
        public void setMaxPlaintextBytes(int maxPlaintextBytes) { this.maxPlaintextBytes = maxPlaintextBytes; }
        public int getMaxBodyBytes() { return maxBodyBytes; }
        public void setMaxBodyBytes(int maxBodyBytes) { this.maxBodyBytes = maxBodyBytes; }
        public Set<String> getAllowedSuites() { return allowedSuites; }
        public void setAllowedSuites(Set<String> allowedSuites) { this.allowedSuites = allowedSuites; }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public V1 getV1() { return v1; }
    public void setV1(V1 v1) { this.v1 = v1; }
}
