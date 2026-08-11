import Foundation

public final class SecureCommunicationClient: Sendable {
    private let endpoint: URL
    private let session: URLSession
    private let codec: EnvelopeCodec

    public init(
        baseURL: URL,
        codec: EnvelopeCodec,
        session: URLSession? = nil
    ) throws {
        guard baseURL.scheme?.lowercased() == "https",
              let endpoint = URL(string: "/sc/v1/message", relativeTo: baseURL)?.absoluteURL
        else {
            throw SecureError.invalidConfiguration("baseURL must use HTTPS")
        }
        self.endpoint = endpoint
        self.codec = codec
        if let session {
            self.session = session
        } else {
            let configuration = URLSessionConfiguration.ephemeral
            configuration.tlsMinimumSupportedProtocolVersion = .TLSv12
            configuration.requestCachePolicy = .reloadIgnoringLocalAndRemoteCacheData
            self.session = URLSession(configuration: configuration)
        }
    }

    public func send(_ request: SecureRequest) async throws -> SecureResponse {
        let encoded = try await codec.encode(request)
        var transport = URLRequest(url: endpoint)
        transport.httpMethod = "POST"
        transport.httpBody = encoded.body
        transport.cachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        transport.setValue(EnvelopeCodec.mediaType, forHTTPHeaderField: "Content-Type")
        transport.setValue(EnvelopeCodec.mediaType, forHTTPHeaderField: "Accept")

        let (data, response) = try await session.data(for: transport)
        guard let http = response as? HTTPURLResponse else {
            throw SecureError.transport(
                code: "SC_NETWORK_FAILED", status: 0, traceID: nil)
        }
        let mediaType = http.value(forHTTPHeaderField: "Content-Type")?
            .split(separator: ";", maxSplits: 1).first.map(String.init).lowercased()
        guard mediaType == EnvelopeCodec.mediaType else {
            let error = (try? JSONSerialization.jsonObject(with: data))
                as? [String: Any]
            throw SecureError.transport(
                code: error?["code"] as? String ?? "SC_TRANSPORT_FAILED",
                status: http.statusCode,
                traceID: error?["traceId"] as? String)
        }
        let decoded = try await codec.decode(
            data,
            expectedSequence: encoded.sequence,
            expectedRequestID: encoded.requestID)
        guard let protectedResponse = try? JSONSerialization.jsonObject(
            with: decoded.body) as? [String: Any],
              Set(protectedResponse.keys) == Set(["contentType", "body"]),
              let rawContentType = protectedResponse["contentType"] as? String,
              let encodedBody = protectedResponse["body"] as? String,
              let logicalBody = Self.decodeBase64URL(encodedBody)
        else { throw SecureError.invalidEnvelope }
        let logicalContentType = try EnvelopeCodec.normalizeContentType(rawContentType)
        return SecureResponse(
            status: decoded.status,
            contentType: logicalContentType,
            body: logicalBody)
    }

    private static func decodeBase64URL(_ encoded: String) -> Data? {
        guard encoded.range(
            of: #"^[A-Za-z0-9_-]*$"#, options: .regularExpression) != nil
        else { return nil }
        var value = encoded.replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        value += String(repeating: "=", count: (4 - value.count % 4) % 4)
        return Data(base64Encoded: value)
    }
}

/// Supply a URLSession configured with this policy from the host when managed
/// pinning is required. Normal certificate and hostname validation must run
/// before custom SPKI pin checks; authentication failures must use cancel.
public protocol ServerTrustPolicy: Sendable {
    func evaluate(_ challenge: URLAuthenticationChallenge) -> URLSession.AuthChallengeDisposition
}
