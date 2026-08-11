package com.coolxer.securecommunication.spi;

import java.time.Clock;
import java.time.Duration;

public interface SecurityPolicy {
    boolean allowsSuite(String suite);

    boolean requireTls();

    Duration clockSkew();

    Duration replayTtl();

    int maxEnvelopeBytes();

    int maxPlaintextBytes();

    default int maxBodyBytes() { return maxPlaintextBytes(); }

    Clock clock();
}
