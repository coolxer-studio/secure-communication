package com.coolxer.securecommunication;

/** Installation proof interface that supports non-exportable private keys. */
public interface InstallationIdentity {
    String deviceId();
    byte[] publicKeySpki();
    byte[] sign(byte[] data) throws SecureError;
}
