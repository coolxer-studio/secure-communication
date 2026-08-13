import CryptoKit
import Foundation

enum SecureSuite: String, Sendable, Codable {
    case international = "P256_HKDF_SHA256_AES256_GCM"
    case gm = "SM2_SM3_SM4_GCM"
}

struct SecureSession: Sendable {
    let keyID: String
    let sessionID: String
    let suite: SecureSuite
    let requestKey: SymmetricKey
    let responseKey: SymmetricKey
    let requestNoncePrefix: Data
    let responseNoncePrefix: Data
    let expiresAt: Date

    init(
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
    public let logicalPath: String
    public let contentType: String
    public let body: Data
    public let protectedHeaders: [String: String]
    public let requestID: String?

    public init(
        method: String = "GET",
        logicalPath: String,
        contentType: String = "application/octet-stream",
        protectedHeaders: [String: String] = [:],
        body: Data = Data(),
        requestID: String? = nil
    ) throws {
        let normalizedMethod = method.uppercased()
        guard normalizedMethod.range(
            of: #"^[A-Z]{3,16}$"#, options: .regularExpression) != nil
        else { throw SecureError.invalidConfiguration("Invalid method") }
        guard logicalPath.hasPrefix("/") && !logicalPath.contains("://")
            && !logicalPath.contains("#") && !logicalPath.contains("\r")
            && !logicalPath.contains("\n")
        else { throw SecureError.invalidConfiguration("Invalid logicalPath") }
        guard contentType.split(separator: ";", maxSplits: 1).first?
            .range(of: #"^[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+$"#,
                   options: .regularExpression) != nil
        else { throw SecureError.invalidConfiguration("Invalid contentType") }
        for (name, value) in protectedHeaders {
            guard name.lowercased().range(
                of: #"^[a-z0-9-]{1,64}$"#, options: .regularExpression) != nil,
                value.utf8.count <= 8192,
                !value.contains("\r"), !value.contains("\n")
            else { throw SecureError.invalidConfiguration("Invalid protected header") }
        }
        if let requestID {
            guard requestID.range(of: #"^[!-~]{1,128}$"#,
                                  options: .regularExpression) != nil
            else { throw SecureError.invalidConfiguration("Invalid request ID") }
        }
        self.method = normalizedMethod
        self.logicalPath = logicalPath
        self.contentType = contentType
        self.protectedHeaders = protectedHeaders
        self.body = body
        self.requestID = requestID
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

public struct SecureError: Error, @unchecked Sendable, Equatable {
    public let code: String
    public let httpStatus: Int
    public let traceID: String?
    public let cause: Error?
    public let message: String

    public init(
        code: String,
        message: String? = nil,
        httpStatus: Int = 0,
        traceID: String? = nil,
        cause: Error? = nil
    ) {
        self.code = code
        self.message = message ?? code
        self.httpStatus = httpStatus
        self.traceID = traceID
        self.cause = cause
    }

    public static func == (left: SecureError, right: SecureError) -> Bool {
        left.code == right.code && left.httpStatus == right.httpStatus
            && left.traceID == right.traceID
    }

    static func invalidConfiguration(_ message: String) -> SecureError {
        SecureError(code: "SC_INVALID_CONFIGURATION", message: message)
    }
    static let invalidEnvelope = SecureError(code: "SC_INVALID_ENVELOPE")
    static let unsupportedVersion = SecureError(code: "SC_UNSUPPORTED_VERSION")
    static let unsupportedSuite = SecureError(code: "SC_UNSUPPORTED_SUITE")
    static let unknownSession = SecureError(code: "SC_UNKNOWN_SESSION")
    static let requestExpired = SecureError(code: "SC_REQUEST_EXPIRED")
    static let replayDetected = SecureError(code: "SC_REPLAY_DETECTED")
    static let routeMismatch = SecureError(code: "SC_ROUTE_MISMATCH")
    static let authenticationFailed = SecureError(code: "SC_AUTHENTICATION_FAILED")
    static let sequenceExhausted = SecureError(code: "SC_SEQUENCE_EXHAUSTED")
    static let requestCancelled = SecureError(code: "SC_REQUEST_CANCELLED")
    static let requestTimeout = SecureError(code: "SC_REQUEST_TIMEOUT")
    static func transport(
        code: String, status: Int, traceID: String?, cause: Error? = nil
    ) -> SecureError {
        SecureError(code: code, httpStatus: status, traceID: traceID, cause: cause)
    }
}

protocol AlgorithmProvider: Sendable {
    var suite: SecureSuite { get }
}

/// The GM suite is intentionally provider-only. Applications must supply an
/// audited implementation and must not map the legacy H5 algorithm to this ID.
protocol GmAlgorithmProvider: AlgorithmProvider {
}
