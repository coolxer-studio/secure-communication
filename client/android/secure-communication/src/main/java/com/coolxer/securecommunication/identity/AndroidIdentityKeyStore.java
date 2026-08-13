package com.coolxer.securecommunication.identity;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.KeyPairGeneratorSpec;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import com.coolxer.securecommunication.IdentityStore;
import com.coolxer.securecommunication.InstallationIdentity;
import com.coolxer.securecommunication.SecureError;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Calendar;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.security.auth.x500.X500Principal;

/** Default v2 identity store with an API 21-compatible Keystore fallback. */
public final class AndroidIdentityKeyStore implements IdentityStore {
    private static final String PROVIDER = "AndroidKeyStore";
    private static final String PREFERENCES = "secure-communication-v2";
    private final Context context;

    public AndroidIdentityKeyStore(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public InstallationIdentity loadOrCreate(String appId) throws SecureError {
        try {
            SharedPreferences preferences = context.getSharedPreferences(
                    PREFERENCES, Context.MODE_PRIVATE);
            String deviceKey = "device." + appId;
            String deviceId = preferences.getString(deviceKey, null);
            if (deviceId == null) {
                deviceId = UUID.randomUUID().toString();
                if (!preferences.edit().putString(deviceKey, deviceId).commit()) {
                    throw new SecureError("SC_IDENTITY_FAILED", "Device ID could not be persisted");
                }
            }
            KeyPair pair = Build.VERSION.SDK_INT >= 23
                    ? loadOrCreateNativeEc(appId) : loadOrCreateWrappedEc(appId, preferences);
            return identity(deviceId, pair);
        } catch (SecureError error) {
            throw error;
        } catch (Exception exception) {
            throw new SecureError("SC_IDENTITY_FAILED", "Installation identity is unavailable",
                    0, null, exception);
        }
    }

    private static KeyPair loadOrCreateNativeEc(String appId) throws Exception {
        String alias = "sc2.installation." + appId;
        KeyStore keyStore = loadedKeyStore();
        if (!keyStore.containsAlias(alias)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_EC, PROVIDER);
            generator.initialize(new KeyGenParameterSpec.Builder(alias,
                    KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256).build());
            generator.generateKeyPair();
        }
        KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry) keyStore.getEntry(alias, null);
        return new KeyPair(entry.getCertificate().getPublicKey(), entry.getPrivateKey());
    }

    private KeyPair loadOrCreateWrappedEc(String appId, SharedPreferences preferences)
            throws Exception {
        String alias = "sc2.installation.wrap." + appId;
        KeyStore keyStore = loadedKeyStore();
        if (!keyStore.containsAlias(alias)) {
            Calendar start = Calendar.getInstance();
            Calendar end = Calendar.getInstance();
            end.add(Calendar.YEAR, 30);
            KeyPairGenerator wrapper = KeyPairGenerator.getInstance("RSA", PROVIDER);
            wrapper.initialize(new KeyPairGeneratorSpec.Builder(context)
                    .setAlias(alias)
                    .setSubject(new X500Principal("CN=Secure Communication v2"))
                    .setSerialNumber(BigInteger.ONE)
                    .setStartDate(start.getTime())
                    .setEndDate(end.getTime())
                    .build());
            wrapper.generateKeyPair();
        }
        KeyStore.PrivateKeyEntry wrapperEntry =
                (KeyStore.PrivateKeyEntry) keyStore.getEntry(alias, null);
        String privateKeyName = "wrapped.private." + appId;
        String publicKeyName = "wrapped.public." + appId;
        String wrappedPrivate = preferences.getString(privateKeyName, null);
        String publicKey = preferences.getString(publicKeyName, null);
        if (wrappedPrivate == null || publicKey == null) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair created = generator.generateKeyPair();
            Cipher wrapper = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            wrapper.init(Cipher.ENCRYPT_MODE, wrapperEntry.getCertificate().getPublicKey());
            String encodedPrivate = Base64.encodeToString(
                    wrapper.doFinal(created.getPrivate().getEncoded()),
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            String encodedPublic = Base64.encodeToString(created.getPublic().getEncoded(),
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            if (!preferences.edit().putString(privateKeyName, encodedPrivate)
                    .putString(publicKeyName, encodedPublic).commit()) {
                throw new SecureError("SC_IDENTITY_FAILED", "Wrapped identity could not be persisted");
            }
            return created;
        }
        Cipher unwrapper = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        unwrapper.init(Cipher.DECRYPT_MODE, wrapperEntry.getPrivateKey());
        byte[] privateBytes = unwrapper.doFinal(decode(wrappedPrivate));
        return new KeyPair(
                KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(decode(publicKey))),
                KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(privateBytes)));
    }

    private static KeyStore loadedKeyStore() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(PROVIDER);
        keyStore.load(null);
        return keyStore;
    }

    private static InstallationIdentity identity(final String deviceId, final KeyPair pair) {
        return new InstallationIdentity() {
            @Override public String deviceId() { return deviceId; }
            @Override public byte[] publicKeySpki() {
                byte[] encoded = pair.getPublic().getEncoded();
                return Arrays.copyOf(encoded, encoded.length);
            }
            @Override public byte[] sign(byte[] data) throws SecureError {
                try {
                    Signature signer = Signature.getInstance("SHA256withECDSA");
                    signer.initSign(pair.getPrivate());
                    signer.update(data);
                    return derToP1363(signer.sign());
                } catch (Exception exception) {
                    throw new SecureError("SC_IDENTITY_FAILED", "Installation signing failed",
                            0, null, exception);
                }
            }
        };
    }

    private static byte[] decode(String value) {
        return Base64.decode(value, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static byte[] derToP1363(byte[] der) {
        if (der.length < 8 || der[0] != 0x30) throw new IllegalArgumentException("Invalid signature");
        int offset = 2;
        if ((der[1] & 0x80) != 0) offset = 2 + (der[1] & 0x7f);
        if (der[offset++] != 0x02) throw new IllegalArgumentException("Invalid signature");
        int rLength = der[offset++] & 0xff;
        byte[] r = Arrays.copyOfRange(der, offset, offset + rLength);
        offset += rLength;
        if (der[offset++] != 0x02) throw new IllegalArgumentException("Invalid signature");
        int sLength = der[offset++] & 0xff;
        byte[] s = Arrays.copyOfRange(der, offset, offset + sLength);
        byte[] result = new byte[64];
        copy(r, result, 0);
        copy(s, result, 32);
        return result;
    }

    private static void copy(byte[] source, byte[] target, int offset) {
        int first = source.length > 32 && source[0] == 0 ? 1 : 0;
        int length = source.length - first;
        if (length > 32) throw new IllegalArgumentException("Invalid signature integer");
        System.arraycopy(source, first, target, offset + 32 - length, length);
    }
}
