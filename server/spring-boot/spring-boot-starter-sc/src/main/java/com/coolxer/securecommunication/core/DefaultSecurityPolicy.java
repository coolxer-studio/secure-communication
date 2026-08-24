package com.coolxer.securecommunication.core;

import com.coolxer.securecommunication.protocol.ProtocolConstants;
import com.coolxer.securecommunication.spi.SecurityPolicy;

import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class DefaultSecurityPolicy implements SecurityPolicy {
    private final Set<String> allowedSuites;
    private final boolean requireTls;
    private final Duration clockSkew;
    private final Duration replayTtl;
    private final int maxEnvelopeBytes;
    private final int maxPlaintextBytes;
    private final int maxBodyBytes;
    private final Clock clock;

    public DefaultSecurityPolicy(
            Set<String> allowedSuites,
            boolean requireTls,
            Duration clockSkew,
            Duration replayTtl,
            int maxEnvelopeBytes,
            int maxPlaintextBytes) {
        this(allowedSuites, requireTls, clockSkew, replayTtl,
                maxEnvelopeBytes, maxPlaintextBytes, maxPlaintextBytes, Clock.systemUTC());
    }

    public DefaultSecurityPolicy(
            Set<String> allowedSuites,
            boolean requireTls,
            Duration clockSkew,
            Duration replayTtl,
            int maxEnvelopeBytes,
            int maxPlaintextBytes,
            Clock clock) {
        this(allowedSuites, requireTls, clockSkew, replayTtl,
                maxEnvelopeBytes, maxPlaintextBytes, maxPlaintextBytes, clock);
    }

    public DefaultSecurityPolicy(
            Set<String> allowedSuites,
            boolean requireTls,
            Duration clockSkew,
            Duration replayTtl,
            int maxEnvelopeBytes,
            int maxPlaintextBytes,
            int maxBodyBytes,
            Clock clock) {
        Set<String> suites = allowedSuites == null || allowedSuites.isEmpty()
                ? Set.of(ProtocolConstants.INTERNATIONAL_SUITE)
                : new HashSet<>(allowedSuites);
        if (clockSkew == null || clockSkew.isNegative() || replayTtl == null
                || replayTtl.isNegative() || replayTtl.isZero()
                || maxEnvelopeBytes < 256 || maxPlaintextBytes < 0
                || maxBodyBytes < 0 || maxBodyBytes > maxPlaintextBytes || clock == null) {
            throw new IllegalArgumentException("Invalid secure communication policy");
        }
        this.allowedSuites = Collections.unmodifiableSet(suites);
        this.requireTls = requireTls;
        this.clockSkew = clockSkew;
        this.replayTtl = replayTtl;
        this.maxEnvelopeBytes = maxEnvelopeBytes;
        this.maxPlaintextBytes = maxPlaintextBytes;
        this.maxBodyBytes = maxBodyBytes;
        this.clock = clock;
    }

    @Override
    public boolean allowsSuite(String suite) {
        return allowedSuites.contains(suite);
    }

    @Override
    public boolean requireTls() {
        return requireTls;
    }

    @Override
    public Duration clockSkew() {
        return clockSkew;
    }

    @Override
    public Duration replayTtl() {
        return replayTtl;
    }

    @Override
    public int maxEnvelopeBytes() {
        return maxEnvelopeBytes;
    }

    @Override
    public int maxPlaintextBytes() {
        return maxPlaintextBytes;
    }

    @Override public int maxBodyBytes() { return maxBodyBytes; }

    @Override
    public Clock clock() {
        return clock;
    }
}
