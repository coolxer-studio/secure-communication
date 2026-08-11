package com.coolxer.securecommunication.protocol;

import com.coolxer.securecommunication.SecureError;

public interface SequenceStore {
    long next(String sessionId) throws SecureError;
}
