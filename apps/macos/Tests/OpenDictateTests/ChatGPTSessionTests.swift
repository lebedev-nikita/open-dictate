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
    private let renewal = #"{"access_token":"synthetic-new","refresh_token":"synthetic-rotated","scope":"chatgpt.tokens.use.direct","token_type":"Bearer","expires_in":3600}"#
    private let completed = "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n"
    private func storedBroker(_ store: TestChatGPTPersistence) -> ChatGPTSession {
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [ChatGPTHTTP.self]
        return ChatGPTSession(session: URLSession(configuration: config), persistence: store.persistence)
    }

    @MainActor func testStartupReadFailureRecoversTheCapturedAccountWithoutReauthorization() async throws {
        let store = TestChatGPTPersistence()
        let suite = "chatgpt-recovery-\(UUID().uuidString)", defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        store.denyReads(true)
        let connection = ChatGPTConnection(defaults: defaults, localOnly: false, persistence: store.persistence)
        let captured = try XCTUnwrap(connection.capturedAccount())
        XCTAssertTrue(connection.failed)
        store.denyReads(false)
        let renewed = renewal, terminal = completed
        ChatGPTHTTP.handler = { request in
            if request.url!.path.hasSuffix("/oauth/token") { return (200, renewed) }
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer synthetic-new")
            return (200, terminal)
        }
        _ = try await storedBroker(store).response(account: captured, body: OpenAIRequest.transform(source: "Synthetic", instruction: "Edit"))
        XCTAssertEqual(connection.capturedAccount(), "oaiapp_test")
        XCTAssertFalse(connection.failed)
    }

    func testRotatedTokenSurvivesFailedPersistenceAndBrokerRestart() async throws {
        let store = TestChatGPTPersistence(), client = storedBroker(store)
        let lock = NSLock(); var refreshes = 0
        let renewed = renewal, terminal = completed
        ChatGPTHTTP.handler = { request in
            if request.url!.path.hasSuffix("/oauth/token") {
                let count = lock.withLock { refreshes += 1; return refreshes }
                return count == 1 ? (200, renewed) : (400, #"{"error":"invalid_grant"}"#)
            }
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer synthetic-new")
            return (200, terminal)
        }
        store.denyWrites(true)
        do {
            _ = try await client.response(account: "oaiapp_test", body: OpenAIRequest.transform(source: "Synthetic", instruction: "Edit"))
            XCTFail("Inference must wait for durable credentials")
        } catch { XCTAssertTrue(error is ChatGPTError) }
        store.denyWrites(false)
        _ = try await client.response(account: "oaiapp_test", body: OpenAIRequest.transform(source: "Synthetic", instruction: "Edit"))
        _ = try await storedBroker(store).response(account: "oaiapp_test", body: OpenAIRequest.transform(source: "Synthetic", instruction: "Edit"))
        XCTAssertEqual(refreshes, 1)
        XCTAssertEqual(store.snapshot().records["oaiapp_test"]?.refresh, "synthetic-rotated")
    }

    func testLockedKeychainCannotBeBypassedByTheBrokerCache() async throws {
        let store = TestChatGPTPersistence(), client = storedBroker(store)
        let renewed = renewal, terminal = completed
        let lock = NSLock(); var requests = 0
        ChatGPTHTTP.handler = { request in
            if request.url!.path.hasSuffix("/oauth/token") { return (200, renewed) }
            lock.withLock { requests += 1 }
            return (200, terminal)
        }
        _ = try await client.response(account: "oaiapp_test", body: OpenAIRequest.transform(source: "Synthetic", instruction: "Edit"))
        store.denyReads(true)
        do {
            _ = try await client.response(account: "oaiapp_test", body: OpenAIRequest.transform(source: "Synthetic", instruction: "Edit"))
            XCTFail("A locked Keychain must stop requests")
        } catch { XCTAssertTrue(error is ChatGPTError) }
        XCTAssertEqual(requests, 1)
        store.denyReads(false)
        _ = try await client.response(account: "oaiapp_test", body: OpenAIRequest.transform(source: "Synthetic", instruction: "Edit"))
        XCTAssertEqual(requests, 2)
    }

    func testKeychainLockAfterRemoteRotationRetainsTheReplacementForRetry() async throws {
        let store = TestChatGPTPersistence(), client = storedBroker(store)
        let renewed = renewal, terminal = completed
        let lock = NSLock(); var refreshes = 0
        ChatGPTHTTP.handler = { request in
            if request.url!.path.hasSuffix("/oauth/token") {
                let count = lock.withLock { refreshes += 1; return refreshes }
                if count == 1 { store.denyReads(true); return (200, renewed) }
                return (400, #"{"error":"invalid_grant"}"#)
            }
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer synthetic-new")
            return (200, terminal)
        }
        do {
            _ = try await client.response(account: "oaiapp_test", body: OpenAIRequest.transform(source: "Synthetic", instruction: "Edit"))
            XCTFail("The locked store must stop inference")
        } catch { XCTAssertTrue(error is ChatGPTError) }
        store.denyReads(false)
        _ = try await client.response(account: "oaiapp_test", body: OpenAIRequest.transform(source: "Synthetic", instruction: "Edit"))
        XCTAssertEqual(refreshes, 1)
        XCTAssertEqual(store.snapshot().records["oaiapp_test"]?.refresh, "synthetic-rotated")
    }

    func testCancellationFinishesRefreshAndPersistenceWithoutInference() async throws {
        let store = TestChatGPTPersistence(), client = storedBroker(store)
        let began = expectation(description: "Server consumed rotating token")
        let release = DispatchSemaphore(value: 0)
        defer { release.signal() }
        let renewed = renewal, terminal = completed
        let lock = NSLock(); var requests = 0
        ChatGPTHTTP.handler = { request in
            if request.url!.path.hasSuffix("/oauth/token") {
                began.fulfill()
                guard release.wait(timeout: .now() + 5) == .success else { throw ChatGPTError.unavailable }
                return (200, renewed)
            }
            lock.withLock { requests += 1 }
            return (200, terminal)
        }
        let operation = Task {
            try await client.response(account: "oaiapp_test", body: OpenAIRequest.transform(source: "Synthetic", instruction: "Edit"))
        }
        await fulfillment(of: [began], timeout: 5)
        operation.cancel(); release.signal()
        do { _ = try await operation.value; XCTFail("Cancelled dictation must stop before inference") }
        catch { XCTAssertTrue(error is CancellationError) }
        XCTAssertEqual(requests, 0)
        XCTAssertEqual(store.snapshot().records["oaiapp_test"]?.refresh, "synthetic-rotated")
    }

    func testPendingReplacementCannotOverwriteANewerSignIn() async throws {
        let store = TestChatGPTPersistence(), client = storedBroker(store)
        let renewed = renewal, terminal = completed
        ChatGPTHTTP.handler = { request in
            if request.url!.path.hasSuffix("/oauth/token") { return (200, renewed) }
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer synthetic-manual")
            return (200, terminal)
        }
        store.denyWrites(true)
        do {
            _ = try await client.response(account: "oaiapp_test", body: OpenAIRequest.transform(source: "Synthetic", instruction: "Edit"))
            XCTFail("A failed save must stop inference")
        } catch { XCTAssertTrue(error is ChatGPTError) }
        var saved = store.snapshot()
        saved.records["oaiapp_test"]?.access = "synthetic-manual"
        saved.records["oaiapp_test"]?.refresh = "synthetic-manual-refresh"
        saved.records["oaiapp_test"]?.expires = Date().timeIntervalSince1970 + 3600
        store.replace(saved); store.denyWrites(false)
        _ = try await client.response(account: "oaiapp_test", body: OpenAIRequest.transform(source: "Synthetic", instruction: "Edit"))
        XCTAssertEqual(store.snapshot().records["oaiapp_test"]?.refresh, "synthetic-manual-refresh")
    }

    func testSignOutDuringRefreshRevokesTheReplacementAndStopsWaitingInference() async throws {
        let store = TestChatGPTPersistence(), client = storedBroker(store)
        let began = expectation(description: "Refresh started"), signingOut = expectation(description: "Sign-out read the journal")
        let release = DispatchSemaphore(value: 0)
        defer { release.signal() }
        let renewed = renewal
        let lock = NSLock(); var requests = 0; var revoked = false
        ChatGPTHTTP.handler = { request in
            if request.url!.path.hasSuffix("/oauth/token") {
                began.fulfill()
                guard release.wait(timeout: .now() + 5) == .success else { throw ChatGPTError.unavailable }
                return (200, renewed)
            }
            if request.url!.path.hasSuffix("/oauth/revoke") {
                var body = request.httpBody ?? Data()
                if let stream = request.httpBodyStream {
                    stream.open(); defer { stream.close() }
                    var buffer = [UInt8](repeating: 0, count: 1024)
                    while stream.hasBytesAvailable {
                        let count = stream.read(&buffer, maxLength: buffer.count)
                        if count <= 0 { break }; body.append(contentsOf: buffer.prefix(count))
                    }
                }
                XCTAssertTrue(String(decoding: body, as: UTF8.self).contains("token=synthetic-rotated"))
                lock.withLock { revoked = true }
                return (200, "")
            }
            lock.withLock { requests += 1 }
            return (200, "")
        }
        let operation = Task { try await client.response(account: "oaiapp_test", body: OpenAIRequest.transform(source: "Synthetic", instruction: "Edit")) }
        await fulfillment(of: [began], timeout: 5)
        store.observeNextRead { signingOut.fulfill() }
        let disconnect = Task { try await client.disconnect() }
        await fulfillment(of: [signingOut], timeout: 5)
        release.signal()
        try await disconnect.value
        do { _ = try await operation.value; XCTFail("Sign-out must stop waiting inference") }
        catch { XCTAssertTrue(error is ChatGPTError) }
        XCTAssertTrue(revoked); XCTAssertEqual(requests, 0)
        XCTAssertNil(store.snapshot().active)
        XCTAssertTrue(store.snapshot().records["oaiapp_test"]?.refresh.isEmpty == true)
    }

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

final class TestChatGPTPersistence: @unchecked Sendable {
    private let lock = NSLock()
    private var readDenied = false, writeDenied = false
    private var journal: ChatGPTJournal
    private var nextRead: (@Sendable () -> Void)?
    init() {
        let record = ChatGPTRecord(client: "oaiapp_test", subject: "synthetic", email: "synthetic@example.test",
            access: "synthetic-expired", refresh: "synthetic-refresh", scope: "chatgpt.tokens.use.direct", expires: 1)
        journal = ChatGPTJournal(records: [record.client: record], active: record.client, last: record.client)
    }
    func denyReads(_ value: Bool) { lock.withLock { readDenied = value } }
    func denyWrites(_ value: Bool) { lock.withLock { writeDenied = value } }
    func snapshot() -> ChatGPTJournal { lock.withLock { journal } }
    func replace(_ saved: ChatGPTJournal) { lock.withLock { journal = saved } }
    func observeNextRead(_ action: @escaping @Sendable () -> Void) { lock.withLock { nextRead = action } }
    var persistence: ChatGPTPersistence {
        ChatGPTPersistence(load: { try self.lock.withLock {
            if self.readDenied { throw ChatGPTError.unavailable }
            let action = self.nextRead; self.nextRead = nil; action?()
            return self.journal
        } }, save: { value in try self.lock.withLock {
            if self.writeDenied { throw ChatGPTError.unavailable }
            self.journal = value
        } })
    }
}
