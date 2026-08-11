package com.coolxer.securecommunication.identity;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.spec.ECGenParameterSpec;

public final class AndroidIdentityKeyStore {
    private static final String PROVIDER = "AndroidKeyStore";

    public KeyPair getOrCreate(String alias) throws Exception {
        if (alias == null || alias.trim().isEmpty()) {
            throw new IllegalArgumentException("alias must not be blank");
        }
        KeyStore keyStore = KeyStore.getInstance(PROVIDER);
        keyStore.load(null);
        if (!keyStore.containsAlias(alias)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_EC, PROVIDER);
            generator.initialize(new KeyGenParameterSpec.Builder(
                    alias, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                    .build());
            generator.generateKeyPair();
        }
        KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry) keyStore.getEntry(
                alias, null);
        return new KeyPair(entry.getCertificate().getPublicKey(), entry.getPrivateKey());
    }
}
