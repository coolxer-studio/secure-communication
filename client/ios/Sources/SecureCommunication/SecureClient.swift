import CryptoKit
import Foundation

public struct SecureClientConfig: Sendable {
    public let baseURL: URL
    public let appID: String
    public let deviceType: String
    public let serverTrustAnchors: [String: Data]
    public let identityStore: any IdentityStore
    public let requestTimeout: TimeInterval
    public let allowedClockSkew: TimeInterval
    public let allowInsecureLoopbackForTesting: Bool

    public init(
        baseURL: URL,
        appID: String,
        serverTrustAnchors: [String: Data],
        deviceType: String = "IOS",
        identityStore: any IdentityStore = KeychainIdentityStore(),
        requestTimeout: TimeInterval = 15,
        allowedClockSkew: TimeInterval = 120,
        allowInsecureLoopbackForTesting: Bool = false
    ) {
        self.baseURL = baseURL
        self.appID = appID
        self.deviceType = deviceType.uppercased()
        self.serverTrustAnchors = serverTrustAnchors
        self.identityStore = identityStore
        self.requestTimeout = requestTimeout
        self.allowedClockSkew = allowedClockSkew
        self.allowInsecureLoopbackForTesting = allowInsecureLoopbackForTesting
    }
}

/// Unified protocol v1 client. Business requests are never retried.
public actor SecureClient {
    private let config: SecureClientConfig
    private let network: URLSession
    private let sequenceStore: PersistentSequenceStore
    private var enrollmentToken: String?
    private var activeSession: SecureSession?
    private var messageClient: SecureCommunicationClient?
    private var initialization: SharedInitialization?
    private var generation = 0

    private struct InitializationResult: Sendable {
        let session: SecureSession
        let client: SecureCommunicationClient
    }

    private struct SharedInitialization {
        let id: UUID
        let generation: Int
        let token: String?
        let task: Task<InitializationResult, Error>
    }

    public init(config: SecureClientConfig, session: URLSession? = nil) throws {
        let scheme = config.baseURL.scheme?.lowercased()
        let host = config.baseURL.host?.lowercased() ?? ""
        let loopback = host == "localhost" || host == "::1" || host.hasPrefix("127.")
        let secureURL = scheme == "https" || (config.allowInsecureLoopbackForTesting
            && scheme == "http" && loopback)
        let deviceTypes = Set(["H5", "HOST", "SERVER", "ANDROID", "IOS", "EMULATOR"])
        guard secureURL, config.baseURL.user == nil, config.baseURL.query == nil,
              config.baseURL.fragment == nil,
              config.appID.range(of: #"^[A-Za-z0-9._:@/-]{1,128}$"#,
                                 options: .regularExpression) != nil,
              !config.serverTrustAnchors.isEmpty,
              deviceTypes.contains(config.deviceType),
              config.requestTimeout > 0, config.allowedClockSkew >= 0
        else { throw SecureError.invalidConfiguration("Invalid protocol v1 configuration") }
        self.config = config
        self.sequenceStore = PersistentSequenceStore(
            namespace: "com.coolxer.securecommunication.v2.\(config.appID)")
        if let session {
            self.network = session
        } else {
            let settings = URLSessionConfiguration.ephemeral
            settings.timeoutIntervalForRequest = config.requestTimeout
            settings.timeoutIntervalForResource = config.requestTimeout
            settings.tlsMinimumSupportedProtocolVersion = .TLSv12
            settings.requestCachePolicy = .reloadIgnoringLocalAndRemoteCacheData
            self.network = URLSession(
                configuration: settings,
                delegate: NoRedirectSessionDelegate(),
                delegateQueue: nil)
        }
    }

    public func enroll(_ token: String) throws {
        guard config.deviceType != "H5" else {
            throw SecureError(code: "SC_ENROLLMENT_NOT_SUPPORTED")
        }
        guard !token.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        else { throw SecureError(code: "SC_ENROLLMENT_REQUIRED") }
        enrollmentToken = token
    }

    public func initialize() async throws {
        if let activeSession, activeSession.expiresAt > Date(), messageClient != nil { return }
        let shared: SharedInitialization
        if let initialization {
            shared = initialization
        } else {
            let id = UUID()
            let expectedGeneration = generation
            let token = enrollmentToken
            let task = Task { try await self.performInitialization(token: token) }
            shared = SharedInitialization(
                id: id, generation: expectedGeneration, token: token, task: task)
            initialization = shared
        }
        do {
            let result = try await waitForShared(shared.task)
            guard initialization?.id == shared.id else {
                if let activeSession, activeSession.expiresAt > Date(), messageClient != nil { return }
                throw SecureError.requestCancelled
            }
            guard generation == shared.generation else {
                initialization = nil
                throw SecureError.requestCancelled
            }
            activeSession = result.session
            messageClient = result.client
            if enrollmentToken == shared.token { enrollmentToken = nil }
            initialization = nil
        } catch {
            if Task.isCancelled { throw SecureError.requestCancelled }
            if initialization?.id == shared.id { initialization = nil }
            activeSession = nil
            messageClient = nil
            if let secureError = error as? SecureError { throw secureError }
            throw Self.executionError(error, fallback: "SC_HANDSHAKE_FAILED")
        }
    }

    private func performInitialization(token: String?) async throws -> InitializationResult {
        let identity = try config.identityStore.loadOrCreate(appID: config.appID)
        let installation = try identity.publicKeySPKI()
        let ephemeral = P256.KeyAgreement.PrivateKey()
        let request = HandshakeStart(
            v: 1,
            suite: SecureSuite.international.rawValue,
            appId: config.appID,
            deviceId: identity.deviceID,
            deviceType: config.deviceType,
            clientEphemeralPublicKey: Self.spki(ephemeral.publicKey.x963Representation).base64URL,
            installationPublicKey: installation.base64URL,
            enrollmentToken: token,
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
            config.appID, identity.deviceID, config.deviceType,
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
                proof: try identity.sign(transcriptHash).base64URL))
        guard finish.active else { throw SecureError.authenticationFailed }
        let codec = try EnvelopeCodec(
            session: established,
            sequences: sequenceStore,
            allowedClockSkew: config.allowedClockSkew)
        return InitializationResult(
            session: established,
            client: try SecureCommunicationClient(
                baseURL: config.baseURL, codec: codec, session: network))
    }

    public func request(_ request: SecureRequest) async throws -> SecureResponse {
        try await initialize()
        guard let messageClient else { throw SecureError.unknownSession }
        do {
            let resolved = try SecureRequest(
                method: request.method,
                logicalPath: request.logicalPath,
                contentType: request.contentType,
                protectedHeaders: request.protectedHeaders,
                body: request.body,
                requestID: request.requestID ?? UUID().uuidString.lowercased())
            return try await messageClient.send(resolved)
        } catch let error as SecureError {
            if error.code == "SC_UNKNOWN_SESSION" { closeSession() }
            throw error
        } catch {
            throw Self.executionError(error, fallback: "SC_NETWORK_FAILED")
        }
    }

    public func closeSession() {
        generation += 1
        activeSession = nil
        messageClient = nil
        initialization = nil
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
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await network.data(for: request)
        } catch {
            throw Self.executionError(error, fallback: "SC_NETWORK_FAILED")
        }
        guard let http = response as? HTTPURLResponse else {
            throw SecureError(code: "SC_NETWORK_FAILED")
        }
        guard (200..<300).contains(http.statusCode) else {
            let remote = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
            throw SecureError(
                code: remote?["code"] as? String ?? "SC_HANDSHAKE_FAILED",
                httpStatus: http.statusCode,
                traceID: remote?["traceId"] as? String)
        }
        do { return try JSONDecoder().decode(Output.self, from: data) }
        catch { throw SecureError(code: "SC_HANDSHAKE_FAILED", cause: error) }
    }

    private static func executionError(_ error: Error, fallback: String) -> SecureError {
        if error is CancellationError { return .requestCancelled }
        if let urlError = error as? URLError {
            if urlError.code == .cancelled { return .requestCancelled }
            if urlError.code == .timedOut {
                return SecureError(code: "SC_REQUEST_TIMEOUT", cause: urlError)
            }
        }
        return SecureError(code: fallback, cause: error)
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

private final class NoRedirectSessionDelegate: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        completionHandler(nil)
    }
}

private final class SharedTaskWaiter<Value>: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Value, Error>?
    private var result: Result<Value, Error>?

    func install(_ value: CheckedContinuation<Value, Error>) {
        lock.lock()
        if let result {
            lock.unlock()
            value.resume(with: result)
        } else {
            continuation = value
            lock.unlock()
        }
    }

    func finish(_ value: Result<Value, Error>) {
        lock.lock()
        guard result == nil else { lock.unlock(); return }
        result = value
        let current = continuation
        continuation = nil
        lock.unlock()
        current?.resume(with: value)
    }
}

private func waitForShared<Value: Sendable>(
    _ task: Task<Value, Error>
) async throws -> Value {
    let waiter = SharedTaskWaiter<Value>()
    return try await withTaskCancellationHandler(operation: {
        try await withCheckedThrowingContinuation { continuation in
            waiter.install(continuation)
            Task {
                do { waiter.finish(.success(try await task.value)) }
                catch { waiter.finish(.failure(error)) }
            }
        }
    }, onCancel: {
        waiter.finish(.failure(SecureError.requestCancelled))
    })
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
