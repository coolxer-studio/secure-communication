import CryptoKit
import Foundation

enum InternationalHandshake {
    static func verifyTranscript(
        hash: Data,
        p1363Signature: Data,
        serverSigningKey: P256.Signing.PublicKey
    ) throws {
        guard hash.count == 32,
              let signature = try? P256.Signing.ECDSASignature(
                rawRepresentation: p1363Signature),
              serverSigningKey.isValidSignature(signature, for: hash)
        else {
            throw SecureError.authenticationFailed
        }
    }

    static func deriveSession(
        keyID: String,
        sessionID: String,
        localEphemeralPrivateKey: P256.KeyAgreement.PrivateKey,
        peerEphemeralPublicKey: P256.KeyAgreement.PublicKey,
        transcriptHash: Data,
        expiresAt: Date
    ) throws -> SecureSession {
        guard transcriptHash.count == 32 else {
            throw SecureError.invalidConfiguration(
                "Transcript hash must contain 32 bytes")
        }
        let secret = try localEphemeralPrivateKey.sharedSecretFromKeyAgreement(
            with: peerEphemeralPublicKey)
        let info = Data(
            "SC1/session/\(SecureSuite.international.rawValue)/\(sessionID)".utf8)
        let output = secret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: transcriptHash,
            sharedInfo: info,
            outputByteCount: 72)
        let material = output.withUnsafeBytes { Data($0) }
        return try SecureSession(
            keyID: keyID,
            sessionID: sessionID,
            requestKey: SymmetricKey(data: material[0..<32]),
            responseKey: SymmetricKey(data: material[32..<64]),
            requestNoncePrefix: material[64..<68],
            responseNoncePrefix: material[68..<72],
            expiresAt: expiresAt)
    }
}
