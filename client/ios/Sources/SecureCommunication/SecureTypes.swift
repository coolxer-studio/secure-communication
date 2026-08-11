import CryptoKit
import Foundation

public enum SecureSuite: String, Sendable, Codable {
    case international = "P256_HKDF_SHA256_AES256_GCM"
    case gm = "SM2_SM3_SM4_GCM"
}

public struct SecureSession: Sendable {
    public let keyID: String
    public let sessionID: String
    public let suite: SecureSuite
    let requestKey: SymmetricKey
    let responseKey: SymmetricKey
    let requestNoncePrefix: Data
    let responseNoncePrefix: Data
    public let expiresAt: Date

    public init(
        keyID: String,
        sessionID: String,
        suite: SecureSuite = .international,
        requestKey: SymmetricKey,
        responseKey: SymmetricKey,
        requestNoncePrefix: Data,
        responseNoncePrefix: Data,
        expiresAt: Date
    ) throws {
        guard !keyID.isEmpty, !sessionID.isEmpty,
              requestNoncePrefix.count == 4, responseNoncePrefix.count == 4
        else {
            throw SecureError.invalidConfiguration("Invalid session material")
        }
        self.keyID = keyID
        self.sessionID = sessionID
        self.suite = suite
        self.requestKey = requestKey
        self.responseKey = responseKey
        self.requestNoncePrefix = requestNoncePrefix
        self.responseNoncePrefix = responseNoncePrefix
        self.expiresAt = expiresAt
    }
}

public struct SecureRequest: Sendable {
    public let method: String
    public let path: String
    public let contentType: String
    public let body: Data
    public let protectedHeaders: [String: String]
    public let requestID: String

    public init(
        method: String = "GET",
        path: String,
        contentType: String = "application/octet-stream",
        protectedHeaders: [String: String] = [:],
        body: Data = Data(),
        requestID: String = UUID().uuidString.lowercased()
    ) {
        self.method = method
        self.path = path
        self.contentType = contentType
        self.protectedHeaders = protectedHeaders
        self.body = body
        self.requestID = requestID.isEmpty ? UUID().uuidString.lowercased() : requestID
    }
}

public struct SecureResponse: Sendable {
    public let status: Int
    public let contentType: String
    public let body: Data

    public func text() throws -> String {
        guard let value = String(data: body, encoding: .utf8) else {
            throw SecureError.invalidEnvelope
        }
        return value
    }
}

public enum SecureError: Error, Sendable, Equatable {
    case invalidConfiguration(String)
    case invalidEnvelope
    case unsupportedVersion
    case unsupportedSuite
    case unknownSession
    case requestExpired
    case replayDetected
    case routeMismatch
    case authenticationFailed
    case sequenceExhausted
    case transport(code: String, status: Int, traceID: String?)
}

public protocol AlgorithmProvider: Sendable {
    var suite: SecureSuite { get }
}

/// The GM suite is intentionally provider-only. Applications must supply an
/// audited implementation and must not map the legacy H5 algorithm to this ID.
public protocol GmAlgorithmProvider: AlgorithmProvider {
}
