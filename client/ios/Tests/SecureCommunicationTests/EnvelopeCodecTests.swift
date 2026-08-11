import CryptoKit
import Foundation
import XCTest
@testable import SecureCommunication

final class EnvelopeCodecTests: XCTestCase {
    func testCrossLanguageRequestVector() async throws {
        let key = Data((0..<32).map(UInt8.init))
        let session = try SecureSession(
            keyID: "test-key-2026-01",
            sessionID: "test-session-0001",
            requestKey: SymmetricKey(data: key),
            responseKey: SymmetricKey(data: key),
            requestNoncePrefix: Data([0xa0, 0xa1, 0xa2, 0xa3]),
            responseNoncePrefix: Data([0xb0, 0xb1, 0xb2, 0xb3]),
            expiresAt: Date(timeIntervalSince1970: 1_785_283_260))
        let suiteName = UUID().uuidString
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let codec = try EnvelopeCodec(
            session: session,
            sequences: PersistentSequenceStore(defaults: defaults),
            clock: { Date(timeIntervalSince1970: 1_785_283_200) })
        let encoded = try await codec.encode(SecureRequest(
            method: "post",
            path: "/api/messages?x=1&lang=zh",
            contentType: "application/json; charset=utf-8",
            body: #"{"message":"你好🌍"}"#.data(using: .utf8)!,
            requestID: "request-0001"))
        let object = try XCTUnwrap(
            JSONSerialization.jsonObject(with: encoded.body) as? [String: Any])

        XCTAssertEqual(object["seq"] as? Int, 1)
        XCTAssertEqual(object["rid"] as? String, "request-0001")
        XCTAssertEqual(object["m"] as? String, "POST")
        XCTAssertEqual(object["p"] as? String, "/sc/v1/message")
        XCTAssertEqual(object["cty"] as? String, "application/sc-protected+json")
        XCTAssertEqual(object["nonce"] as? String, "oKGiowAAAAAAAAAB")
        XCTAssertFalse((object["ct"] as? String ?? "").isEmpty)
        XCTAssertNil(encoded.body.range(of: Data("/api/messages".utf8)))
    }
}
