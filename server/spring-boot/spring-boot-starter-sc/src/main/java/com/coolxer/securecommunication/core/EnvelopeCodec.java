package com.coolxer.securecommunication.core;

import com.coolxer.securecommunication.protocol.SecureEnvelope;
import com.coolxer.securecommunication.protocol.SecureProtocolException;

public interface EnvelopeCodec {
    SecureEnvelope decode(byte[] encoded) throws SecureProtocolException;

    byte[] encode(SecureEnvelope envelope) throws SecureProtocolException;
}
