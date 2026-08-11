package com.coolxer.securecommunication.protocol;

import com.coolxer.securecommunication.SecureError;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;

import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class InternationalHandshake {
    private InternationalHandshake() {
    }

    public static KeyPair createEphemeralKeyPair() throws SecureError {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (Exception exception) {
            throw new SecureError(
                    "SC_HANDSHAKE_FAILED", "Unable to create P-256 key pair",
                    0, null, exception);
        }
    }

    public static boolean verifyTranscriptSignature(
            PublicKey serverSigningKey, byte[] transcriptHash, byte[] p1363Signature)
            throws SecureError {
        try {
            if (p1363Signature == null || p1363Signature.length != 64) {
                return false;
            }
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(serverSigningKey);
            verifier.update(transcriptHash);
            return verifier.verify(p1363ToDer(p1363Signature));
        } catch (Exception exception) {
            throw new SecureError(
                    "SC_HANDSHAKE_FAILED", "Unable to verify handshake transcript",
                    0, null, exception);
        }
    }

    private static byte[] p1363ToDer(byte[] signature) {
        byte[] r = unsignedInteger(Arrays.copyOfRange(signature, 0, 32));
        byte[] s = unsignedInteger(Arrays.copyOfRange(signature, 32, 64));
        byte[] result = new byte[2 + 2 + r.length + 2 + s.length];
        int offset = 0;
        result[offset++] = 0x30;
        result[offset++] = (byte) (result.length - 2);
        result[offset++] = 0x02;
        result[offset++] = (byte) r.length;
        System.arraycopy(r, 0, result, offset, r.length);
        offset += r.length;
        result[offset++] = 0x02;
        result[offset++] = (byte) s.length;
        System.arraycopy(s, 0, result, offset, s.length);
        return result;
    }

    private static byte[] unsignedInteger(byte[] value) {
        int first = 0;
        while (first < value.length - 1 && value[first] == 0) {
            first += 1;
        }
        boolean needsLeadingZero = (value[first] & 0x80) != 0;
        byte[] result = new byte[value.length - first + (needsLeadingZero ? 1 : 0)];
        System.arraycopy(value, first, result, needsLeadingZero ? 1 : 0,
                value.length - first);
        return result;
    }

    public static SecureSession deriveSession(
            String keyId,
            String sessionId,
            PrivateKey localEphemeralPrivateKey,
            PublicKey peerEphemeralPublicKey,
            byte[] transcriptHash,
            long expiresAtEpochMillis) throws SecureError {
        if (transcriptHash == null || transcriptHash.length != 32) {
            throw new SecureError(
                    "SC_HANDSHAKE_FAILED", "Transcript hash must contain 32 bytes");
        }
        try {
            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(localEphemeralPrivateKey);
            agreement.doPhase(peerEphemeralPublicKey, true);
            byte[] sharedSecret = agreement.generateSecret();
            byte[] info = ("SC1/session/" + SecureSession.INTERNATIONAL_SUITE
                    + "/" + sessionId).getBytes(StandardCharsets.UTF_8);
            byte[] material = hkdf(sharedSecret, transcriptHash, info, 72);
            Arrays.fill(sharedSecret, (byte) 0);
            SecureSession session = new SecureSession(
                    keyId,
                    sessionId,
                    SecureSession.INTERNATIONAL_SUITE,
                    new SecretKeySpec(Arrays.copyOfRange(material, 0, 32), "AES"),
                    new SecretKeySpec(Arrays.copyOfRange(material, 32, 64), "AES"),
                    Arrays.copyOfRange(material, 64, 68),
                    Arrays.copyOfRange(material, 68, 72),
                    expiresAtEpochMillis);
            Arrays.fill(material, (byte) 0);
            return session;
        } catch (Exception exception) {
            throw new SecureError(
                    "SC_HANDSHAKE_FAILED", "Session key derivation failed",
                    0, null, exception);
        }
    }

    private static byte[] hkdf(
            byte[] inputKey, byte[] salt, byte[] info, int outputLength)
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
            int count = Math.min(previous.length, outputLength - offset);
            System.arraycopy(previous, 0, output, offset, count);
            offset += count;
            counter += 1;
        }
        Arrays.fill(pseudoRandomKey, (byte) 0);
        Arrays.fill(previous, (byte) 0);
        return output;
    }
}
