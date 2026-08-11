package com.coolxer.securecommunication.spi;

import com.coolxer.securecommunication.protocol.SecureProtocolException;
import java.time.Duration;

public interface EnrollmentTokenService {
    String issue(String appId, String deviceType, Duration ttl)
            throws SecureProtocolException;

    void consume(String token, String appId, String deviceId, String deviceType)
            throws SecureProtocolException;
}
