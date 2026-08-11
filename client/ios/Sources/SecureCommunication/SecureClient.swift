import CryptoKit
import Foundation

public struct SecureClientConfig: Sendable {
    public let baseURL: URL
    public let appID: String
    public let deviceType: String
    public let serverTrustAnchors: [String: Data]
    public let identityStore: InstallationIdentityStore
    public let requestTimeout: TimeInterval
    public let allowedClockSkew: TimeInterval

    public init(
        baseURL: URL,
        appID: String,
        serverTrustAnchors: [String: Data],
        deviceType: String = "IOS",
        identityStore: InstallationIdentityStore = InstallationIdentityStore(),
        requestTimeout: TimeInterval = 15,
        allowedClockSkew: TimeInterval = 120
    ) {
        self.baseURL = baseURL
        self.appID = appID
        self.deviceType = deviceType.uppercased()
        self.serverTrustAnchors = serverTrustAnchors
        self.identityStore = identityStore
        self.requestTimeout = requestTimeout
        self.allowedClockSkew = allowedClockSkew
    }
}

/// High-level protocol v1 client. Business requests are never retried by the SDK.
public actor SecureClient {
    private let config: SecureClientConfig
    private let network: URLSession
    private let sequenceStore: PersistentSequenceStore
    private var enrollmentToken: String?
    private var activeSession: SecureSession?
    private var messageClient: SecureCommunicationClient?

    public init(config: SecureClientConfig, session: URLSession? = nil) throws {
        guard config.baseURL.scheme?.lowercased() == "https",
              !config.appID.isEmpty, !config.serverTrustAnchors.isEmpty,
              config.deviceType == "IOS"
        else { throw SecureError.invalidConfiguration("Invalid protocol v1 configuration") }
        self.config = config
        self.sequenceStore = PersistentSequenceStore(
            namespace: "com.coolxer.securecommunication.v1.\(config.appID)")
        if let session {
            self.network = session
        } else {
            let settings = URLSessionConfiguration.ephemeral
            settings.timeoutIntervalForRequest = config.requestTimeout
            settings.timeoutIntervalForResource = config.requestTimeout
            settings.tlsMinimumSupportedProtocolVersion = .TLSv12
            settings.requestCachePolicy = .reloadIgnoringLocalAndRemoteCacheData
            self.network = URLSession(configuration: settings)
        }
    }

    public func enroll(_ token: String) throws {
        guard !token.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        else { throw SecureError.transport(code: "SC_ENROLLMENT_REQUIRED", status: 0, traceID: nil) }
        enrollmentToken = token
    }

    public func initialize() async throws {
        if let activeSession, activeSession.expiresAt > Date(), messageClient != nil {
            return
        }
        do {
            let identity = try config.identityStore.getOrCreate(account: config.appID)
            let deviceID = try Self.deviceID(appID: config.appID)
            let ephemeral = P256.KeyAgreement.PrivateKey()
            let request = HandshakeStart(
                v: 1,
                suite: SecureSuite.international.rawValue,
                appId: config.appID,
                deviceId: deviceID,
                deviceType: config.deviceType,
                clientEphemeralPublicKey: Self.spki(ephemeral.publicKey.x963Representation).base64URL,
                installationPublicKey: Self.spki(identity.publicKey.x963Representation).base64URL,
                enrollmentToken: enrollmentToken,
                timestamp: Int64(Date().timeIntervalSince1970 * 1_000))
            let response: HandshakeStartResponse = try await post(
                path: "/sc/v1/handshake", value: request)
            guard response.v == 1,
                  response.suite == SecureSuite.international.rawValue,
                  let serverIdentity = Data(base64URL: response.serverIdentityPublicKey),
                  let pinned = config.serverTrustAnchors[response.kid],
                  pinned == serverIdentity,
                  let serverEphemeral = Data(base64URL: response.serverEphemeralPublicKey),
                  let serverSignature = Data(base64URL: response.signature)
            else { throw SecureError.authenticationFailed }

            let transcript = [
                "SC1-HANDSHAKE", "1", SecureSuite.international.rawValue,
                config.appID, deviceID, config.deviceType,
                request.clientEphemeralPublicKey, request.installationPublicKey,
                serverIdentity.base64URL, serverEphemeral.base64URL,
                response.kid, response.sid, String(response.createdAt),
                String(response.expiresAt)
            ].joined(separator: "\n")
            let transcriptHash = Data(SHA256.hash(data: Data(transcript.utf8)))
            try InternationalHandshake.verifyTranscript(
                hash: transcriptHash,
                p1363Signature: serverSignature,
                serverSigningKey: try P256.Signing.PublicKey(
                    x963Representation: Self.x963(serverIdentity)))
            let established = try InternationalHandshake.deriveSession(
                keyID: response.kid,
                sessionID: response.sid,
                localEphemeralPrivateKey: ephemeral,
                peerEphemeralPublicKey: try P256.KeyAgreement.PublicKey(
                    x963Representation: Self.x963(serverEphemeral)),
                transcriptHash: transcriptHash,
                expiresAt: Date(timeIntervalSince1970: TimeInterval(response.expiresAt) / 1_000))
            let finish: HandshakeFinishResponse = try await post(
                path: "/sc/v1/handshake/finish",
                value: HandshakeFinish(
                    kid: response.kid, sid: response.sid,
                    proof: try identity.signature(for: transcriptHash).base64URL))
            guard finish.active else { throw SecureError.authenticationFailed }
            let codec = try EnvelopeCodec(
                session: established,
                sequences: sequenceStore,
                allowedClockSkew: config.allowedClockSkew)
            activeSession = established
            messageClient = try SecureCommunicationClient(
                baseURL: config.baseURL, codec: codec, session: network)
            enrollmentToken = nil
        } catch {
            closeSession()
            if let secureError = error as? SecureError { throw secureError }
            throw SecureError.transport(code: "SC_HANDSHAKE_FAILED", status: 0, traceID: nil)
        }
    }

    public func request(
        _ method: String,
        logicalPath: String,
        protectedHeaders: [String: String] = [:],
        body: Data = Data(),
        requestID: String? = nil
    ) async throws -> SecureResponse {
        try await initialize()
        guard let messageClient else { throw SecureError.unknownSession }
        return try await messageClient.send(SecureRequest(
            method: method,
            path: logicalPath,
            contentType: "application/json",
            protectedHeaders: protectedHeaders,
            body: body,
            requestID: requestID ?? UUID().uuidString.lowercased()))
    }

    public func closeSession() {
        activeSession = nil
        messageClient = nil
    }

    private func post<Input: Encodable, Output: Decodable>(
        path: String, value: Input
    ) async throws -> Output {
        guard let endpoint = URL(string: path, relativeTo: config.baseURL)?.absoluteURL
        else { throw SecureError.invalidConfiguration("Invalid handshake endpoint") }
        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        request.httpBody = try JSONEncoder().encode(value)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (data, response) = try await network.data(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode)
        else { throw SecureError.transport(code: "SC_HANDSHAKE_FAILED", status: 0, traceID: nil) }
        return try JSONDecoder().decode(Output.self, from: data)
    }

    private static func deviceID(appID: String) throws -> String {
        let key = "com.coolxer.securecommunication.v1.device.\(appID)"
        if let value = UserDefaults.standard.string(forKey: key) { return value }
        let value = UUID().uuidString.lowercased()
        UserDefaults.standard.set(value, forKey: key)
        return value
    }

    private static func spki(_ x963: Data) -> Data {
        var result = Data([
            0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86, 0x48, 0xce,
            0x3d, 0x02, 0x01, 0x06, 0x08, 0x2a, 0x86, 0x48, 0xce, 0x3d,
            0x03, 0x01, 0x07, 0x03, 0x42, 0x00
        ])
        result.append(x963)
        return result
    }

    private static func x963(_ spki: Data) throws -> Data {
        guard spki.count == 91, spki.suffix(65).first == 0x04
        else { throw SecureError.authenticationFailed }
        return Data(spki.suffix(65))
    }
}

private struct HandshakeStart: Codable {
    let v: Int
    let suite: String
    let appId: String
    let deviceId: String
    let deviceType: String
    let clientEphemeralPublicKey: String
    let installationPublicKey: String
    let enrollmentToken: String?
    let timestamp: Int64
}

private struct HandshakeStartResponse: Codable {
    let v: Int
    let suite: String
    let kid: String
    let sid: String
    let serverIdentityPublicKey: String
    let serverEphemeralPublicKey: String
    let createdAt: Int64
    let expiresAt: Int64
    let signature: String
}

private struct HandshakeFinish: Codable {
    let kid: String
    let sid: String
    let proof: String
}

private struct HandshakeFinishResponse: Codable {
    let active: Bool
    let expiresAt: Int64
}

private extension Data {
    var base64URL: String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    init?(base64URL: String) {
        guard base64URL.range(
            of: #"^[A-Za-z0-9_-]+$"#, options: .regularExpression) != nil
        else { return nil }
        var value = base64URL.replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        value += String(repeating: "=", count: (4 - value.count % 4) % 4)
        self.init(base64Encoded: value)
    }
}
