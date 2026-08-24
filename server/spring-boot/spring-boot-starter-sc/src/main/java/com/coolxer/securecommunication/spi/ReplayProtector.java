package com.coolxer.securecommunication.spi;

import com.coolxer.securecommunication.protocol.Direction;
import com.coolxer.securecommunication.protocol.SecureProtocolException;

import java.time.Duration;

public interface ReplayProtector {
    boolean claim(String sessionId, Direction direction, long sequence, Duration ttl)
            throws SecureProtocolException;
}
