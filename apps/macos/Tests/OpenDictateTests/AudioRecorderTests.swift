import AVFoundation
import OpenDictateAudio
import OpenDictateCore
import XCTest
@testable import OpenDictate

private final class TestCaptureEngine: AudioCaptureEngine, @unchecked Sendable {
    private let lock = NSLock()
    let format: AVAudioFormat
    private var running = false
    private var buffer: ODAudioCaptureBuffer?
    private let changed: @Sendable () -> Void
    let failsToStart: Bool
    let formatRace: Bool
    let changesOnStart: Bool
    init(format: AVAudioFormat, failsToStart: Bool, formatRace: Bool, changesOnStart: Bool,
         changed: @escaping @Sendable () -> Void) {
        self.format = format; self.failsToStart = failsToStart; self.changed = changed
        self.formatRace = formatRace; self.changesOnStart = changesOnStart
    }
    var isRunning: Bool { lock.withLock { running } }
    func prepare() {}
    func start(buffer: ODAudioCaptureBuffer, format: AVAudioFormat) throws {
        if formatRace { throw AudioCaptureEngineError.configurationChanged }
        if failsToStart { throw DictationError.audio }
        lock.withLock { self.buffer = buffer; running = true }
        if changesOnStart { configurationChanged() }
    }
    func stop() { lock.withLock { running = false; buffer = nil } }
    func configurationChanged() { lock.withLock { running = false }; changed() }
    func emit(frames: AVAudioFrameCount) throws {
        let input = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames))
        input.frameLength = frames
        for index in 0..<Int(frames) { input.int16ChannelData![0][index] = 5_000 }
        lock.withLock { buffer?.write(frames: frames, from: input.audioBufferList) }
    }
}

private final class CaptureEngineFactory: @unchecked Sendable {
    private let lock = NSLock()
    private var engines = [TestCaptureEngine]()
    var failRecovery = false
    var formatFailures = 0
    var unstable = false
    var count: Int { lock.withLock { engines.count } }
    func engine(_ index: Int) -> TestCaptureEngine { lock.withLock { engines[index] } }
    func make(changed: @escaping @Sendable () -> Void) -> any AudioCaptureEngine {
        lock.withLock {
            let format = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 24_000, channels: 1, interleaved: true)!
            let engine = TestCaptureEngine(format: format, failsToStart: failRecovery && !engines.isEmpty,
                formatRace: engines.count < formatFailures, changesOnStart: unstable, changed: changed)
            engines.append(engine)
            return engine
        }
    }
    func recorder(prepare: Bool = false) -> AudioRecorder {
        AudioRecorder(makeEngine: { self.make(changed: $0) }, inputDevice: { 1 }, canPrepare: { _ in prepare })
    }
}

private final class RecorderEvents: @unchecked Sendable {
    private let lock = NSLock()
    private var ready = 0, failures = 0
    private var chunks = Data()
    var readyCount: Int { lock.withLock { ready } }
    var errorCount: Int { lock.withLock { failures } }
    var audio: Data { lock.withLock { chunks } }
    func start(_ recorder: AudioRecorder) async throws {
        try await recorder.start(keepAudio: true,
            onReady: { self.lock.withLock { self.ready += 1 } },
            onChunk: { data in self.lock.withLock { self.chunks.append(data) } }, onLevel: { _ in },
            onError: { _ in self.lock.withLock { self.failures += 1 } })
    }
}

final class AudioRecorderTests: XCTestCase {
    func testConfigurationChangeRestartsCaptureAndPreservesAudioAcrossBothGraphs() async throws {
        let factory = CaptureEngineFactory(), events = RecorderEvents(), recorder = factory.recorder()
        try await events.start(recorder)
        try factory.engine(0).emit(frames: 1_200)
        try await wait { events.readyCount == 1 }
        factory.engine(0).configurationChanged()
        try await wait { factory.count == 2 && factory.engine(1).isRunning }
        try factory.engine(1).emit(frames: 1_440)
        let (audio, bytes) = await recorder.stop()
        XCTAssertEqual(bytes, 5_280)
        XCTAssertEqual(audio.count, bytes)
        XCTAssertEqual(events.audio, audio)
        XCTAssertEqual(events.readyCount, 1)
        XCTAssertEqual(events.errorCount, 0)
        let secondStop = await recorder.stop()
        XCTAssertEqual(secondStop.1, 0)
    }

    func testIdleConfigurationChangeDiscardsThePreparedGraph() async throws {
        let factory = CaptureEngineFactory(), events = RecorderEvents(), recorder = factory.recorder(prepare: true)
        try await wait { factory.count == 1 }
        factory.engine(0).configurationChanged()
        try await events.start(recorder)
        XCTAssertEqual(factory.count, 2)
        _ = await recorder.stop()
    }

    func testFailedRecoveryReportsAnErrorAndRetainsOnlyAnExplicitRetryPrefix() async throws {
        let factory = CaptureEngineFactory(), events = RecorderEvents()
        factory.failRecovery = true
        let recorder = factory.recorder()
        try await events.start(recorder)
        try factory.engine(0).emit(frames: 2_400)
        try await wait { events.readyCount == 1 }
        factory.engine(0).configurationChanged()
        try await wait { events.errorCount == 1 }
        let (audio, bytes) = await recorder.stop()
        XCTAssertEqual(audio.count, 4_800)
        XCTAssertEqual(bytes, 0, "Interrupted audio must not be delivered as a complete dictation")
        XCTAssertEqual(events.errorCount, 1)
    }

    func testLateConfigurationChangeCannotRestartACancelledOrNewerRecording() async throws {
        let factory = CaptureEngineFactory(), events = RecorderEvents(), recorder = factory.recorder()
        try await events.start(recorder)
        let old = factory.engine(0)
        _ = await recorder.stop()
        try await events.start(recorder)
        old.configurationChanged()
        try factory.engine(1).emit(frames: 1_200)
        let (_, bytes) = await recorder.stop()
        XCTAssertEqual(bytes, 2_400)
        XCTAssertEqual(factory.count, 2)
        XCTAssertEqual(events.errorCount, 0)
    }

    func testFormatRaceDuringConnectionRetriesWithAFreshGraph() async throws {
        let factory = CaptureEngineFactory(), events = RecorderEvents()
        factory.formatFailures = 2
        let recorder = factory.recorder()
        try await events.start(recorder)
        XCTAssertEqual(factory.count, 3)
        try factory.engine(2).emit(frames: 1_200)
        let (_, bytes) = await recorder.stop()
        XCTAssertEqual(bytes, 2_400)
        XCTAssertEqual(events.readyCount, 1)
        XCTAssertEqual(events.errorCount, 0)
    }

    func testUnstableConfigurationFailsInsteadOfRestartingWithoutABound() async throws {
        let factory = CaptureEngineFactory(), events = RecorderEvents()
        factory.unstable = true
        let recorder = factory.recorder()
        try await events.start(recorder)
        try await wait { events.errorCount == 1 }
        let (_, bytes) = await recorder.stop()
        XCTAssertEqual(bytes, 0)
        XCTAssertEqual(factory.count, 4)
        XCTAssertEqual(events.errorCount, 1)
    }

    func testSuccessfulAudioResetsTheRecoveryBudgetAcrossLaterRouteChanges() async throws {
        let factory = CaptureEngineFactory(), events = RecorderEvents(), recorder = factory.recorder()
        try await events.start(recorder)
        for index in 0..<6 {
            let graph = factory.engine(index)
            try graph.emit(frames: 1_920)
            try await wait { events.audio.count >= (index + 1) * 3_840 }
            if index < 5 {
                graph.configurationChanged()
                try await wait { factory.count == index + 2 && factory.engine(index + 1).isRunning }
            }
        }
        let (audio, bytes) = await recorder.stop()
        XCTAssertEqual(bytes, 6 * 3_840)
        XCTAssertEqual(audio, events.audio)
        XCTAssertEqual(events.errorCount, 0)
        XCTAssertEqual(events.readyCount, 1)
    }

    private func wait(_ condition: () -> Bool) async throws {
        let deadline = ContinuousClock.now + .seconds(2)
        while !condition() {
            guard ContinuousClock.now < deadline else { throw DictationError.timeout }
            try await Task.sleep(for: .milliseconds(5))
        }
    }
}
