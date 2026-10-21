import XCTest
import OpenDictateCore
@testable import OpenDictate

private final class MockSocket: LiveSocket, @unchecked Sendable {
    let responseStatus: Int? = nil
    private let lock = NSLock()
    private var sent = [String]()
    private var events = [URLSessionWebSocketTask.Message]()
    private var waiter: CheckedContinuation<URLSessionWebSocketTask.Message, Error>?
    private var closed = false
    func resume() {}
    func send(_ message: URLSessionWebSocketTask.Message) async throws {
        try lock.withLock {
            if closed { throw URLError(.cancelled) }
            if case .string(let string) = message { sent.append(string) }
        }
    }
    func receive() async throws -> URLSessionWebSocketTask.Message {
        try await withCheckedThrowingContinuation { continuation in
            lock.withLock {
                if closed { continuation.resume(throwing: URLError(.cancelled)) }
                else if !events.isEmpty { continuation.resume(returning: events.removeFirst()) }
                else { waiter = continuation }
            }
        }
    }
    func close() {
        lock.withLock {
            closed = true; waiter?.resume(throwing: URLError(.cancelled)); waiter = nil
        }
    }
    func emit(_ event: [String: Any]) throws {
        let message = URLSessionWebSocketTask.Message.string(String(decoding: try JSONSerialization.data(withJSONObject: event), as: UTF8.self))
        lock.withLock {
            if let continuation = waiter { waiter = nil; continuation.resume(returning: message) }
            else { events.append(message) }
        }
    }
    var messages: [[String: Any]] { lock.withLock { sent.compactMap { try? JSONSerialization.jsonObject(with: Data($0.utf8)) as? [String: Any] } } }
}

final class NetworkTests: XCTestCase {
    func testEarlyAudioFlushesInOrderBeforeExplicitCommit() async throws {
        let socket = MockSocket()
        let live = LiveTranscriptionSession(socket: socket) { _ in }
        try await live.start()
        try await live.append(Data([1, 2]))
        try await live.append(Data([3, 4]))
        try await wait { socket.messages.count == 1 }
        XCTAssertEqual(socket.messages.first?["type"] as? String, "session.update")
        try socket.emit(["type": "session.updated"])
        try await wait { socket.messages.count == 3 }
        try await live.append(Data([5, 6]))
        let completion = Task { try await live.finish(timeout: 2) }
        try await wait { socket.messages.count == 5 }
        XCTAssertEqual(socket.messages.compactMap { $0["type"] as? String },
                       ["session.update", "input_audio_buffer.append", "input_audio_buffer.append", "input_audio_buffer.append", "input_audio_buffer.commit"])
        let chunks = socket.messages.compactMap { ($0["audio"] as? String).flatMap { Data(base64Encoded: $0) } }
        XCTAssertEqual(chunks, [Data([1, 2]), Data([3, 4]), Data([5, 6])])
        try socket.emit(["type": "conversation.item.input_audio_transcription.completed", "item_id": "a", "transcript": "Done."])
        let final = try await completion.value
        XCTAssertEqual(final, "Done.")
    }

    func testCancellationDoesNotWaitForConfigurationOrCommit() async throws {
        let socket = MockSocket()
        let live = LiveTranscriptionSession(socket: socket) { _ in }
        try await live.start()
        try await live.append(Data([1, 2]))
        let finishing = Task { try await live.finish(timeout: 2) }
        finishing.cancel()
        do { _ = try await finishing.value; XCTFail("Cancellation should fail") }
        catch is CancellationError {}
        await live.close()
        XCTAssertFalse(socket.messages.contains { $0["type"] as? String == "input_audio_buffer.commit" })
    }

    func testPendingAudioIsBoundedWithoutSilentlyDroppingSpeech() async throws {
        let socket = MockSocket()
        let live = LiveTranscriptionSession(socket: socket) { _ in }
        try await live.start()
        try await wait { socket.messages.count == 1 }
        for _ in 0..<AudioConstants.maximumQueuedChunks { try await live.append(Data([1, 2])) }
        do { try await live.append(Data([1, 2])); XCTFail("Overflow must fail") }
        catch let error as DictationError { if case .queueFull = error {} else { XCTFail("Wrong error") } }
    }

    private func wait(_ condition: () -> Bool) async throws {
        for _ in 0..<200 {
            if condition() { return }
            try await Task.sleep(nanoseconds: 5_000_000)
        }
        XCTFail("Timed out waiting for mock transport")
    }
}
