import XCTest
import Security
@testable import OpenDictateCore

final class ChatGPTProtocolTests: XCTestCase {
    func testCallbackBindsStatePathAndClientAndRejectsDuplicatesAndDecline() throws {
        let callback = try ChatGPTProtocol.callback("/auth/callback?state=s&code=c&client_id=oaiapp_test", state: "s", client: nil)
        XCTAssertEqual(callback.0, "c"); XCTAssertEqual(callback.1, "oaiapp_test")
        for target in ["/callback?state=s&code=c&client_id=oaiapp_test", "/auth/callback?state=other&code=c&client_id=oaiapp_test",
                       "/auth/callback?state=s&state=s&code=c&client_id=oaiapp_test", "/auth/callback?state=s&code=c&client_id=oaiapp_other",
                       "/auth/callback?state=s&error=access_denied"] {
            XCTAssertThrowsError(try ChatGPTProtocol.callback(target, state: "s", client: "oaiapp_test"))
        }
        let initial = ChatGPTProtocol.authorization(host: "host", redirect: "http://127.0.0.1:123/auth/callback", state: "s", nonce: "n", challenge: "pkce")
        XCTAssertTrue(initial.absoluteString.contains("client_id=dynamic_agent_client"))
        XCTAssertTrue(initial.absoluteString.contains("agent_name_hint=OpenDictate"))
        XCTAssertFalse(ChatGPTProtocol.authorization(host: "host", redirect: "redirect", state: "s", nonce: "n", challenge: "pkce", client: "oaiapp_test").absoluteString.contains("agent_name_hint"))
    }
    func testRS256SignatureAndOIDCClaims() throws {
        let key = try XCTUnwrap(SecKeyCreateRandomKey([kSecAttrKeyType as String: kSecAttrKeyTypeRSA, kSecAttrKeySizeInBits as String: 2048] as CFDictionary, nil))
        let publicKey = try XCTUnwrap(SecKeyCopyPublicKey(key))
        let publicBytes = try XCTUnwrap(SecKeyCopyExternalRepresentation(publicKey, nil) as Data?)
        // Parse the PKCS#1 public key produced by Security into the public JWK integers.
        var offset = 0
        func tlv(_ tag: UInt8) throws -> Data {
            XCTAssertEqual(publicBytes[offset], tag); offset += 1
            var length = Int(publicBytes[offset]); offset += 1
            if length >= 128 { let count = length & 127; length = 0; for _ in 0..<count { length = length * 256 + Int(publicBytes[offset]); offset += 1 } }
            let value = publicBytes.subdata(in: offset..<offset+length); offset += length; return value
        }
        let sequence = try tlv(0x30); offset = publicBytes.count - sequence.count
        let modulus = try tlv(2), exponent = try tlv(2)
        let jwks: [String: Any] = ["keys": [["kid": "test", "kty": "RSA", "n": ChatGPTProtocol.base64URL(modulus), "e": ChatGPTProtocol.base64URL(exponent)]]]
        func signed(_ claims: [String: Any]) throws -> String {
            let header = ChatGPTProtocol.base64URL(Data(#"{"alg":"RS256","kid":"test"}"#.utf8))
            let input = header + "." + ChatGPTProtocol.base64URL(try JSONSerialization.data(withJSONObject: claims))
            let signature = try XCTUnwrap(SecKeyCreateSignature(key, .rsaSignatureMessagePKCS1v15SHA256, Data(input.utf8) as CFData, nil) as Data?)
            return input + "." + ChatGPTProtocol.base64URL(signature)
        }
        var claims: [String: Any] = ["iss": ChatGPTProtocol.issuer, "sub": "synthetic", "aud": "oaiapp_test", "nonce": "nonce", "exp": 2000]
        let jwt = try signed(claims)
        XCTAssertEqual(try ChatGPTProtocol.identity(jwt, jwks: jwks, client: "oaiapp_test", nonce: "nonce", now: 1000)["sub"] as? String, "synthetic")
        XCTAssertThrowsError(try ChatGPTProtocol.identity(jwt, jwks: jwks, client: "other", nonce: "nonce", now: 1000))
        XCTAssertThrowsError(try ChatGPTProtocol.identity(jwt, jwks: jwks, client: "oaiapp_test", nonce: "other", now: 1000))
        XCTAssertThrowsError(try ChatGPTProtocol.identity(jwt, jwks: jwks, client: "oaiapp_test", nonce: "nonce", now: 2000))
        XCTAssertThrowsError(try ChatGPTProtocol.identity(String(jwt.dropLast(8)) + "AAAAAAAA", jwks: jwks, client: "oaiapp_test", nonce: "nonce", now: 1000))
        // A single audience may be encoded as an array without an authorized-party claim.
        claims["aud"] = ["oaiapp_test"]
        XCTAssertEqual(try ChatGPTProtocol.identity(signed(claims), jwks: jwks, client: "oaiapp_test", nonce: "nonce", now: 1000)["sub"] as? String, "synthetic")
        claims["aud"] = ["oaiapp_other"]
        XCTAssertThrowsError(try ChatGPTProtocol.identity(signed(claims), jwks: jwks, client: "oaiapp_test", nonce: "nonce", now: 1000))
        claims["aud"] = ["oaiapp_test", "oaiapp_other"]
        XCTAssertThrowsError(try ChatGPTProtocol.identity(signed(claims), jwks: jwks, client: "oaiapp_test", nonce: "nonce", now: 1000))
        claims["azp"] = "oaiapp_test"
        XCTAssertEqual(try ChatGPTProtocol.identity(signed(claims), jwks: jwks, client: "oaiapp_test", nonce: "nonce", now: 1000)["sub"] as? String, "synthetic")
        claims["aud"] = "oaiapp_test"; claims["azp"] = "oaiapp_other"
        XCTAssertThrowsError(try ChatGPTProtocol.identity(signed(claims), jwks: jwks, client: "oaiapp_test", nonce: "nonce", now: 1000))
        claims.removeValue(forKey: "azp")
        claims["iss"] = "https://example.com"
        XCTAssertThrowsError(try ChatGPTProtocol.identity(signed(claims), jwks: jwks, client: "oaiapp_test", nonce: "nonce", now: 1000))
    }
    func testPlanTransportAndTerminalEventAreRequired() throws {
        let body = OpenAIRequest.transform(source: "Synthetic", instruction: "Edit")
        let plan = ChatGPTProtocol.planRequest(body)
        XCTAssertEqual(plan["stream"] as? Bool, true); XCTAssertEqual(plan["store"] as? Bool, false)
        XCTAssertEqual((plan["input"] as? [[String: String]])?.first?["content"], body["input"] as? String)
        XCTAssertNil(body["stream"])
        XCTAssertNil(try ChatGPTProtocol.completedEvent(#"{"type":"response.output_text.delta","delta":"partial"}"#))
        XCTAssertThrowsError(try ChatGPTProtocol.completedEvent(#"{"type":"response.failed"}"#))
        XCTAssertThrowsError(try ChatGPTProtocol.completedEvent(#"{"type":"response.incomplete"}"#))
        XCTAssertEqual(try ChatGPTProtocol.completedEvent(#"{"type":"response.completed","response":{"status":"completed"}}"#)?["status"] as? String, "completed")
    }
    private func done(_ index: Any, _ text: String) throws -> String {
        let value: [String: Any] = ["type": "response.output_item.done", "output_index": index,
            "item": ["type": "message", "content": [["type": "output_text", "text": text]]]]
        return String(decoding: try JSONSerialization.data(withJSONObject: value), as: UTF8.self)
    }
    private var terminal: String { #"{"type":"response.completed","response":{"status":"completed","output":[]}}"# }
    func testCompletedItemsAreOrderedAndOnlyReleasedAfterTerminalSuccess() throws {
        var stream = ChatGPTResponseAccumulator()
        try stream.consume(done(1, "B")); try stream.consume(done(0, "A"))
        XCTAssertThrowsError(try stream.result())
        try stream.consume(terminal); try stream.consume("[DONE]")
        XCTAssertEqual(OpenAIRequest.responseText(try stream.result()), "AB")
        var full = ChatGPTResponseAccumulator(); try full.consume(done(0, "stale"))
        try full.consume(#"{"type":"response.completed","response":{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"authoritative"}]}]}}"#)
        XCTAssertEqual(OpenAIRequest.responseText(try full.result()), "authoritative")
    }
    func testDeltasAndFailedOrInterruptedStreamsCannotDeliverCompletedItems() throws {
        var partial = ChatGPTResponseAccumulator()
        try partial.consume(#"{"type":"response.output_text.delta","delta":"partial"}"#)
        XCTAssertThrowsError(try partial.result())
        try partial.consume(terminal)
        XCTAssertEqual(OpenAIRequest.responseText(try partial.result()), "")
        for type in ["response.failed", "response.incomplete", "error"] {
            var stream = ChatGPTResponseAccumulator(); try stream.consume(done(0, "finished item")); try stream.consume(terminal)
            XCTAssertThrowsError(try stream.consume("{\"type\":\"\(type)\"}"))
            XCTAssertThrowsError(try stream.result())
        }
    }
    func testMalformedOrUnboundedOutputIndexesFailClosed() throws {
        for index: Any in [-1, 1024, 0.5, true, "0"] as [Any] {
            var stream = ChatGPTResponseAccumulator()
            XCTAssertThrowsError(try stream.consume(done(index, "text")))
            XCTAssertThrowsError(try stream.result())
        }
    }

}
