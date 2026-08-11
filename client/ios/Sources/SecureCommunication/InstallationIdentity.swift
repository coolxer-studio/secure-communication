import CryptoKit
import Foundation
import Security

public struct InstallationIdentity: Sendable {
    public let publicKey: P256.Signing.PublicKey
    let privateKey: P256.Signing.PrivateKey

    public func signature(for data: Data) throws -> Data {
        try privateKey.signature(for: data).rawRepresentation
    }
}

public final class InstallationIdentityStore: @unchecked Sendable {
    private let service: String

    public init(service: String = "com.coolxer.securecommunication.identity") {
        self.service = service
    }

    public func getOrCreate(account: String) throws -> InstallationIdentity {
        if let stored = try read(account: account) {
            let key = try P256.Signing.PrivateKey(rawRepresentation: stored)
            return InstallationIdentity(publicKey: key.publicKey, privateKey: key)
        }
        let key = P256.Signing.PrivateKey()
        try write(key.rawRepresentation, account: account)
        return InstallationIdentity(publicKey: key.publicKey, privateKey: key)
    }

    private func read(account: String) throws -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else {
            throw SecureError.invalidConfiguration("Keychain read failed")
        }
        return data
    }

    private func write(_ data: Data, account: String) throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            kSecValueData as String: data
        ]
        guard SecItemAdd(query as CFDictionary, nil) == errSecSuccess else {
            throw SecureError.invalidConfiguration("Keychain write failed")
        }
    }
}
