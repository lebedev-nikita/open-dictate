import AppKit
import ApplicationServices
import XCTest
import OpenDictateCore
@testable import OpenDictate

private final class StartupRecorder: AudioRecording, @unchecked Sendable {
    private let lock = NSLock()
    private var running = false
    private var audio = Data()
    private var onReady: (@Sendable () -> Void)?
    private var onChunk: (@Sendable (Data) -> Void)?
    private let firstChunks: [Data]
    init(firstChunks: [Data]) { self.firstChunks = firstChunks }
    var isRecording: Bool { lock.withLock { running } }
    func start(keepAudio: Bool, onReady: @escaping @Sendable () -> Void, onChunk: @escaping @Sendable (Data) -> Void,
               onLevel: @escaping @Sendable (Float) -> Void,
               onError: @escaping @Sendable (DictationError) -> Void) async throws {
        lock.withLock { running = true; self.onReady = onReady; self.onChunk = onChunk }
        for chunk in firstChunks { emit(chunk) }
    }
    func emit(_ chunk: Data) {
        let callback = lock.withLock { () -> (@Sendable (Data) -> Void)? in
            guard running else { return nil }
            audio.append(chunk)
            return onChunk
        }
        callback?(chunk)
        let ready = lock.withLock { let ready = onReady; onReady = nil; return ready }
        ready?()
    }
    func stop() async -> (Data, Int) {
        lock.withLock {
            running = false; onReady = nil; onChunk = nil
            let result = (audio, audio.count)
            audio = Data()
            return result
        }
    }
}

final class DictationStartupTests: XCTestCase {
    @MainActor func testAccurateDoesNotAdvertiseRecordingUntilFirstPCMAndKeepsTheBeginning() async throws {
        let recorder = StartupRecorder(firstChunks: [])
        let domain = "com.opendictate.tests.startup.\(UUID().uuidString)"
        let first = Data(repeating: 1, count: 1_920), rest = Data(repeating: 2, count: 3_840)
        let model = AppModel(localOnly: true, defaults: UserDefaults(suiteName: domain)!,
            captureTarget: { _, _ in Self.target() }, makeRecorder: { recorder },
            transcribeAudio: { pcm, _, _, _ in
                XCTAssertEqual(pcm, first + rest)
                return "Complete beginning."
            })
        defer { model.shutdown(); UserDefaults.standard.removePersistentDomain(forName: domain) }
        model.toggleLocalRecording(mode: .accurate)
        try await wait { recorder.isRecording }
        // Let recorder.start finish while Bluetooth is still producing no samples.
        for _ in 0..<10 { await Task.yield() }
        XCTAssertEqual(model.phase, .preparing, "An engine start is not microphone readiness")
        recorder.emit(first)
        try await wait { model.phase == .recording }
        recorder.emit(rest)
        model.stop()
        try await wait { model.phase == .idle }
        XCTAssertEqual(model.lastTranscript, "Complete beginning.")
    }

    @MainActor func testStopBeforeFirstBluetoothBufferFinishesWithoutWaitingForReadiness() async throws {
        let recorder = StartupRecorder(firstChunks: [])
        let domain = "com.opendictate.tests.startup.\(UUID().uuidString)"
        let model = AppModel(localOnly: true, defaults: UserDefaults(suiteName: domain)!,
            captureTarget: { _, _ in Self.target() }, makeRecorder: { recorder },
            transcribeAudio: { _, _, _, _ in XCTFail("Empty input must not be submitted"); return "" })
        defer { model.shutdown(); UserDefaults.standard.removePersistentDomain(forName: domain) }
        model.toggleLocalRecording(mode: .accurate)
        try await wait { recorder.isRecording }
        for _ in 0..<10 { await Task.yield() }
        model.stop()
        try await wait { model.phase == .idle }
        XCTAssertFalse(recorder.isRecording)
        XCTAssertEqual(model.lastTranscript, "")
    }

    @MainActor func testMicrophoneStartsBeforeSocketCreationAndEarlySpeechSurvivesStopBeforeConfiguration() async throws {
        let chunks = (1...4).map { Data(repeating: UInt8($0), count: AudioConstants.chunkBytes) }
        let recorder = StartupRecorder(firstChunks: Array(chunks.prefix(3))), socket = MockSocket()
        let domain = "com.opendictate.tests.startup.\(UUID().uuidString)"
        let model = AppModel(localOnly: true, defaults: UserDefaults(suiteName: domain)!,
            captureTarget: { _, _ in Self.target() }, makeRecorder: { recorder },
            makeLiveSession: { _, context, partial in
                XCTAssertTrue(recorder.isRecording, "Client/socket creation must not delay the microphone")
                // Speech produced while the socket is being created must already be captured.
                recorder.emit(chunks[3])
                return LiveTranscriptionSession(socket: socket, context: context, partial: partial)
            })
        defer { model.shutdown(); UserDefaults.standard.removePersistentDomain(forName: domain) }
        model.toggleLocalRecording(mode: .live)
        try await wait { model.phase == .recording && socket.messages.count == 1 }
        XCTAssertEqual(socket.messages.first?["type"] as? String, "session.update")
        XCTAssertTrue(recorder.isRecording)
        model.stop()
        try await wait { !recorder.isRecording }
        XCTAssertFalse(socket.messages.contains { $0["type"] as? String == "input_audio_buffer.commit" })
        try socket.emit(["type": "session.updated"])
        try await wait { socket.messages.last?["type"] as? String == "input_audio_buffer.commit" }
        let sentAudio = socket.messages.compactMap { ($0["audio"] as? String).flatMap { Data(base64Encoded: $0) } }
        XCTAssertEqual(sentAudio, chunks, "Include speech from recorder startup and socket creation, in order")
        try socket.emit(["type": "conversation.item.input_audio_transcription.completed", "item_id": "a", "transcript": "Complete speech."])
        try await wait { model.phase == .idle }
        XCTAssertEqual(model.lastTranscript, "Complete speech.")
        XCTAssertTrue(model.canRetranscribe)
    }

    @MainActor func testCancelWhileConnectingStopsMicrophoneWithoutCommittingOrDelivering() async throws {
        let recorder = StartupRecorder(firstChunks: [Data(repeating: 1, count: 5_760)]), socket = MockSocket()
        let domain = "com.opendictate.tests.startup.\(UUID().uuidString)"
        let model = AppModel(localOnly: true, defaults: UserDefaults(suiteName: domain)!,
            captureTarget: { _, _ in Self.target() }, makeRecorder: { recorder },
            makeLiveSession: { _, context, partial in LiveTranscriptionSession(socket: socket, context: context, partial: partial) })
        defer { model.shutdown(); UserDefaults.standard.removePersistentDomain(forName: domain) }
        model.toggleLocalRecording(mode: .live)
        try await wait { model.phase == .recording && socket.messages.count == 1 }
        model.cancel()
        try await wait { model.phase == .idle }
        XCTAssertFalse(recorder.isRecording)
        XCTAssertEqual(model.lastTranscript, "")
        XCTAssertFalse(model.canRetranscribe)
        XCTAssertEqual(socket.messages.compactMap { $0["type"] as? String }, ["session.update"])
    }

    @MainActor private static func target() -> TextTarget {
        TextTarget(element: AXUIElementCreateApplication(getpid()), application: .current,
                   snapshot: EditableTextSnapshot(original: "", selection: NSRange(location: 0, length: 0)))
    }

    @MainActor private func wait(_ condition: () -> Bool) async throws {
        let deadline = ContinuousClock.now + .seconds(2)
        while !condition() {
            guard ContinuousClock.now < deadline else { throw DictationError.timeout }
            try await Task.sleep(for: .milliseconds(5))
        }
    }
}
