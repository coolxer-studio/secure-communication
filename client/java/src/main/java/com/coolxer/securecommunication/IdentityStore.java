package com.coolxer.securecommunication;

/** Loads one stable installation identity per application namespace. */
public interface IdentityStore {
    InstallationIdentity loadOrCreate(String appId) throws SecureError;
}
