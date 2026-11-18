import AppKit
import XCTest
import OpenDictateCore
@testable import OpenDictate

/// Manually advanced time for the shared meeting clock.
private final class TestTime: @unchecked Sendable {
    private let lock = NSLock()
    private var current = ContinuousClock.now
    var now: ContinuousClock.Instant { lock.withLock { current } }
    func advance(_ millis: Int) { lock.withLock { current = current.advanced(by: .milliseconds(millis)) } }
}

/// A capture channel driven by the test. `holdStart` simulates macOS waiting on a permission prompt;
/// `gated` holds start until `release()`; `stopDelay` simulates a slow device stop.
private final class ScriptedRecorder: AudioRecording, @unchecked Sendable {
    private let lock = NSLock()
    private var onChunk: (@Sendable (Data) -> Void)?
    private var released = false
    private(set) var starts = 0
    private(set) var stops = 0
    let holdStart: Bool
    let gated: Bool
    let failStart: Bool
    let stopDelay: Duration

    init(holdStart: Bool = false, gated: Bool = false, failStart: Bool = false, stopDelay: Duration = .zero) {
        self.holdStart = holdStart; self.gated = gated; self.failStart = failStart; self.stopDelay = stopDelay
    }

    func release() { lock.withLock { released = true } }

    private func waitForGate() async throws {
        while gated && !lock.withLock({ released }) { try await Task.sleep(for: .milliseconds(10)) }
    }

    func start(keepAudio: Bool, onReady: @escaping @Sendable () -> Void, onChunk: @escaping @Sendable (Data) -> Void,
               onLevel: @escaping @Sendable (Float) -> Void,
               onError: @escaping @Sendable (DictationError) -> Void) async throws {
        lock.withLock { starts += 1 }
        if holdStart { try await Task.sleep(for: .seconds(3_600)) }
        try await waitForGate()
        if failStart { throw DictationError.audio }
        lock.withLock { self.onChunk = onChunk }
        onReady()
    }

    func emit(_ chunk: Data) {
        let callback = lock.withLock { onChunk }
        callback?(chunk)
    }

    func stop() async -> (Data, Int) {
        lock.withLock { stops += 1; onChunk = nil }
        // AudioRecorder serializes stop behind a start that macOS is still holding.
        if holdStart { try? await Task.sleep(for: .seconds(3_600)) }
        try? await waitForGate()
        if stopDelay > .zero { try? await Task.sleep(for: stopDelay) }
        return (Data(), 0)
    }
}

/// Peak amplitude labels each synthetic utterance, so the fake recognizer knows what was "said".
private enum Speech {
    static func tone(_ millis: Int, _ amplitude: Double) -> Data {
        let count = AudioConstants.sampleRate * millis / 1_000
        // Explicit steps: Xcode 16 cannot type-check this as one expression.
        var samples = [Int16](repeating: 0, count: count)
        for index in samples.indices {
            let phase: Double = 2 * Double.pi * 440 * Double(index) / Double(AudioConstants.sampleRate)
            let value: Double = amplitude * 32_767 * sin(phase)
            samples[index] = Int16(value)
        }
        return samples.withUnsafeBytes { Data($0) }
    }
    static func silence(_ millis: Int) -> Data { Data(count: AudioConstants.sampleRate * millis / 1_000 * 2) }
    static func peak(_ pcm: Data) -> Double {
        let peak = pcm.withUnsafeBytes { $0.bindMemory(to: Int16.self).map { abs(Int($0)) }.max() ?? 0 }
        return (Double(peak) / 32_767 * 10).rounded() / 10
    }
}

@MainActor
final class MeetingControllerTests: XCTestCase {
    private var time: TestTime!
    private var microphone: ScriptedRecorder!
    private var call: ScriptedRecorder!
    private var store: MeetingStore!
    private var preferences: Preferences!
    private var notifications = [String]()

    override func setUp() async throws {
        time = TestTime()
        microphone = ScriptedRecorder(); call = ScriptedRecorder()
        store = MeetingStore(inMemory: true)
        let defaults = UserDefaults(suiteName: "meeting-tests-\(UUID().uuidString)")!
        preferences = Preferences(defaults: defaults)
        preferences.interfaceLanguage = "en"
        notifications = []
    }

    private func controller(transcribe: @escaping MeetingController.Transcriber = { pcm, _, _, _ in
                                ["0.2": "Let's start with the release plan.", "0.3": "Agreed, channels first.",
                                 "0.4": "Second point."][String(Speech.peak(pcm))] ?? "" },
                            key: String? = "sk-test", microphoneAllowed: Bool = true, call: ScriptedRecorder? = nil,
                            pendingLimit: Int = MeetingLimits.maximumPendingAudioBytes) -> MeetingController {
        let time = self.time!
        // Looked up per meeting, so a test can give the next meeting other recorders.
        return MeetingController(store: store, preferences: preferences, replacements: preferences.replacements,
            makeMicrophone: { [unowned self] in self.microphone }, makeCallAudio: { [unowned self] _ in call ?? self.call },
            transcribe: transcribe,
            loadKey: { key }, microphoneAllowed: { microphoneAllowed },
            notify: { [weak self] in self?.notifications.append($0) },
            clock: MeetingClock(now: { time.now }), pendingLimit: pendingLimit)
    }

    /// Feeds both channels in 40 ms lockstep while the meeting clock advances with the audio.
    private func feed(microphone mic: Data = Data(), call other: Data = Data()) async throws {
        let length = max(mic.count, other.count)
        var offset = 0
        while offset < length {
            time.advance(40)
            let end = offset + AudioConstants.chunkBytes
            if offset < mic.count { microphone.emit(mic.subdata(in: offset..<min(end, mic.count))) }
            if offset < other.count { call.emit(other.subdata(in: offset..<min(end, other.count))) }
            offset = end
        }
        await settle()
    }

    private func settle() async {
        for _ in 0..<20 { await Task.yield() }
        try? await Task.sleep(for: .milliseconds(20))
    }

    private func waitUntil(_ timeout: TimeInterval = 6, _ condition: () -> Bool) async throws {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition() {
            guard Date() < deadline else { XCTFail("Timed out"); return }
            try await Task.sleep(for: .milliseconds(20))
        }
    }

    private func started(_ meetings: MeetingController) async throws {
        try meetings.start(source: .application(bundleID: "us.zoom.xos", name: "Zoom"))
        try await waitUntil { meetings.microphone == .live && (meetings.callAudio == .live || call.holdStart || call.gated) }
    }

    func testStartNeedsAKeyAndMicrophoneAccess() throws {
        XCTAssertThrowsError(try controller(key: nil).start(source: .system))
        XCTAssertThrowsError(try controller(key: "").start(source: .system))
        XCTAssertThrowsError(try controller(microphoneAllowed: false).start(source: .system))
        XCTAssertTrue(store.meetings.isEmpty)
    }

    func testBothChannelsAreRecognizedAndMergedOnTheMeetingClock() async throws {
        let meetings = controller()
        try await started(meetings)
        XCTAssertEqual(store.meetings.first?.state, .recording, "The transcript is saved while recording")
        try await feed(microphone: Speech.silence(1_000) + Speech.tone(1_500, 0.2) + Speech.silence(3_000),
                       call: Speech.silence(3_000) + Speech.tone(1_000, 0.3) + Speech.silence(1_500))
        meetings.finish()
        try await waitUntil { meetings.phase == .idle }
        let saved = try XCTUnwrap(store.meetings.first)
        XCTAssertEqual(saved.state, .finished)
        XCTAssertEqual(saved.sourceName, "Zoom")
        XCTAssertEqual(saved.utterances.map(\.speaker), [Meeting.meID, Meeting.otherID])
        XCTAssertEqual(saved.utterances.map(\.text), ["Let's start with the release plan.", "Agreed, channels first."])
        XCTAssertEqual(saved.utterances[0].startMillis, 1_000, accuracy: 100)
        XCTAssertEqual(saved.utterances[1].startMillis, 3_000, accuracy: 100)
        XCTAssertEqual(saved.durationMillis, 5_500, accuracy: 100)
        XCTAssertEqual(meetings.lastFinishedID, saved.id)
        XCTAssertTrue(meetings.markdown(saved).contains("**[00:00:03] Other speaker:** Agreed, channels first."))
        XCTAssertEqual(microphone.stops, 1); XCTAssertEqual(call.stops, 1)
    }

    func testAnotherMeetingStartsFromZero() async throws {
        let meetings = controller()
        try await started(meetings)
        try await feed(microphone: Speech.silence(1_000) + Speech.tone(1_500, 0.2) + Speech.silence(3_000))
        meetings.finish()
        try await waitUntil { meetings.phase == .idle }
        let first = try XCTUnwrap(meetings.lastFinishedID)

        try await started(meetings)
        XCTAssertNotEqual(meetings.meeting?.id, first)
        XCTAssertEqual(meetings.elapsedMillis, 0)
        try await feed(microphone: Speech.silence(2_000) + Speech.tone(1_000, 0.3) + Speech.silence(1_500))
        meetings.finish()
        try await waitUntil { meetings.phase == .idle }
        let second = try XCTUnwrap(store.meetings.first { $0.id != first })
        XCTAssertEqual(store.meetings.count, 2)
        XCTAssertEqual(second.utterances.map(\.text), ["Agreed, channels first."])
        XCTAssertEqual(second.utterances[0].startMillis, 2_000, accuracy: 100)
        XCTAssertEqual(second.durationMillis, 4_500, accuracy: 100)
        XCTAssertEqual(store.meetings.first { $0.id == first }?.utterances.count, 1)
    }

    func testEchoOfTheCallIsRemovedFromMe() async throws {
        let meetings = controller()
        try await started(meetings)
        // Without headphones the microphone hears the same words at the same time.
        try await feed(microphone: Speech.silence(500) + Speech.tone(1_500, 0.3) + Speech.silence(3_000),
                       call: Speech.silence(450) + Speech.tone(1_500, 0.3) + Speech.silence(3_000))
        meetings.finish()
        try await waitUntil { meetings.phase == .idle }
        XCTAssertEqual(store.meetings.first?.utterances.map(\.speaker), [Meeting.otherID])
    }

    func testRecoverableFailuresRetryTheSameUtterance() async throws {
        let attempts = Counter()
        let meetings = controller(transcribe: { _, _, _, _ in
            if attempts.increment() == 1 { throw DictationError.connection }
            return "After retry."
        })
        try await started(meetings)
        try await feed(microphone: Speech.tone(1_000, 0.2) + Speech.silence(1_000))
        try await waitUntil { meetings.issue != nil }
        XCTAssertEqual(meetings.meeting?.utterances.first?.status, .pending, "Shown as waiting for recognition")
        try await waitUntil(4) { meetings.meeting?.utterances.first?.status == .recognized }
        XCTAssertEqual(meetings.meeting?.utterances.first?.text, "After retry.")
        XCTAssertNil(meetings.issue)
        XCTAssertEqual(attempts.value, 2)
        meetings.cancel()
    }

    func testUnrecoverableFailureKeepsTheGapVisible() async throws {
        let meetings = controller(transcribe: { _, _, _, _ in throw DictationError.api(400) })
        try await started(meetings)
        try await feed(microphone: Speech.tone(1_000, 0.2) + Speech.silence(1_000))
        meetings.finish()
        try await waitUntil { meetings.phase == .idle }
        XCTAssertEqual(store.meetings.first?.utterances.map(\.status), [.failed])
        XCTAssertTrue(meetings.markdown(try XCTUnwrap(store.meetings.first)).contains("*(not recognized)*"))
    }

    func testBacklogPausesCaptureInsteadOfDroppingSpeech() async throws {
        let meetings = controller(transcribe: { _, _, _, _ in try await Task.sleep(for: .seconds(3_600)); return "" },
                                  pendingLimit: 100_000)
        try await started(meetings)
        try await feed(microphone: Speech.tone(1_500, 0.2) + Speech.silence(1_000) + Speech.tone(1_500, 0.2) + Speech.silence(1_000))
        try await waitUntil { meetings.phase == .paused }
        XCTAssertEqual(meetings.pauseReason, .backlog)
        XCTAssertEqual(notifications.count, 1)
        XCTAssertEqual(meetings.meeting?.utterances.count, 2)
        XCTAssertTrue(meetings.meeting?.utterances.allSatisfy { $0.status == .pending } == true)
        meetings.resume()
        XCTAssertEqual(meetings.phase, .paused, "Resuming waits until recognition catches up")
        meetings.finish()
        meetings.finishNow()
        try await waitUntil { meetings.phase == .idle }
        XCTAssertEqual(store.meetings.first?.utterances.map(\.status), [.failed, .failed])
    }

    func testDictationDuringAMeetingStaysOutOfItsTranscript() async throws {
        let meetings = controller()
        try await started(meetings)
        meetings.noteDictation(active: true)
        try await feed(microphone: Speech.tone(1_500, 0.2) + Speech.silence(1_000))
        meetings.noteDictation(active: false)
        try await feed(microphone: Speech.tone(1_000, 0.4) + Speech.silence(1_000))
        meetings.finish()
        try await waitUntil { meetings.phase == .idle }
        XCTAssertEqual(store.meetings.first?.utterances.map(\.text), ["Second point."])
    }

    func testPausedTimeIsExcludedFromTimestamps() async throws {
        let meetings = controller()
        try await started(meetings)
        try await feed(microphone: Speech.tone(1_000, 0.2) + Speech.silence(1_000))
        meetings.pause()
        XCTAssertEqual(meetings.phase, .paused)
        try await waitUntil { self.microphone.stops == 1 }
        time.advance(60_000)
        meetings.resume()
        try await waitUntil { meetings.microphone == .live }
        XCTAssertEqual(microphone.starts, 2)
        try await feed(microphone: Speech.tone(1_000, 0.4) + Speech.silence(1_000))
        meetings.finish()
        try await waitUntil { meetings.phase == .idle }
        let starts = try XCTUnwrap(store.meetings.first).utterances.map(\.startMillis)
        XCTAssertEqual(starts.count, 2)
        XCTAssertEqual(starts[1], 2_000, accuracy: 120)
        XCTAssertEqual(store.meetings.first?.durationMillis ?? 0, 4_000, accuracy: 120)
    }

    func testCancelDeletesTheMeeting() async throws {
        let meetings = controller()
        try await started(meetings)
        try await feed(microphone: Speech.tone(1_000, 0.2) + Speech.silence(1_000))
        XCTAssertEqual(store.meetings.count, 1)
        meetings.cancel()
        XCTAssertEqual(meetings.phase, .idle)
        XCTAssertTrue(store.meetings.isEmpty)
        XCTAssertNil(meetings.meeting)
    }

    func testAPermissionPromptOnCallAudioNeverBlocksTheMeeting() async throws {
        let blocked = ScriptedRecorder(holdStart: true)
        call = blocked
        let meetings = controller(call: blocked)
        try await started(meetings)
        XCTAssertEqual(meetings.callAudio, .starting)
        try await feed(microphone: Speech.tone(1_000, 0.2) + Speech.silence(1_000))
        let started = Date()
        meetings.finish()
        try await waitUntil(6) { meetings.phase == .idle }
        XCTAssertGreaterThanOrEqual(Date().timeIntervalSince(started), 2.9, "The call channel gets a moment to flush")
        XCTAssertLessThan(Date().timeIntervalSince(started), 5)
        XCTAssertEqual(store.meetings.first?.utterances.map(\.text), ["Let's start with the release plan."])
    }

    func testFinishedMeetingsAreExportedWithoutReplacingFiles() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        preferences.meetingExportFolder = folder.path
        let meetings = controller()
        try await started(meetings)
        let name = MeetingFormat.fileName(try XCTUnwrap(meetings.meeting), russian: false)
        try Data("keep".utf8).write(to: folder.appendingPathComponent(name))
        try await feed(microphone: Speech.tone(1_000, 0.2) + Speech.silence(1_000))
        meetings.finish()
        try await waitUntil { meetings.phase == .idle }
        XCTAssertEqual(try String(contentsOf: folder.appendingPathComponent(name), encoding: .utf8), "keep")
        let exported = try String(contentsOf: folder.appendingPathComponent((name as NSString).deletingPathExtension + " 2.md"),
                                  encoding: .utf8)
        XCTAssertTrue(exported.hasPrefix("# Meeting "))
        XCTAssertTrue(exported.contains("**[00:00:00] Me:** Let's start with the release plan."))
        XCTAssertTrue(meetings.copyLatest(to: NSPasteboard(name: .init("meeting-tests-\(UUID().uuidString)"))))
    }

    func testDictationAlreadyRunningWhenTheMeetingStartsStaysOut() async throws {
        let meetings = controller()
        // The app reports dictation when it starts, which can be before the meeting does.
        meetings.noteDictation(active: true)
        try await started(meetings)
        try await feed(microphone: Speech.tone(1_500, 0.2) + Speech.silence(1_000))
        meetings.noteDictation(active: false)
        try await feed(microphone: Speech.tone(1_000, 0.4) + Speech.silence(1_000))
        meetings.finish()
        try await waitUntil { meetings.phase == .idle }
        XCTAssertEqual(store.meetings.first?.utterances.map(\.text), ["Second point."])
    }
}

private final class Counter: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0
    var value: Int { lock.withLock { count } }
    func increment() -> Int { lock.withLock { count += 1; return count } }
}
