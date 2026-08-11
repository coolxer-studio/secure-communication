package com.coolxer.securecommunication.protocol;

import android.content.SharedPreferences;

import com.coolxer.securecommunication.SecureError;

/**
 * Persists a sequence before it is used. Applications using multiple processes
 * must supply a process-safe SequenceStore instead.
 */
public final class AndroidSequenceStore implements SequenceStore {
    private final SharedPreferences preferences;

    public AndroidSequenceStore(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    @Override
    public synchronized long next(String sessionId) throws SecureError {
        String key = "sc1.sequence." + sessionId;
        long current = preferences.getLong(key, 0L);
        if (current >= 9_007_199_254_740_991L) {
            throw new SecureError("SC_SEQUENCE_EXHAUSTED", "Session sequence is exhausted");
        }
        long next = current + 1L;
        if (!preferences.edit().putLong(key, next).commit()) {
            throw new SecureError(
                    "SC_SEQUENCE_PERSIST_FAILED", "Sequence could not be persisted");
        }
        return next;
    }
}
