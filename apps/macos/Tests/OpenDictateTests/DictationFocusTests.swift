import AppKit
import ApplicationServices
import XCTest
import OpenDictateCore
@testable import OpenDictate

private actor FocusRecorder: AudioRecording {
    private(set) var stops = 0
    private(set) var starts = 0
    private(set) var keepsAudio = false
    private var pcm: Data
    private var onError: (@Sendable (DictationError) -> Void)?
    init(pcm: Data = Data(repeating: 0, count: 4_800)) { self.pcm = pcm }
    func start(keepAudio: Bool, onChunk: @escaping @Sendable (Data) -> Void,
               onLevel: @escaping @Sendable (Float) -> Void,
               onError: @escaping @Sendable (DictationError) -> Void) async throws {
        starts += 1; keepsAudio = keepAudio; self.onError = onError
    }
    func interrupt() { onError?(.connection) }
    func stop() async -> (Data, Int) {
        stops += 1
        let result = (pcm, pcm.count)
        pcm = Data()
        return result
    }
}

private actor ReplayTranscriber {
    struct Call: Sendable { let audio: Data; let context: TranscriptionContext; let timeout: Int }
    private(set) var calls: [Call] = []
    var failNext = false
    var holdNext = false
    private var pending: CheckedContinuation<String, Never>?
    func fail() { failNext = true }
    func hold() { holdNext = true }
    func release() { pending?.resume(returning: "late result"); pending = nil }
    func transcribe(_ audio: Data, context: TranscriptionContext, timeout: Int) async throws -> String {
        calls.append(Call(audio: audio, context: context, timeout: timeout))
        if failNext { failNext = false; throw DictationError.connection }
        if holdNext {
            holdNext = false
            // Deliberately ignores cancellation to exercise the session's late-result guard.
            return await withCheckedContinuation { pending = $0 }
        }
        return "synthetic transcript"
    }
}

private actor HeldPostProcessor {
    private(set) var settings: [PostProcessingSettings] = []
    private(set) var languages: [[String]] = []
    private var pending: CheckedContinuation<PostProcessingResult, Never>?
    func process(_ value: PostProcessingSettings, languages: [String] = []) async -> PostProcessingResult {
        settings.append(value); self.languages.append(languages)
        return await withCheckedContinuation { pending = $0 }
    }
    func release() { pending?.resume(returning: .init(text: "processed transcript")); pending = nil }
}

@MainActor private final class FocusEditor: TextAccessibility, TextPasting {
    var text: String
    var range: NSRange
    var focused = true
    var sends = 0
    private var staged = ""
    init(_ text: String) { self.text = text; range = NSRange(location: (text as NSString).length, length: 0) }
    func isFocused(_ element: AXUIElement, pid: pid_t) -> Bool { focused }
    func value(_ element: AXUIElement) -> String? { text }
    func selection(_ element: AXUIElement) -> NSRange? { range }
    func setSelection(_ range: NSRange, in element: AXUIElement) -> Bool { self.range = range; return true }
    func stage(_ text: String) -> Bool { staged = text; return true }
    func send(to pid: pid_t) -> Bool {
        sends += 1
        text = (text as NSString).replacingCharacters(in: range, with: staged)
        range = NSRange(location: range.location + (staged as NSString).length, length: 0)
        return true
    }
    func restore() {}
    func target() -> TextTarget {
        TextTarget(element: AXUIElementCreateApplication(getpid()), application: .current,
                   snapshot: EditableTextSnapshot(original: text, selection: range), accessibility: self, clipboard: self)
    }
}

@MainActor private final class HistoryRequestClock { var value = Duration.zero }

private actor PostLanguageCapture {
    private(set) var calls: [[String]] = []
    func process(source: String, languages: [String]) -> PostProcessingResult {
        calls.append(languages)
        return .init(text: source)
    }
}

final class DictationFocusTests: XCTestCase {
    private var defaultsDomains: [String] = []
    private func isolatedDefaults() -> UserDefaults {
        let domain = "com.opendictate.tests.retranscribe.\(UUID().uuidString)"
        defaultsDomains.append(domain)
        return UserDefaults(suiteName: domain)!
    }
    override func tearDown() {
        for domain in defaultsDomains { UserDefaults.standard.removePersistentDomain(forName: domain) }
        super.tearDown()
    }
    @MainActor private func waitFor(_ phase: AppModel.Phase, in model: AppModel) async {
        let deadline = ContinuousClock.now + .seconds(2)
        while model.phase != phase && ContinuousClock.now < deadline { await Task.yield() }
        XCTAssertEqual(model.phase, phase)
    }

    @MainActor func testPostProcessingUsesCapturedSettingsAndLateResultCannotDeliverAfterCancelOrFocusChange() async {
        for cancel in [true, false] {
            let first = FocusEditor("first "), second = FocusEditor("second "), processor = HeldPostProcessor()
            var active = first
            let model = AppModel(localOnly: true, defaults: isolatedDefaults(),
                captureTarget: { _, _ in active.target() }, makeRecorder: { FocusRecorder() },
                processTranscript: { _, _, settings, _, languages in await processor.process(settings, languages: languages) })
            model.preferences.speechLanguages = ["ru", "en"]
            model.preferences.postProcessing = .init(enabled: true, formatValues: true, formats: .systemDefaults(locale: Locale(identifier: "en_US")))
            model.toggleLocalRecording(mode: .accurate)
            await waitFor(.recording, in: model)
            model.preferences.speechLanguages = ["ja"]
            let originalDate = model.preferences.postProcessing.formats?[.date].example
            model.preferences.postProcessing.formats?[.date].example = "2026-03-05"
            model.stop()
            let deadline = ContinuousClock.now + .seconds(2)
            while await processor.settings.isEmpty && ContinuousClock.now < deadline { await Task.yield() }
            let captured = await processor.settings.first
            let capturedLanguages = await processor.languages.first
            XCTAssertEqual(capturedLanguages, ["en", "ru"])
            XCTAssertEqual(captured?.formats?[.date].example, originalDate)
            if cancel { model.cancel() }
            else { first.focused = false; active = second; model.tick() }
            await processor.release()
            await waitFor(.idle, in: model)
            XCTAssertEqual(first.sends, 0)
            XCTAssertEqual(second.sends, 0)
            XCTAssertEqual(model.lastTranscript, cancel ? "" : "processed transcript")
            model.shutdown()
        }
    }

    @MainActor func testHistoryMeasuresEachAwaitedRequestAndExcludesRecordingEvenWhenPostProcessingFails() async {
        for failed in [false, true] {
            let clock = HistoryRequestClock(), editor = FocusEditor("original ")
            let model = AppModel(localOnly: true, defaults: isolatedDefaults(),
                captureTarget: { _, _ in editor.target() }, makeRecorder: { FocusRecorder() },
                transcribeAudio: { _, _, _, _ in
                    await Task.yield()
                    await MainActor.run { clock.value += .milliseconds(1250) }
                    return "synthetic transcript"
                }, processTranscript: { source, _, _, selectedModel, _ in
                    XCTAssertEqual(selectedModel, "test-text-model")
                    await Task.yield()
                    await MainActor.run { clock.value += .milliseconds(450) }
                    return .init(text: source, failed: failed)
                }, requestClock: { clock.value })
            model.preferences.textProcessingModel = "test-text-model"
            model.preferences.saveHistory = true
            model.preferences.postProcessing = .init(enabled: true)
            model.toggleLocalRecording(mode: .accurate)
            await waitFor(.recording, in: model)
            model.preferences.textProcessingModel = TextProcessingModel.id
            clock.value += .seconds(40) // User recording time must not be included.
            model.preferences.mode = .live
            model.stop()
            await waitFor(.idle, in: model)
            XCTAssertEqual(model.history.entries.first?.mode, "accurate")
            XCTAssertEqual(model.history.entries.first?.transcriptionMillis, 1250)
            XCTAssertEqual(model.history.entries.first?.postProcessingMillis, 450)
            model.shutdown()
        }
    }

    @MainActor func testPostProcessingCapturesLanguagesForRecordingAndAccurateRetry() async {
        let editor = FocusEditor("original "), capture = PostLanguageCapture()
        let model = AppModel(localOnly: true, defaults: isolatedDefaults(),
            captureTarget: { _, _ in editor.target() }, makeRecorder: { FocusRecorder() }, accessibilityTrust: { true },
            processTranscript: { source, _, _, _, languages in await capture.process(source: source, languages: languages) })
        defer { model.shutdown() }
        model.preferences.postProcessing.enabled = true
        model.preferences.speechLanguages = ["ru"]
        model.toggleLocalRecording(mode: .accurate)
        await waitFor(.recording, in: model)
        model.preferences.speechLanguages = ["en"]
        model.stop()
        await waitFor(.idle, in: model)
        XCTAssertTrue(model.canRetranscribe)
        model.preferences.speechLanguages = ["de"]
        model.retranscribeLast()
        model.preferences.speechLanguages = ["ja"]
        await waitFor(.idle, in: model)
        let calls = await capture.calls
        XCTAssertEqual(calls, [["ru"], ["de"]])
    }

    @MainActor func testLiveSkipsPostProcessingEvenWhenEnabledAndModeChangesBeforeStop() async {
        let editor = FocusEditor("original "), processor = HeldPostProcessor()
        let model = AppModel(localOnly: true, defaults: isolatedDefaults(),
            captureTarget: { _, _ in editor.target() }, makeRecorder: { FocusRecorder() },
            processTranscript: { _, _, settings, _, _ in await processor.process(settings) })
        defer { model.shutdown() }
        model.preferences.postProcessing.enabled = true
        model.toggleLocalRecording(mode: .live)
        await waitFor(.recording, in: model)
        model.preferences.mode = .accurate
        model.stop()
        await waitFor(.idle, in: model)
        let count = await processor.settings.count
        await processor.release()
        XCTAssertEqual(count, 0)
        XCTAssertFalse(model.lastTranscript.isEmpty)
        XCTAssertEqual(model.history.entries.first?.mode, "live")
        XCTAssertNil(model.history.entries.first?.transcriptionMillis)
        XCTAssertNil(model.history.entries.first?.postProcessingMillis)
    }

    @MainActor func testVoiceEditSkipsPostProcessing() async {
        let editor = FocusEditor("original "), processor = HeldPostProcessor()
        let model = AppModel(localOnly: true, defaults: isolatedDefaults(), captureTarget: { _, _ in editor.target() },
            makeRecorder: { FocusRecorder() }, processTranscript: { _, _, settings, _, _ in await processor.process(settings) })
        defer { model.shutdown() }
        model.preferences.postProcessing.enabled = true
        model.toggleLocalRecording(transform: true)
        await waitFor(.recording, in: model)
        model.stop()
        await waitFor(.idle, in: model)
        let calls = await processor.settings.count
        XCTAssertEqual(calls, 0)
    }

    @MainActor func testSwitchingWindowsKeepsRecordingAndPastesIntoFieldAtStop() async {
        let first = FocusEditor("first "), second = FocusEditor("second ")
        var active = first
        var captures = 0
        let recorder = FocusRecorder()
        let model = AppModel(localOnly: true, captureTarget: { _, _ in
            captures += 1; return active.target()
        }, makeRecorder: { recorder })
        defer { model.shutdown() }
        model.toggleLocalRecording()
        await waitFor(.recording, in: model)
        first.focused = false; active = second
        model.tick()
        XCTAssertEqual(model.phase, .recording)
        XCTAssertFalse(model.detached)
        let stops = await recorder.stops
        XCTAssertEqual(stops, 0)
        model.stop()
        await waitFor(.idle, in: model)
        XCTAssertEqual(first.text, "first ")
        XCTAssertEqual(first.sends, 0)
        XCTAssertEqual(second.text, "second " + model.lastTranscript)
        XCTAssertEqual(second.sends, 1)
        XCTAssertEqual(captures, 2)
    }

    @MainActor func testReturningToOriginalWindowDoesNotPermanentlyDetachDelivery() async {
        let editor = FocusEditor("original ")
        let model = AppModel(localOnly: true, captureTarget: { _, _ in editor.target() }, makeRecorder: { FocusRecorder() })
        defer { model.shutdown() }
        model.toggleLocalRecording()
        await waitFor(.recording, in: model)
        editor.focused = false; model.tick()
        editor.focused = true; model.tick()
        XCTAssertEqual(model.phase, .recording)
        model.stop()
        await waitFor(.idle, in: model)
        XCTAssertEqual(editor.sends, 1)
    }

    @MainActor func testSwitchingDuringPreparationUsesFieldAtEarlyStop() async {
        let first = FocusEditor("first "), second = FocusEditor("second ")
        var active = first
        let model = AppModel(localOnly: true, captureTarget: { _, _ in active.target() }, makeRecorder: { FocusRecorder() })
        defer { model.shutdown() }
        model.toggleLocalRecording()
        XCTAssertEqual(model.phase, .preparing)
        first.focused = false; active = second; model.tick()
        XCTAssertEqual(model.phase, .preparing)
        model.stop()
        // A repeated finish action must not recapture a different destination.
        active = first; model.stop()
        await waitFor(.idle, in: model)
        XCTAssertEqual(first.sends, 0)
        XCTAssertEqual(second.sends, 1)
    }

    @MainActor func testSwitchingAfterStopNeverRetargetsPendingTranscript() async {
        let first = FocusEditor("first "), second = FocusEditor("second ")
        var active = first
        let model = AppModel(localOnly: true, captureTarget: { _, _ in active.target() }, makeRecorder: { FocusRecorder() })
        defer { model.shutdown() }
        model.toggleLocalRecording()
        await waitFor(.recording, in: model)
        model.stop()
        first.focused = false; active = second; model.tick()
        await waitFor(.idle, in: model)
        XCTAssertEqual(first.sends, 0)
        XCTAssertEqual(second.sends, 0)
        XCTAssertFalse(model.lastTranscript.isEmpty)
    }

    @MainActor func testIneligibleFieldAtStopKeepsResultWithoutFallbackPaste() async {
        for error in [DictationError.noField, .secureField, .excluded, .accessibility] {
            let editor = FocusEditor("original ")
            var denyCapture = false
            let model = AppModel(localOnly: true, captureTarget: { _, _ in
                if denyCapture { throw error }; return editor.target()
            }, makeRecorder: { FocusRecorder() })
            model.toggleLocalRecording()
            await waitFor(.recording, in: model)
            denyCapture = true; model.stop()
            await waitFor(.idle, in: model)
            XCTAssertEqual(editor.sends, 0)
            XCTAssertFalse(model.lastTranscript.isEmpty)
            model.shutdown()
        }
    }

    @MainActor func testCancellationAfterWindowSwitchNeverPastesOrKeepsTranscript() async {
        let first = FocusEditor("first "), second = FocusEditor("second ")
        var active = first
        let model = AppModel(localOnly: true, captureTarget: { _, _ in active.target() }, makeRecorder: { FocusRecorder() })
        defer { model.shutdown() }
        model.toggleLocalRecording()
        await waitFor(.recording, in: model)
        first.focused = false; active = second; model.tick()
        model.cancel()
        await waitFor(.idle, in: model)
        XCTAssertEqual(first.sends + second.sends, 0)
        XCTAssertTrue(model.lastTranscript.isEmpty)
    }

    @MainActor func testRevokingAccessibilityCancelsRecordingWithoutDelivery() async {
        let editor = FocusEditor("original ")
        var trusted = true
        let model = AppModel(localOnly: true, captureTarget: { _, _ in editor.target() },
                             makeRecorder: { FocusRecorder() }, accessibilityTrust: { trusted })
        defer { model.shutdown() }
        model.toggleLocalRecording()
        await waitFor(.recording, in: model)
        trusted = false; model.tick()
        await waitFor(.idle, in: model)
        XCTAssertEqual(editor.sends, 0)
        XCTAssertTrue(model.lastTranscript.isEmpty)
    }

    @MainActor func testVoiceEditingStillDetachesAndStopsWhenOriginalFieldLosesFocus() async {
        let editor = FocusEditor("original")
        let model = AppModel(localOnly: true, captureTarget: { _, _ in editor.target() }, makeRecorder: { FocusRecorder() })
        defer { model.shutdown() }
        model.toggleLocalRecording(transform: true)
        await waitFor(.recording, in: model)
        editor.focused = false; model.tick()
        XCTAssertTrue(model.detached)
        XCTAssertEqual(model.phase, .processing)
        // Cancel before completion; local checks never make a voice-edit provider request.
        model.cancel()
        await waitFor(.idle, in: model)
        XCTAssertEqual(editor.sends, 0)
    }

    @MainActor func testRetranscriptionUsesSameLiveAudioAndCurrentAccurateSettingsWithoutRecording() async {
        let first = FocusEditor("first "), second = FocusEditor("second ")
        var active = first
        let audio = Data(repeating: 7, count: 4_800), recorder = FocusRecorder(pcm: Data(repeating: 7, count: 4_800))
        let transcriber = ReplayTranscriber()
        let model = AppModel(localOnly: true, defaults: isolatedDefaults(), captureTarget: { _, _ in active.target() }, makeRecorder: { recorder },
                             accessibilityTrust: { true }, transcribeAudio: { pcm, _, context, timeout in
            try await transcriber.transcribe(pcm, context: context, timeout: timeout)
        })
        defer { model.shutdown() }
        XCTAssertFalse(model.canRetranscribe)
        model.retranscribeLast()
        XCTAssertEqual(model.phase, .idle)
        model.toggleLocalRecording(mode: .live)
        await waitFor(.recording, in: model)
        model.stop()
        await waitFor(.idle, in: model)
        XCTAssertTrue(model.canRetranscribe)
        let keepsAudio = await recorder.keepsAudio
        XCTAssertTrue(keepsAudio)
        first.focused = false; active = second
        model.preferences.accurateModel = "synthetic-accurate-model"
        model.preferences.speechLanguages = ["de"]
        model.preferences.dictionary = "SyntheticTerm"
        model.preferences.timeout = 120
        model.preferences.saveHistory = true
        XCTAssertTrue(model.replacements.save(source: "synthetic", replacement: "replaced"))
        model.retranscribeLast()
        XCTAssertEqual(model.phase, .processing)
        XCTAssertFalse(model.canRetranscribe)
        model.retranscribeLast() // Repeated menu actions cannot start overlapping requests.
        model.stop()
        await waitFor(.idle, in: model)
        let calls = await transcriber.calls, starts = await recorder.starts
        XCTAssertEqual(starts, 1)
        XCTAssertEqual(calls.count, 2)
        XCTAssertEqual(calls.last?.audio, audio)
        XCTAssertEqual(calls.last?.context.accurateModel, "synthetic-accurate-model")
        XCTAssertEqual(calls.last?.context.languages, ["de"])
        XCTAssertEqual(calls.last?.context.dictionary, ["SyntheticTerm"])
        XCTAssertEqual(calls.last?.timeout, 120)
        XCTAssertEqual(model.preferences.mode, .live)
        XCTAssertEqual(model.lastTranscript, "replaced transcript")
        XCTAssertEqual(first.sends, 1)
        XCTAssertEqual(second.sends, 1)
        XCTAssertEqual(second.text, "second replaced transcript")
        XCTAssertEqual(model.history.entries.first?.mode, "accurate")
        XCTAssertEqual(model.history.entries.first?.text, model.lastTranscript)
        XCTAssertNotNil(model.history.entries.first?.transcriptionMillis)
        XCTAssertNil(model.history.entries.first?.postProcessingMillis)
        XCTAssertTrue(model.canRetranscribe)
    }

    @MainActor func testFailedRecognitionKeepsAudioForRepeatedRetries() async {
        let editor = FocusEditor("original "), transcriber = ReplayTranscriber()
        let model = AppModel(localOnly: true, defaults: isolatedDefaults(), captureTarget: { _, _ in editor.target() }, makeRecorder: { FocusRecorder() },
                             accessibilityTrust: { true }, transcribeAudio: { pcm, _, context, timeout in
            try await transcriber.transcribe(pcm, context: context, timeout: timeout)
        })
        defer { model.shutdown() }
        await transcriber.fail()
        model.toggleLocalRecording()
        await waitFor(.recording, in: model)
        model.stop()
        await waitFor(.idle, in: model)
        XCTAssertTrue(model.canRetranscribe)
        XCTAssertTrue(model.lastTranscript.isEmpty)
        await transcriber.fail()
        model.retranscribeLast()
        await waitFor(.idle, in: model)
        XCTAssertTrue(model.canRetranscribe)
        XCTAssertEqual(editor.sends, 0)
        model.retranscribeLast()
        await waitFor(.idle, in: model)
        XCTAssertFalse(model.lastTranscript.isEmpty)
        XCTAssertEqual(editor.sends, 1)
    }

    @MainActor func testRetryWithoutEligibleFieldKeepsTextForCopyAndNeverPastes() async {
        for error in [DictationError.noField, .secureField, .excluded, .accessibility] {
            let editor = FocusEditor("original ")
            var denyCapture = false
            let model = AppModel(localOnly: true, defaults: isolatedDefaults(), captureTarget: { _, _ in
                if denyCapture { throw error }; return editor.target()
            }, makeRecorder: { FocusRecorder() }, accessibilityTrust: { true })
            model.toggleLocalRecording()
            await waitFor(.recording, in: model)
            model.stop()
            await waitFor(.idle, in: model)
            denyCapture = true
            model.retranscribeLast()
            await waitFor(.idle, in: model)
            XCTAssertEqual(editor.sends, 1)
            XCTAssertFalse(model.lastTranscript.isEmpty)
            XCTAssertTrue(model.canRetranscribe)
            model.shutdown()
            XCTAssertFalse(model.canRetranscribe)
        }
    }

    @MainActor func testRetryFocusChangeAndCancellationRejectLateResultButKeepAudio() async {
        for cancel in [false, true] {
            let first = FocusEditor("first "), second = FocusEditor("second ")
            var active = first
            let transcriber = ReplayTranscriber()
            let model = AppModel(localOnly: true, defaults: isolatedDefaults(), captureTarget: { _, _ in active.target() }, makeRecorder: { FocusRecorder() },
                                 accessibilityTrust: { true }, transcribeAudio: { pcm, _, context, timeout in
                try await transcriber.transcribe(pcm, context: context, timeout: timeout)
            })
            model.toggleLocalRecording()
            await waitFor(.recording, in: model)
            model.stop()
            await waitFor(.idle, in: model)
            let previous = model.lastTranscript
            await transcriber.hold()
            model.retranscribeLast()
            let deadline = ContinuousClock.now + .seconds(2)
            while await transcriber.calls.count < 2 && ContinuousClock.now < deadline { await Task.yield() }
            if cancel { model.cancel() }
            else { first.focused = false; active = second; model.tick() }
            await transcriber.release()
            await waitFor(.idle, in: model)
            XCTAssertEqual(first.sends, 1)
            XCTAssertEqual(second.sends, 0)
            XCTAssertEqual(model.lastTranscript, cancel ? previous : "late result")
            XCTAssertTrue(model.canRetranscribe)
            model.shutdown()
        }
    }

    @MainActor func testNewDictationReplacesAudioAndCancelledOrShortRecordingCannotBeRetried() async {
        let editor = FocusEditor("original "), transcriber = ReplayTranscriber()
        var nextAudio = Data(repeating: 1, count: 4_800)
        let model = AppModel(localOnly: true, defaults: isolatedDefaults(), captureTarget: { _, _ in editor.target() },
                             makeRecorder: { FocusRecorder(pcm: nextAudio) }, accessibilityTrust: { true },
                             transcribeAudio: { pcm, _, context, timeout in
            try await transcriber.transcribe(pcm, context: context, timeout: timeout)
        })
        defer { model.shutdown() }
        for marker in [UInt8(1), 2] {
            nextAudio = Data(repeating: marker, count: 4_800)
            model.toggleLocalRecording()
            XCTAssertFalse(model.canRetranscribe)
            await waitFor(.recording, in: model)
            model.stop()
            await waitFor(.idle, in: model)
            model.retranscribeLast()
            await waitFor(.idle, in: model)
            let call = await transcriber.calls.last
            XCTAssertEqual(call?.audio, nextAudio)
        }
        model.toggleLocalRecording()
        await waitFor(.recording, in: model)
        model.cancel()
        await waitFor(.idle, in: model)
        XCTAssertFalse(model.canRetranscribe)
        nextAudio = Data(repeating: 3, count: 100)
        model.toggleLocalRecording()
        await waitFor(.recording, in: model)
        model.stop()
        await waitFor(.idle, in: model)
        XCTAssertFalse(model.canRetranscribe)
    }

    @MainActor func testRecordingFailurePreservesCapturedAudioButVoiceEditDoesNotReplaceIt() async {
        let editor = FocusEditor("original "), transcriber = ReplayTranscriber()
        let recorder = FocusRecorder(pcm: Data(repeating: 5, count: 4_800))
        var next: any AudioRecording = recorder
        var notifications: [String] = []
        let model = AppModel(localOnly: true, defaults: isolatedDefaults(), captureTarget: { _, _ in editor.target() },
                             makeRecorder: { next }, accessibilityTrust: { true }, transcribeAudio: { pcm, _, context, timeout in
            try await transcriber.transcribe(pcm, context: context, timeout: timeout)
        }, notifyError: { notifications.append($0) })
        defer { model.shutdown() }
        model.toggleLocalRecording(mode: .live)
        await waitFor(.recording, in: model)
        await recorder.interrupt()
        await waitFor(.idle, in: model)
        XCTAssertTrue(model.canRetranscribe)
        XCTAssertEqual(notifications, [DictationError.connection.message(russian: model.preferences.isRussian)])
        next = FocusRecorder(pcm: Data(repeating: 9, count: 4_800))
        model.toggleLocalRecording(transform: true)
        await waitFor(.recording, in: model)
        model.cancel()
        await waitFor(.idle, in: model)
        XCTAssertTrue(model.canRetranscribe)
        model.retranscribeLast()
        await waitFor(.idle, in: model)
        let audio = await transcriber.calls.last?.audio
        XCTAssertEqual(audio, Data(repeating: 5, count: 4_800))
        XCTAssertEqual(notifications.count, 1, "Cancellation and successful retry must not notify.")
    }

    @MainActor func testCancellingOriginalProcessingDiscardsAudioAndLateTranscript() async {
        let editor = FocusEditor("original "), transcriber = ReplayTranscriber()
        let model = AppModel(localOnly: true, defaults: isolatedDefaults(), captureTarget: { _, _ in editor.target() },
                             makeRecorder: { FocusRecorder() }, accessibilityTrust: { true }, transcribeAudio: { pcm, _, context, timeout in
            try await transcriber.transcribe(pcm, context: context, timeout: timeout)
        })
        defer { model.shutdown() }
        await transcriber.hold()
        model.toggleLocalRecording()
        await waitFor(.recording, in: model)
        model.stop()
        let deadline = ContinuousClock.now + .seconds(2)
        while await transcriber.calls.isEmpty && ContinuousClock.now < deadline { await Task.yield() }
        model.cancel()
        await transcriber.release()
        await waitFor(.idle, in: model)
        XCTAssertFalse(model.canRetranscribe)
        XCTAssertTrue(model.lastTranscript.isEmpty)
        XCTAssertEqual(editor.sends, 0)
    }

}
