package com.coolxer.securecommunication.protocol;

import java.nio.ByteBuffer;
import java.util.Arrays;

public final class NonceFactory {
    private NonceFactory() {
    }

    public static byte[] create(byte[] prefix, long sequence) throws SecureProtocolException {
        if (prefix == null || prefix.length != 4 || sequence < 1) {
            throw new SecureProtocolException(SecureErrorCode.INVALID_ENVELOPE);
        }
        ByteBuffer buffer = ByteBuffer.allocate(ProtocolConstants.NONCE_BYTES);
        buffer.put(prefix);
        buffer.putLong(sequence);
        return buffer.array();
    }

    public static void requireMatches(byte[] prefix, long sequence, byte[] nonce)
            throws SecureProtocolException {
        if (!Arrays.equals(create(prefix, sequence), nonce)) {
            throw new SecureProtocolException(SecureErrorCode.INVALID_ENVELOPE);
        }
    }
}
