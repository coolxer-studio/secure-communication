package com.coolxer.securecommunication.internal;

import com.coolxer.securecommunication.SecureError;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.AlgorithmParameters;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

public final class CryptoSupport {
    private static final ECParameterSpec P256 = p256Parameters();
    private CryptoSupport() { }

    public static KeyPair generateP256() throws SecureError {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (Exception exception) {
            throw cryptoFailure("Unable to generate P-256 key pair", exception);
        }
    }

    public static PublicKey parseP256Public(byte[] encoded) throws SecureError {
        try {
            PublicKey key = KeyFactory.getInstance("EC")
                    .generatePublic(new X509EncodedKeySpec(encoded));
            requireP256(key);
            return key;
        } catch (Exception exception) {
            throw cryptoFailure("Invalid P-256 public key", exception);
        }
    }

    public static void requireP256(java.security.Key key) {
        if (!(key instanceof ECKey ecKey) || ecKey.getParams() == null
                || !sameParameters(ecKey.getParams(), P256)) {
            throw new IllegalArgumentException("Key is not P-256");
        }
    }

    private static ECParameterSpec p256Parameters() {
        try {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            return parameters.getParameterSpec(ECParameterSpec.class);
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static boolean sameParameters(ECParameterSpec left, ECParameterSpec right) {
        return left.getCofactor() == right.getCofactor()
                && left.getOrder().equals(right.getOrder())
                && left.getGenerator().equals(right.getGenerator())
                && left.getCurve().equals(right.getCurve());
    }

    public static byte[] signP1363(PrivateKey key, byte[] data) throws SecureError {
        try {
            requireP256(key);
            Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
            signer.initSign(key);
            signer.update(data);
            byte[] signature = signer.sign();
            if (signature.length != 64) throw new IllegalArgumentException("Invalid P1363 signature");
            return signature;
        } catch (Exception exception) {
            throw cryptoFailure("Installation signing failed", exception);
        }
    }

    public static boolean verifyP1363(PublicKey key, byte[] data, byte[] signature)
            throws SecureError {
        if (signature == null || signature.length != 64) return false;
        try {
            requireP256(key);
            Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
            verifier.initVerify(key);
            verifier.update(data);
            return verifier.verify(signature);
        } catch (Exception exception) {
            throw cryptoFailure("P-256 signature verification failed", exception);
        }
    }

    public static byte[] deriveMaterial(
            PrivateKey localPrivate, PublicKey peerPublic, byte[] transcriptHash,
            String suite, String sessionId) throws SecureError {
        byte[] secret = null;
        try {
            requireP256(localPrivate);
            requireP256(peerPublic);
            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(localPrivate);
            agreement.doPhase(peerPublic, true);
            secret = agreement.generateSecret();
            byte[] info = ("SC1/session/" + suite + "/" + sessionId)
                    .getBytes(StandardCharsets.UTF_8);
            return hkdf(secret, transcriptHash, info, 72);
        } catch (Exception exception) {
            throw cryptoFailure("Session key derivation failed", exception);
        } finally {
            if (secret != null) Arrays.fill(secret, (byte) 0);
        }
    }

    static byte[] hkdf(byte[] input, byte[] salt, byte[] info, int length) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(input);
        byte[] output = new byte[length];
        byte[] previous = new byte[0];
        int offset = 0;
        int counter = 1;
        while (offset < length) {
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            mac.update(previous);
            mac.update(info);
            mac.update((byte) counter++);
            previous = mac.doFinal();
            int count = Math.min(previous.length, length - offset);
            System.arraycopy(previous, 0, output, offset, count);
            offset += count;
        }
        Arrays.fill(prk, (byte) 0);
        Arrays.fill(previous, (byte) 0);
        return output;
    }

    public static byte[] crypt(
            int mode, byte[] key, byte[] nonce, byte[] aad, byte[] input) throws SecureError {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad);
            return cipher.doFinal(input);
        } catch (AEADBadTagException exception) {
            throw new SecureError("SC_AUTHENTICATION_FAILED",
                    "Response authentication failed", 0, null, exception);
        } catch (Exception exception) {
            throw cryptoFailure("Cryptographic operation failed", exception);
        }
    }

    public static byte[] nonce(byte[] prefix, long sequence) {
        return ByteBuffer.allocate(12).put(prefix).putLong(sequence).array();
    }

    public static byte[] sha256(byte[] value) throws SecureError {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (Exception exception) {
            throw cryptoFailure("SHA-256 is unavailable", exception);
        }
    }

    public static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public static byte[] decode(String value, boolean allowEmpty) {
        if (value == null || (!allowEmpty && value.isEmpty())
                || !value.matches(allowEmpty ? "[A-Za-z0-9_-]*" : "[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid Base64URL");
        }
        return Base64.getUrlDecoder().decode(value);
    }

    public static boolean constantTimeEquals(byte[] left, byte[] right) {
        return left != null && right != null && MessageDigest.isEqual(left, right);
    }

    private static SecureError cryptoFailure(String message, Throwable cause) {
        return new SecureError("SC_CRYPTO_FAILED", message, 0, null, cause);
    }
}
