package com.coolxer.securecommunication.spi;

import com.coolxer.securecommunication.protocol.SecureProtocolException;

public interface SessionRecordProtector {
    byte[] protect(byte[] plaintext) throws SecureProtocolException;
    byte[] unprotect(byte[] protectedRecord) throws SecureProtocolException;
}
