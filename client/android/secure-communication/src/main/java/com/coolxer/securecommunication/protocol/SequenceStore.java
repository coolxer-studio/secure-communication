package com.coolxer.securecommunication.internal.protocol;

import com.coolxer.securecommunication.SecureError;

public interface SequenceStore {
    long next(String sessionId) throws SecureError;
}
