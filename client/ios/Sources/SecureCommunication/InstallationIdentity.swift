import CryptoKit
import Foundation
import Security

public protocol InstallationIdentity: Sendable {
    var deviceID: String { get }
    func publicKeySPKI() throws -> Data
    func sign(_ data: Data) throws -> Data
}

public protocol IdentityStore: Sendable {
    func loadOrCreate(appID: String) throws -> any InstallationIdentity
}

private struct KeychainIdentity: InstallationIdentity {
    let deviceID: String
    let privateKey: P256.Signing.PrivateKey

    func publicKeySPKI() throws -> Data {
        var result = Data([
            0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86, 0x48, 0xce,
            0x3d, 0x02, 0x01, 0x06, 0x08, 0x2a, 0x86, 0x48, 0xce, 0x3d,
            0x03, 0x01, 0x07, 0x03, 0x42, 0x00
        ])
        result.append(privateKey.publicKey.x963Representation)
        return result
    }

    func sign(_ data: Data) throws -> Data {
        try privateKey.signature(for: data).rawRepresentation
    }
}

public final class KeychainIdentityStore: IdentityStore, @unchecked Sendable {
    private let service: String
    private let defaults: UserDefaults

    public init(
        service: String = "com.coolxer.securecommunication.v2.identity",
        defaults: UserDefaults = .standard
    ) {
        self.service = service
        self.defaults = defaults
    }

    public func loadOrCreate(appID: String) throws -> any InstallationIdentity {
        let privateKey: P256.Signing.PrivateKey
        if let stored = try read(account: appID) {
            privateKey = try P256.Signing.PrivateKey(rawRepresentation: stored)
        } else {
            privateKey = P256.Signing.PrivateKey()
            try write(privateKey.rawRepresentation, account: appID)
        }
        let deviceKey = "com.coolxer.securecommunication.v2.device.\(appID)"
        let deviceID: String
        if let stored = defaults.string(forKey: deviceKey) {
            deviceID = stored
        } else {
            deviceID = UUID().uuidString.lowercased()
            defaults.set(deviceID, forKey: deviceKey)
        }
        return KeychainIdentity(deviceID: deviceID, privateKey: privateKey)
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
            throw SecureError(code: "SC_IDENTITY_FAILED", message: "Keychain read failed")
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
            throw SecureError(code: "SC_IDENTITY_FAILED", message: "Keychain write failed")
        }
    }
}
