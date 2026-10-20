import XCTest
import OpenDictateCore
@testable import OpenDictate

private final class ChatGPTHTTP: URLProtocol, @unchecked Sendable {
    static var handler: ((URLRequest) throws -> (Int, String))?
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        do {
            let (code, body) = try Self.handler!(request)
            let response = HTTPURLResponse(url: request.url!, statusCode: code, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "text/event-stream"])!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: Data(body.utf8)); client?.urlProtocolDidFinishLoading(self)
        } catch { client?.urlProtocol(self, didFailWithError: error) }
    }
    override func stopLoading() {}
}
final class ChatGPTSessionTests: XCTestCase {
    private func broker() -> ChatGPTSession {
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [ChatGPTHTTP.self]
        let record = ChatGPTRecord(client: "oaiapp_test", access: "synthetic-expired", refresh: "synthetic-refresh", scope: "chatgpt.tokens.use.direct", expires: 1)
        return ChatGPTSession(session: URLSession(configuration: config), initial: ChatGPTJournal(records: [record.client: record], active: record.client, last: record.client))
    }
    func testRotatingRefreshIsSerializedAndOAuthUsesCompletedStream() async throws {
        let lock = NSLock(); var refreshes = 0; var requests = 0
        ChatGPTHTTP.handler = { request in
            lock.lock(); defer { lock.unlock() }
            if request.url!.path.hasSuffix("/oauth/token") {
                refreshes += 1
                return (200, #"{"access_token":"synthetic-new","refresh_token":"synthetic-rotated","scope":"chatgpt.tokens.use.direct","token_type":"Bearer","expires_in":3600}"#)
            }
            requests += 1
            XCTAssertEqual(request.url!.path, "/v1/responses")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer synthetic-new")
            return (200, "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n")
        }
        let client = broker()
        async let first = client.response(account: "oaiapp_test", body: OpenAIRequest.transform(source: "Synthetic", instruction: "Edit"))
        async let second = client.response(account: "oaiapp_test", body: OpenAIRequest.transform(source: "Synthetic", instruction: "Edit"))
        let values = try await [first, second]
        XCTAssertEqual(values.count, 2); XCTAssertEqual(refreshes, 1); XCTAssertEqual(requests, 2)
        let record = try await client.active()
        XCTAssertEqual(record?.refresh, "synthetic-rotated")
    }
    func testRevokedRefreshStopsWithoutApiFallbackOrRepeatedRefresh() async throws {
        var count = 0
        ChatGPTHTTP.handler = { _ in count += 1; return (400, #"{"error":"invalid_grant"}"#) }
        let client = broker()
        for _ in 0..<2 {
            do { _ = try await client.response(account: "oaiapp_test", body: OpenAIRequest.transform(source: "Synthetic", instruction: "Edit")); XCTFail() }
            catch { XCTAssertTrue(error is ChatGPTError) }
        }
        XCTAssertEqual(count, 1)
        let record = try await client.registration()
        XCTAssertEqual(record?.client, "oaiapp_test"); XCTAssertTrue(record?.refresh.isEmpty == true)
    }
    func testCompletedItemFeedsPostProcessingWhenTerminalOutputIsEmpty() async throws {
        let item: [String: Any] = ["type": "message", "role": "assistant", "status": "completed",
            "content": [["type": "output_text", "text": "{\"transformed_text\":\"123\"}"]]]
        let done = String(decoding: try JSONSerialization.data(withJSONObject: ["type": "response.output_item.done", "output_index": 0, "item": item]), as: UTF8.self)
        let stream = "data: \(done)\n\ndata: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n"
        ChatGPTHTTP.handler = { request in
            if request.url!.path.hasSuffix("/oauth/token") {
                return (200, #"{"access_token":"synthetic-new","refresh_token":"synthetic-rotated","scope":"chatgpt.tokens.use.direct","token_type":"Bearer","expires_in":3600}"#)
            }
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer synthetic-new")
            return (200, stream)
        }
        let client = broker()
        let settings = PostProcessingSettings(enabled: true, formatValues: true, formats: .systemDefaults(locale: Locale(identifier: "en_US")))
        let result = try await PostProcessing.apply(source: "сто двадцать три", settings: settings) {
            let response = try await client.response(account: "oaiapp_test", body: OpenAIRequest.postProcessing(source: "сто двадцать три", settings: settings))
            let data = Data(OpenAIRequest.responseText(response).utf8)
            let object = try JSONSerialization.jsonObject(with: data) as? [String: Any]
            return object?["transformed_text"] as? String ?? ""
        }
        XCTAssertFalse(result.failed); XCTAssertEqual(result.text, "123")
    }

}
