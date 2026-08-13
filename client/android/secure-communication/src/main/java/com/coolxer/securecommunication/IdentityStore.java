package com.coolxer.securecommunication;

public interface IdentityStore {
    InstallationIdentity loadOrCreate(String appId) throws SecureError;
}
