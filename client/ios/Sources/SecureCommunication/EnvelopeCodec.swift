import CryptoKit
import Foundation

struct SecureEnvelope: Codable, Sendable {
    let v: Int
    let suite: String
    let kid: String
    let sid: String
    let ts: Int64
    let seq: UInt64
    let rid: String
    let m: String
    let p: String
    let cty: String
    let st: Int
    let nonce: String
    let ct: String
}

actor EnvelopeCodec {
    static let mediaType = "application/sc-envelope+json"
    private static let protectedMediaType = "application/sc-protected+json"
    private static let messageEndpoint = "/sc/v1/message"

    private let session: SecureSession
    private let sequences: PersistentSequenceStore
    private let clock: @Sendable () -> Date
    private let allowedClockSkew: TimeInterval
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init(
        session: SecureSession,
        sequences: PersistentSequenceStore,
        allowedClockSkew: TimeInterval = 120,
        clock: @escaping @Sendable () -> Date = { Date() }
    ) throws {
        guard session.suite == .international, allowedClockSkew >= 0 else {
            throw SecureError.unsupportedSuite
        }
        self.session = session
        self.sequences = sequences
        self.clock = clock
        self.allowedClockSkew = allowedClockSkew
    }

    func encode(_ request: SecureRequest) async throws
        -> (body: Data, sequence: UInt64, requestID: String) {
        let now = clock()
        guard now < session.expiresAt else { throw SecureError.unknownSession }
        let sequence = try await sequences.next(sessionID: session.sessionID)
        _ = try Self.normalizeMethod(request.method)
        _ = try Self.normalizePath(request.logicalPath)
        _ = try Self.normalizeContentType(request.contentType)
        guard let requestID = request.requestID, requestID.range(
            of: #"^[!-~]{1,128}$"#, options: .regularExpression) != nil
        else { throw SecureError.invalidEnvelope }
        let nonceData = Self.nonce(
            prefix: session.requestNoncePrefix, sequence: sequence)
        let timestamp = Int64(now.timeIntervalSince1970 * 1_000)
        let unsigned = SecureEnvelope(
            v: 1,
            suite: session.suite.rawValue,
            kid: session.keyID,
            sid: session.sessionID,
            ts: timestamp,
            seq: sequence,
            rid: requestID,
            m: "POST",
            p: Self.messageEndpoint,
            cty: Self.protectedMediaType,
            st: 0,
            nonce: nonceData.base64URL,
            ct: "")
        let protectedBody = try Self.protectedPayload(request)
        let sealed = try AES.GCM.seal(
            protectedBody,
            using: session.requestKey,
            nonce: AES.GCM.Nonce(data: nonceData),
            authenticating: Self.aad(direction: "request", envelope: unsigned))
        let envelope = SecureEnvelope(
            v: unsigned.v, suite: unsigned.suite, kid: unsigned.kid,
            sid: unsigned.sid, ts: unsigned.ts, seq: unsigned.seq,
            rid: unsigned.rid,
            m: unsigned.m, p: unsigned.p, cty: unsigned.cty,
            st: unsigned.st,
            nonce: unsigned.nonce,
            ct: (sealed.ciphertext + sealed.tag).base64URL)
        return (try encoder.encode(envelope), sequence, requestID)
    }

    func decode(
        _ data: Data,
        expectedSequence: UInt64,
        expectedRequestID: String
    ) throws -> (body: Data, contentType: String, status: Int) {
        try Self.requireExactFields(data)
        let envelope: SecureEnvelope
        do {
            envelope = try decoder.decode(SecureEnvelope.self, from: data)
        } catch {
            throw SecureError.invalidEnvelope
        }
        guard envelope.v == 1 else { throw SecureError.unsupportedVersion }
        guard envelope.suite == session.suite.rawValue,
              envelope.kid == session.keyID,
              envelope.sid == session.sessionID
        else { throw SecureError.unknownSession }
        guard envelope.seq == expectedSequence,
              envelope.rid == expectedRequestID,
              envelope.m == "POST",
              envelope.p == Self.messageEndpoint,
              envelope.cty == Self.protectedMediaType,
              (100...599).contains(envelope.st)
        else { throw SecureError.routeMismatch }
        let envelopeDate = Date(
            timeIntervalSince1970: TimeInterval(envelope.ts) / 1_000)
        guard abs(clock().timeIntervalSince(envelopeDate)) <= allowedClockSkew
        else { throw SecureError.requestExpired }
        guard let receivedNonce = Data(base64URL: envelope.nonce),
              receivedNonce == Self.nonce(
                prefix: session.responseNoncePrefix, sequence: envelope.seq),
              let combined = Data(base64URL: envelope.ct),
              combined.count >= 16
        else { throw SecureError.invalidEnvelope }
        let ciphertext = combined.dropLast(16)
        let tag = combined.suffix(16)
        do {
            let box = try AES.GCM.SealedBox(
                nonce: AES.GCM.Nonce(data: receivedNonce),
                ciphertext: ciphertext,
                tag: tag)
            return (
                try AES.GCM.open(
                    box,
                    using: session.responseKey,
                    authenticating: Self.aad(
                        direction: "response", envelope: envelope)),
                envelope.cty,
                envelope.st)
        } catch {
            throw SecureError.authenticationFailed
        }
    }

    private static func aad(direction: String, envelope: SecureEnvelope) -> Data {
        var lines = [
            "SC1", direction, envelope.suite, envelope.kid, envelope.sid,
            String(envelope.ts), String(envelope.seq), envelope.rid, envelope.m, envelope.p,
            envelope.cty
        ]
        if direction == "response" {
            lines.append(String(envelope.st))
        }
        return lines.joined(separator: "\n").data(using: .utf8)!
    }

    private static func protectedPayload(_ request: SecureRequest) throws -> Data {
        var headers: [String: String] = [:]
        for (rawName, value) in request.protectedHeaders {
            let name = rawName.lowercased()
            guard name.range(
                of: #"^[a-z0-9-]{1,64}$"#, options: .regularExpression) != nil,
                !value.contains("\r"), !value.contains("\n")
            else { throw SecureError.invalidEnvelope }
            headers[name] = value
        }
        return try JSONSerialization.data(withJSONObject: [
            "method": try normalizeMethod(request.method),
            "path": try normalizePath(request.logicalPath),
            "contentType": try normalizeContentType(request.contentType),
            "headers": headers,
            "body": request.body.base64URL
        ])
    }

    private static func nonce(prefix: Data, sequence: UInt64) -> Data {
        var bigEndian = sequence.bigEndian
        var result = prefix
        withUnsafeBytes(of: &bigEndian) { result.append(contentsOf: $0) }
        return result
    }

    static func normalizeMethod(_ value: String) throws -> String {
        let method = value.uppercased()
        guard method.range(of: #"^[A-Z]{3,16}$"#, options: .regularExpression) != nil
        else { throw SecureError.routeMismatch }
        return method
    }

    static func normalizeContentType(_ value: String) throws -> String {
        let contentType = value.split(separator: ";", maxSplits: 1)
            .first.map(String.init)?.trimmingCharacters(in: .whitespaces)
            .lowercased() ?? "application/octet-stream"
        guard contentType.range(
            of: #"^[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+$"#,
            options: .regularExpression) != nil
        else { throw SecureError.routeMismatch }
        return contentType
    }

    static func normalizePath(_ value: String) throws -> String {
        guard value.hasPrefix("/"), !value.contains("#"),
              !value.contains("://"), !value.contains(" "),
              !value.contains("\r"), !value.contains("\n"),
              value.filter({ $0 == "?" }).count <= 1
        else { throw SecureError.routeMismatch }
        let target = value.split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false)
        let path = try uppercasePercentHex(String(target[0]))
        guard target.count == 2, !target[1].isEmpty else { return path }
        let pairs = try target[1].split(separator: "&")
            .map { try uppercasePercentHex(String($0)) }
            .sorted {
                let left = queryParts($0)
                let right = queryParts($1)
                return left.0 == right.0 ? left.1 < right.1 : left.0 < right.0
            }
        return pairs.isEmpty ? path : path + "?" + pairs.joined(separator: "&")
    }

    private static func uppercasePercentHex(_ value: String) throws -> String {
        let characters = Array(value)
        var result = ""
        var index = 0
        while index < characters.count {
            if characters[index] == "%" {
                guard index + 2 < characters.count,
                      characters[index + 1].isHexDigit,
                      characters[index + 2].isHexDigit
                else { throw SecureError.routeMismatch }
                result += "%" + String(characters[(index + 1)...(index + 2)]).uppercased()
                index += 3
            } else {
                result.append(characters[index])
                index += 1
            }
        }
        return result
    }

    private static func queryParts(_ value: String) -> (String, String) {
        let pair = value.split(
            separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
        return (String(pair[0]), pair.count == 2 ? String(pair[1]) : "")
    }

    private static func requireExactFields(_ data: Data) throws {
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              Set(object.keys) == Set([
                "v", "suite", "kid", "sid", "ts", "seq",
                "rid", "m", "p", "cty", "st", "nonce", "ct"
              ])
        else { throw SecureError.invalidEnvelope }
    }
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
        var value = base64URL
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        value += String(repeating: "=", count: (4 - value.count % 4) % 4)
        self.init(base64Encoded: value)
    }
}
