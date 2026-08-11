package com.coolxer.securecommunication.core;

import com.coolxer.securecommunication.protocol.ProtocolConstants;
import com.coolxer.securecommunication.spi.SessionKeys;

import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Arrays;

/**
 * P-256 ECDH + HKDF-SHA-256 session derivation defined by protocol v1.
 * Transcript signature verification is performed before calling this class.
 */
public final class InternationalSessionKeyDeriver {
    private static final int OUTPUT_BYTES = 72;

    public SessionKeys derive(
            String keyId,
            String sessionId,
            PrivateKey localEphemeralPrivateKey,
            PublicKey peerEphemeralPublicKey,
            byte[] transcriptHash,
            Instant expiresAt) {
        try {
            if (transcriptHash == null || transcriptHash.length != 32) {
                throw new IllegalArgumentException(
                        "Transcript hash must contain exactly 32 bytes");
            }
            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(localEphemeralPrivateKey);
            agreement.doPhase(peerEphemeralPublicKey, true);
            byte[] sharedSecret = agreement.generateSecret();
            byte[] info = ("SC1/session/" + ProtocolConstants.INTERNATIONAL_SUITE
                    + "/" + sessionId).getBytes(StandardCharsets.UTF_8);
            byte[] material = hkdf(sharedSecret, transcriptHash, info, OUTPUT_BYTES);
            Arrays.fill(sharedSecret, (byte) 0);
            SessionKeys session = new SessionKeys(
                    keyId,
                    sessionId,
                    ProtocolConstants.INTERNATIONAL_SUITE,
                    Arrays.copyOfRange(material, 0, 32),
                    Arrays.copyOfRange(material, 32, 64),
                    Arrays.copyOfRange(material, 64, 68),
                    Arrays.copyOfRange(material, 68, 72),
                    expiresAt,
                    false);
            Arrays.fill(material, (byte) 0);
            return session;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("International session derivation failed", exception);
        }
    }

    static byte[] hkdf(byte[] inputKey, byte[] salt, byte[] info, int outputLength)
            throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] pseudoRandomKey = mac.doFinal(inputKey);
        byte[] output = new byte[outputLength];
        byte[] previous = new byte[0];
        int offset = 0;
        int counter = 1;
        while (offset < outputLength) {
            mac.init(new SecretKeySpec(pseudoRandomKey, "HmacSHA256"));
            mac.update(previous);
            mac.update(info);
            mac.update((byte) counter);
            previous = mac.doFinal();
            int length = Math.min(previous.length, outputLength - offset);
            System.arraycopy(previous, 0, output, offset, length);
            offset += length;
            counter += 1;
        }
        Arrays.fill(pseudoRandomKey, (byte) 0);
        Arrays.fill(previous, (byte) 0);
        return output;
    }
}
