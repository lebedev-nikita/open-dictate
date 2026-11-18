import AppKit
import AVFoundation
import XCTest
import OpenDictateCore
@testable import OpenDictate

final class MeetingStoreTests: XCTestCase {
    private var folder: URL!

    override func setUp() {
        folder = FileManager.default.temporaryDirectory.appendingPathComponent("meetings-\(UUID().uuidString)", isDirectory: true)
    }
    override func tearDown() { try? FileManager.default.removeItem(at: folder) }

    private func permissions(_ url: URL) throws -> Int {
        try XCTUnwrap(FileManager.default.attributesOfItem(atPath: url.path)[.posixPermissions] as? Int)
    }

    func testMeetingsAreOwnerOnlyFilesAndUnfinishedOnesBecomeInterrupted() async throws {
        try await MainActor.run {
            let store = MeetingStore(folder: folder)
            var recording = Meeting(startedAt: Date(timeIntervalSinceNow: -60), sourceName: "Zoom", sourceBundleID: "us.zoom.xos")
            recording.insert(MeetingUtterance(speaker: Meeting.meID, startMillis: 0, durationMillis: 1, text: "Kept", status: .recognized))
            recording.insert(MeetingUtterance(speaker: Meeting.otherID, startMillis: 5, durationMillis: 1))
            var finished = Meeting(startedAt: Date(), sourceName: nil, sourceBundleID: nil, state: .finished)
            finished.rename(speaker: Meeting.otherID, to: "Anna")
            store.save(recording); store.save(finished)
            XCTAssertEqual(store.meetings.map(\.id), [finished.id, recording.id], "Newest first")
            XCTAssertEqual(try permissions(folder), 0o700)
            XCTAssertEqual(try permissions(folder.appendingPathComponent("\(recording.id.uuidString).json")), 0o600)
            try Data("not json".utf8).write(to: folder.appendingPathComponent("broken.json"))

            let reloaded = MeetingStore(folder: folder)
            XCTAssertEqual(reloaded.meetings.count, 2, "Unreadable files are skipped")
            let interrupted = try XCTUnwrap(reloaded.meeting(recording.id))
            XCTAssertEqual(interrupted.state, .interrupted)
            XCTAssertEqual(interrupted.utterances.map(\.status), [.recognized, .failed])
            XCTAssertEqual(reloaded.meeting(finished.id)?.speaker(Meeting.otherID)?.name, "Anna")
            reloaded.rename(finished.id, speaker: Meeting.meID, to: "Nikita")
            XCTAssertEqual(MeetingStore(folder: folder).meeting(finished.id)?.speaker(Meeting.meID)?.name, "Nikita")
        }
    }

    func testDeletingRemovesFilesAndKeepsTheMeetingBeingRecorded() async throws {
        try await MainActor.run {
            let store = MeetingStore(folder: folder)
            let active = Meeting(startedAt: Date(), sourceName: nil, sourceBundleID: nil)
            let old = Meeting(startedAt: Date(timeIntervalSinceNow: -3_600), sourceName: nil, sourceBundleID: nil, state: .finished)
            let older = Meeting(startedAt: Date(timeIntervalSinceNow: -7_200), sourceName: nil, sourceBundleID: nil, state: .finished)
            for meeting in [active, old, older] { store.save(meeting) }
            store.delete(old.id)
            XCTAssertFalse(FileManager.default.fileExists(atPath: folder.appendingPathComponent("\(old.id.uuidString).json").path))
            store.deleteAll(except: active.id)
            XCTAssertEqual(store.meetings.map(\.id), [active.id])
            XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: folder.path), ["\(active.id.uuidString).json"])
        }
    }
}

final class MeetingCaptureTests: XCTestCase {
    func testHelperProcessesBelongToTheirApplication() {
        let parents: [pid_t: pid_t] = [300: 200, 200: 100]
        func process(_ pid: pid_t, _ bundle: String) -> AudioProcesses.Process { .init(object: 1, pid: pid, bundleID: bundle) }
        // Browser helpers share the bundle prefix; other helpers descend from the main process.
        XCTAssertTrue(AudioProcesses.belongs(process(10, "com.google.Chrome.helper"), to: "com.google.Chrome", mainPIDs: []))
        XCTAssertTrue(AudioProcesses.belongs(process(300, "com.vendor.audio-engine"), to: "us.zoom.xos", mainPIDs: [100],
                                             parent: { parents[$0] }))
        XCTAssertFalse(AudioProcesses.belongs(process(11, "com.google.Chromecast"), to: "com.google.Chrome", mainPIDs: []))
        XCTAssertFalse(AudioProcesses.belongs(process(12, "com.apple.Music"), to: "us.zoom.xos", mainPIDs: [100],
                                              parent: { _ in nil }))
    }

    func testMeetingChannelsAreNotLimitedLikeDictation() throws {
        let format = try XCTUnwrap(AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 24_000, channels: 1, interleaved: false))
        let input = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 4_800))
        input.frameLength = 4_800
        for index in 0..<4_800 { input.floatChannelData![0][index] = 0.1 }
        func run(limit: Int?) throws -> Int {
            let pipeline = try AudioCapturePipeline(format: format, keepAudio: false, limitBytes: limit,
                                                    onReady: {}, onChunk: { _ in }, onLevel: { _ in })
            for _ in 0..<3 {
                pipeline.buffer.write(frames: input.frameLength, from: input.audioBufferList)
                try pipeline.drain()
            }
            return try pipeline.finish().1
        }
        XCTAssertThrowsError(try run(limit: 10_000))
        XCTAssertEqual(try run(limit: nil), 28_800, "The byte count still advances without a limit")
    }

    func testMeetingSourcePersistsAndDefaultsToSystemAudio() async throws {
        try await MainActor.run {
            let defaults = try XCTUnwrap(UserDefaults(suiteName: "meeting-source-\(UUID().uuidString)"))
            XCTAssertEqual(Preferences(defaults: defaults).meetingSource, .system)
            XCTAssertNil(Preferences(defaults: defaults).meetingExportFolder)
            XCTAssertFalse(Preferences(defaults: defaults).meetingNoticeAccepted)
            let preferences = Preferences(defaults: defaults)
            preferences.meetingSource = .application(bundleID: "us.zoom.xos", name: "Zoom")
            preferences.meetingNoticeAccepted = true
            preferences.meetingExportFolder = "/tmp/meetings"
            let restored = Preferences(defaults: defaults)
            XCTAssertEqual(restored.meetingSource, .application(bundleID: "us.zoom.xos", name: "Zoom"))
            XCTAssertTrue(restored.meetingNoticeAccepted)
            XCTAssertEqual(restored.meetingExportFolder, "/tmp/meetings")
        }
    }

    func testMeetingClockExcludesPauses() {
        final class Time: @unchecked Sendable { var now = ContinuousClock.now }
        let time = Time()
        let clock = MeetingClock(now: { time.now })
        clock.run(); time.now = time.now.advanced(by: .seconds(5))
        clock.pause(); time.now = time.now.advanced(by: .seconds(60))
        XCTAssertEqual(clock.millis, 5_000)
        clock.run(); time.now = time.now.advanced(by: .milliseconds(1_500))
        XCTAssertEqual(clock.millis, 6_500)
    }
}

final class MeetingIconAndShortcutTests: XCTestCase {
    func testMeetingStatesAreDistinctAndDictationTakesPrecedence() async throws {
        try await MainActor.run {
            let dictation = try XCTUnwrap(MenuBarIcon.image(for: .recording))
            let meeting = try XCTUnwrap(MenuBarIcon.image(for: .idle, meeting: .recording))
            let paused = try XCTUnwrap(MenuBarIcon.image(for: .idle, meeting: .paused))
            XCTAssertTrue(MenuBarIcon.image(for: .recording, meeting: .recording) === dictation)
            XCTAssertTrue(MenuBarIcon.image(for: .processing, meeting: .recording) === meeting)
            XCTAssertFalse(meeting.isTemplate, "The recording badge stays red")
            XCTAssertTrue(paused.isTemplate, "Paused follows the menu bar color")
            XCTAssertEqual(meeting.size, dictation.size); XCTAssertEqual(paused.size, dictation.size)
            for name in [NSAppearance.Name.aqua, .darkAqua] {
                let appearance = try XCTUnwrap(NSAppearance(named: name))
                let bitmap = try XCTUnwrap(NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 40, pixelsHigh: 40,
                    bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB,
                    bytesPerRow: 0, bitsPerPixel: 0))
                bitmap.size = meeting.size
                appearance.performAsCurrentDrawingAppearance {
                    NSGraphicsContext.saveGraphicsState()
                    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: bitmap)
                    meeting.draw(in: NSRect(origin: .zero, size: meeting.size))
                    NSGraphicsContext.restoreGraphicsState()
                }
                // The badge centre is red; the microphone takes the menu bar's label color.
                let badge = try XCTUnwrap(bitmap.colorAt(x: 33, y: 7)?.usingColorSpace(.deviceRGB))
                XCTAssertGreaterThan(badge.redComponent, 0.7); XCTAssertLessThan(badge.greenComponent, 0.5)
                var ink = 0
                for y in 0..<40 { for x in 0..<24 {
                    guard let color = bitmap.colorAt(x: x, y: y)?.usingColorSpace(.deviceRGB), color.alphaComponent > 0.5 else { continue }
                    ink += 1
                    XCTAssertEqual(color.redComponent, name == .darkAqua ? 1 : 0, accuracy: 0.25)
                } }
                XCTAssertGreaterThan(ink, 40, "The microphone is drawn in \(name)")
            }
        }
    }

    func testMeetingShortcutStartsUnassignedAndMustBeUnique() throws {
        let original = ShortcutBindings.defaults
        XCTAssertFalse(original.meeting.isEnabled)
        var legacy = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(original)) as? [String: Any])
        legacy.removeValue(forKey: "meeting")
        let restored = try JSONDecoder().decode(ShortcutBindings.self, from: JSONSerialization.data(withJSONObject: legacy))
        XCTAssertEqual(restored.dictate, original.dictate)
        XCTAssertEqual(restored.transform, original.transform)
        XCTAssertFalse(restored.meeting.isEnabled)
        var updated = restored
        updated.meeting = KeyboardShortcut(keyCode: 46, flags: [.option, .shift], keyLabel: "M")
        XCTAssertTrue(updated.isValid)
        XCTAssertEqual(try JSONDecoder().decode(ShortcutBindings.self, from: JSONEncoder().encode(updated)), updated)
        updated.meeting = original.dictate
        XCTAssertFalse(updated.isValid, "A meeting cannot share the dictation shortcut")
    }
}

/// A microphone or call channel fed directly by the test.
private final class FedRecorder: AudioRecording, @unchecked Sendable {
    private let lock = NSLock()
    private var onChunk: (@Sendable (Data) -> Void)?
    func start(keepAudio: Bool, onReady: @escaping @Sendable () -> Void, onChunk: @escaping @Sendable (Data) -> Void,
               onLevel: @escaping @Sendable (Float) -> Void, onError: @escaping @Sendable (DictationError) -> Void) async throws {
        lock.withLock { self.onChunk = onChunk }
        onReady()
    }
    func emit(_ data: Data) {
        let callback = lock.withLock { onChunk }
        var offset = 0
        while offset < data.count {
            callback?(data.subdata(in: offset..<min(offset + AudioConstants.chunkBytes, data.count)))
            offset += AudioConstants.chunkBytes
        }
    }
    func stop() async -> (Data, Int) { lock.withLock { onChunk = nil }; return (Data(), 0) }
}

@MainActor
final class MeetingFlowTests: XCTestCase {
    private func tone(_ millis: Int) -> Data {
        let count = AudioConstants.sampleRate * millis / 1_000
        var samples = [Int16](repeating: 0, count: count)
        for index in samples.indices {
            let phase: Double = 2 * Double.pi * 440 * Double(index) / Double(AudioConstants.sampleRate)
            samples[index] = Int16(0.3 * 32_767 * sin(phase))
        }
        return samples.withUnsafeBytes { Data($0) }
    }

    private func waitUntil(_ condition: () -> Bool) async throws {
        let deadline = Date().addingTimeInterval(6)
        while !condition() {
            guard Date() < deadline else { XCTFail("Timed out"); return }
            try await Task.sleep(for: .milliseconds(20))
        }
    }

    private func controller(store: MeetingStore, preferences: Preferences, microphone: FedRecorder,
                            transcribe: @escaping MeetingController.Transcriber) -> MeetingController {
        MeetingController(store: store, preferences: preferences, replacements: preferences.replacements,
                          makeMicrophone: { microphone }, makeCallAudio: { _ in FedRecorder() }, transcribe: transcribe,
                          loadKey: { "sk-test" }, microphoneAllowed: { true })
    }

    private func preferences() -> Preferences {
        let preferences = Preferences(defaults: UserDefaults(suiteName: "meeting-flow-\(UUID().uuidString)")!)
        preferences.interfaceLanguage = "en"
        return preferences
    }

    /// A retry message from the meeting that just ended must not greet the next one.
    func testFinishingNowClearsTheRecognitionIssue() async throws {
        let microphone = FedRecorder()
        let meetings = controller(store: MeetingStore(inMemory: true), preferences: preferences(), microphone: microphone,
                                  transcribe: { _, _, _, _ in throw DictationError.connection })
        try meetings.start(source: .system)
        try await waitUntil { meetings.microphone == .live }
        microphone.emit(tone(1_000) + Data(count: AudioConstants.sampleRate * 2))
        try await waitUntil { meetings.issue != nil }
        meetings.finish()
        try await waitUntil { meetings.phase == .finishing }
        meetings.finishNow()
        XCTAssertEqual(meetings.phase, .idle)
        XCTAssertNil(meetings.issue, "The finished meeting has no retry in progress")
    }

    /// An export failure stays visible after the meeting ends.
    func testExportFailureIsReportedAfterFinishing() async throws {
        let preferences = preferences()
        preferences.meetingExportFolder = FileManager.default.temporaryDirectory
            .appendingPathComponent("missing-\(UUID().uuidString)").path
        let meetings = controller(store: MeetingStore(inMemory: true), preferences: preferences, microphone: FedRecorder(),
                                  transcribe: { _, _, _, _ in "x" })
        try meetings.start(source: .system)
        meetings.finish()
        try await waitUntil { meetings.phase == .idle }
        XCTAssertEqual(meetings.issue, "Couldn't save the meeting to the export folder. Check the folder in Settings → Meetings.")
    }
}
