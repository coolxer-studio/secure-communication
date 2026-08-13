package com.coolxer.securecommunication;

public interface InstallationIdentity {
    String deviceId();
    byte[] publicKeySpki();
    byte[] sign(byte[] data) throws SecureError;
}
