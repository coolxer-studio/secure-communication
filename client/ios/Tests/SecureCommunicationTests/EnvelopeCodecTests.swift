import CryptoKit
import Foundation
import XCTest
@testable import SecureCommunication

final class EnvelopeCodecTests: XCTestCase {
    func testConfigAcceptsHTTPAndHTTPSAndRejectsOtherURLForms() throws {
        let anchors = ["kid": Data([1])]
        let http = SecureClientConfig(
            baseURL: URL(string: "http://192.0.2.10:8080")!, appID: "agent",
            serverTrustAnchors: anchors)
        XCTAssertNoThrow(try SecureClient(config: http))
        let https = SecureClientConfig(
            baseURL: URL(string: "https://example.test")!, appID: "agent",
            serverTrustAnchors: anchors)
        XCTAssertNoThrow(try SecureClient(config: https))
        for value in ["ftp://example.test", "https://user:secret@example.test",
                      "https://example.test?x=1", "https://example.test#fragment"] {
            let config = SecureClientConfig(
                baseURL: URL(string: value)!, appID: "agent", serverTrustAnchors: anchors)
            XCTAssertThrowsError(try SecureClient(config: config))
        }
    }

    func testUnifiedRequestAndErrorModels() throws {
        let request = try SecureRequest(logicalPath: "/health")
        XCTAssertEqual(request.method, "GET")
        XCTAssertEqual(request.contentType, "application/octet-stream")
        XCTAssertTrue(request.body.isEmpty)
        XCTAssertNil(request.requestID)

        let cause = URLError(.timedOut)
        let error = SecureError(
            code: "SC_TEST", httpStatus: 409, traceID: "trace", cause: cause)
        XCTAssertEqual(error.code, "SC_TEST")
        XCTAssertEqual(error.httpStatus, 409)
        XCTAssertEqual(error.traceID, "trace")
        XCTAssertNotNil(error.cause)
    }

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
        let encoded = try await codec.encode(try SecureRequest(
            method: "post",
            logicalPath: "/api/messages?x=1&lang=zh",
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
