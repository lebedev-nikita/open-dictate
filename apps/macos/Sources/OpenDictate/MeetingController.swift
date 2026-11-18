import AppKit
import AVFoundation
import OpenDictateCore

/// Recorded meeting time shared by both channels. Pauses are excluded. Thread-safe.
final class MeetingClock: @unchecked Sendable {
    private let lock = NSLock()
    private let now: @Sendable () -> ContinuousClock.Instant
    private var accumulated = Duration.zero
    private var resumedAt: ContinuousClock.Instant?

    init(now: @escaping @Sendable () -> ContinuousClock.Instant = { ContinuousClock.now }) { self.now = now }

    /// Starts the next meeting at zero.
    func reset() { lock.withLock { accumulated = .zero; resumedAt = nil } }
    func run() { lock.withLock { if resumedAt == nil { resumedAt = now() } } }
    func pause() {
        lock.withLock {
            if let resumedAt { accumulated += now() - resumedAt }
            resumedAt = nil
        }
    }
    var millis: Int {
        lock.withLock {
            let total = accumulated + (resumedAt.map { now() - $0 } ?? .zero)
            return Int(total.components.seconds * 1_000 + total.components.attoseconds / 1_000_000_000_000_000)
        }
    }
}

/// One capture channel: its own recorder and segmenter, stamped on the shared clock.
/// Audio callbacks arrive on the recorder's worker; the lock guards channel state.
final class MeetingChannel: @unchecked Sendable {
    struct Captured: Sendable {
        let run: Int
        let speaker: String
        let startMillis: Int
        let durationMillis: Int
        let pcm: Data
    }
    let speaker: String
    let recorder: any AudioRecording
    private let lock = NSLock()
    private let clock: MeetingClock
    private var segmenter = UtteranceSegmenter(configuration: MeetingChannel.segmentation)
    private var run = 0
    private var baseMillis: Int?
    private var samples = 0
    private var lastSoundMillis: Int?

    /// Measured on Russian and English speech: 700 ms pauses gave the lowest word error of
    /// 300–1000 ms, with median utterances of about 7 seconds.
    static let segmentation = UtteranceSegmenter.Configuration()

    init(speaker: String, recorder: any AudioRecording, clock: MeetingClock) {
        self.speaker = speaker; self.recorder = recorder; self.clock = clock
    }

    /// Starts a new capture run; the next chunk sets its place on the meeting clock. Returns the
    /// run and any utterance the previous run had not yet emitted.
    func begin() -> (run: Int, leftover: [Captured]) {
        lock.withLock {
            let leftover = segmenter.finish().map(captured)
            run += 1; baseMillis = nil; samples = 0
            segmenter = UtteranceSegmenter(configuration: Self.segmentation)
            return (run, leftover)
        }
    }

    func receive(_ chunk: Data, run expected: Int) -> [Captured] {
        lock.withLock {
            guard run == expected else { return [] }
            if baseMillis == nil {
                baseMillis = max(0, clock.millis - chunk.count / 2 * 1_000 / AudioConstants.sampleRate)
            }
            samples += chunk.count / 2
            if chunk.contains(where: { $0 != 0 }) { lastSoundMillis = clock.millis }
            return segmenter.append(chunk).map(captured)
        }
    }

    /// Ends the run, emitting the utterance in progress.
    func end(run expected: Int) -> [Captured] {
        lock.withLock {
            guard run == expected else { return [] }
            run += 1
            return segmenter.finish().map(captured)
        }
    }

    /// Discards audio of the current run without emitting it.
    func discard() { lock.withLock { run += 1; segmenter.reset() } }

    /// Meeting time up to which this channel has decided where its utterances are.
    var coveredMillis: Int? {
        lock.withLock {
            guard let baseMillis else { return nil }
            let processed = baseMillis + samples * 1_000 / AudioConstants.sampleRate
            return segmenter.isInUtterance ? baseMillis : processed
        }
    }

    var hasAudio: Bool { lock.withLock { baseMillis != nil } }
    var lastSound: Int? { lock.withLock { lastSoundMillis } }

    private func captured(_ utterance: UtteranceSegmenter.Utterance) -> Captured {
        let base = baseMillis ?? 0
        return Captured(run: run, speaker: speaker,
                        startMillis: base + utterance.speechStartSample * 1_000 / AudioConstants.sampleRate,
                        durationMillis: utterance.durationMillis, pcm: utterance.pcm)
    }
}

@MainActor
final class MeetingController: ObservableObject {
    typealias Transcriber = @Sendable (Data, String, TranscriptionContext, Int) async throws -> String
    enum Phase: Equatable { case idle, recording, paused, finishing }
    enum PauseReason: Equatable { case user, sleep, backlog }
    enum ChannelState: Equatable { case off, starting, live, failed }

    @Published private(set) var phase = Phase.idle
    @Published private(set) var pauseReason: PauseReason?
    @Published private(set) var meeting: Meeting?
    @Published private(set) var elapsedMillis = 0
    @Published private(set) var microphone = ChannelState.off
    @Published private(set) var callAudio = ChannelState.off
    /// No sound has arrived from the call yet; macOS delivers silence until System Audio Recording is allowed.
    @Published private(set) var callAudioSilent = false
    @Published private(set) var issue: String?
    @Published private(set) var lastFinishedID: UUID?
    let levels = MeetingLevels()
    var onStateChange: (() -> Void)?
    /// Debug local checks: synthetic recognition, no Keychain or OpenAI.
    var localOnly = false

    let store: MeetingStore
    private let preferences: Preferences
    private let replacements: ReplacementStore
    private let makeMicrophone: () -> any AudioRecording
    private let makeCallAudio: (MeetingAudioSource) -> any AudioRecording
    private let transcribe: Transcriber
    private let loadKey: () throws -> String?
    private let microphoneAllowed: () -> Bool
    private let notify: (String) -> Void
    private let clock: MeetingClock
    private let pendingLimit: Int

    private struct Settings {
        let key: String
        let context: TranscriptionContext
        let timeout: Int
        let replacements: WordReplacementEngine
        let russian: Bool
    }
    private struct Job {
        let utterance: UUID
        let pcm: Data
        var attempt = 0
    }
    private var settings: Settings?
    private var channels = [MeetingChannel]()
    private var runs = [String: Int]()
    /// Channels whose stopped run has not been flushed yet.
    private var stopping = Set<String>()
    private var queue = [Job]()
    private var running = [UUID: Task<Void, Never>]()
    private var retries = [UUID: Task<Void, Never>]()
    private var pendingBytes = 0
    /// Recognized microphone text waiting for the overlapping call text, for echo removal.
    private var awaitingEcho = [UUID: (text: String, since: Int)]()
    private var dictations = [(start: Int, end: Int?)]()
    /// Whether dictation is running, also between meetings.
    private var dictating = false
    private var timer: Timer?
    private var dirty = false
    private var lastSave = Date.distantPast
    private var sleepObserver: NSObjectProtocol?

    init(store: MeetingStore, preferences: Preferences, replacements: ReplacementStore,
         makeMicrophone: @escaping () -> any AudioRecording = { AudioRecorder(limitBytes: nil) },
         makeCallAudio: @escaping (MeetingAudioSource) -> any AudioRecording = MeetingController.callRecorder,
         transcribe: Transcriber? = nil,
         loadKey: @escaping () throws -> String? = { try APIKeyStore.load() },
         microphoneAllowed: @escaping () -> Bool = { AVCaptureDevice.authorizationStatus(for: .audio) == .authorized },
         notify: @escaping (String) -> Void = { _ in },
         clock: MeetingClock = MeetingClock(),
         pendingLimit: Int = MeetingLimits.maximumPendingAudioBytes) {
        self.pendingLimit = pendingLimit
        self.store = store; self.preferences = preferences; self.replacements = replacements
        self.makeMicrophone = makeMicrophone; self.makeCallAudio = makeCallAudio
        let client = OpenAIClient()
        self.transcribe = transcribe ?? { try await client.transcribe(pcm: $0, key: $1, context: $2, timeout: $3) }
        self.loadKey = loadKey; self.microphoneAllowed = microphoneAllowed; self.notify = notify; self.clock = clock
        sleepObserver = NSWorkspace.shared.notificationCenter.addObserver(forName: NSWorkspace.willSleepNotification,
                                                                         object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { if self?.phase == .recording { self?.pause(.sleep) } }
        }
    }

    nonisolated static func callRecorder(_ source: MeetingAudioSource) -> any AudioRecording {
        AudioRecorder(makeEngine: { ProcessTapCaptureEngine(source: source, onConfigurationChange: $0) },
                      inputDevice: { nil }, canPrepare: { _ in false }, limitBytes: nil)
    }

    var isActive: Bool { phase != .idle }
    var waitingCount: Int { meeting?.pendingCount ?? 0 }
    private func t(_ en: String, _ ru: String) -> String { preferences.t(en, ru) }

    enum StartError: Error { case missingKey, microphone }

    /// Starts recording the microphone as "Me" and the chosen source as "Other speaker".
    func start(source: MeetingAudioSource) throws {
        guard phase == .idle else { return }
        guard let key = try loadKey(), !key.isEmpty else { throw StartError.missingKey }
        guard microphoneAllowed() else { throw StartError.microphone }
        settings = Settings(key: key, context: preferences.context, timeout: preferences.timeout,
                            replacements: replacements.engine(), russian: preferences.isRussian)
        let started = Meeting(startedAt: Date(), sourceName: source.displayName,
                              sourceBundleID: source.bundleID, transcriptionModel: preferences.accurateModel)
        meeting = started; store.save(started); lastSave = Date()
        issue = nil; callAudioSilent = false; pauseReason = nil; elapsedMillis = 0
        queue.removeAll(); pendingBytes = 0; awaitingEcho.removeAll(); dictations.removeAll()
        channels = [MeetingChannel(speaker: Meeting.meID, recorder: makeMicrophone(), clock: clock),
                    MeetingChannel(speaker: Meeting.otherID, recorder: makeCallAudio(source), clock: clock)]
        phase = .recording
        clock.reset(); clock.run()
        // The app reports dictation only when it changes, which may be before this meeting.
        if dictating { dictations.append((0, nil)) }
        startChannels()
        timer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.tick() }
        }
        onStateChange?()
    }

    func pause(_ reason: PauseReason = .user) {
        guard phase == .recording else { return }
        clock.pause()
        phase = .paused; pauseReason = reason
        stopChannels(flush: true)
        if reason == .backlog {
            notify(t("Meeting paused: recognition is behind. Check your connection, then resume.",
                     "Встреча на паузе: распознавание отстаёт. Проверьте соединение и продолжите."))
        }
        onStateChange?()
    }

    func resume() {
        guard phase == .paused else { return }
        guard pendingBytes < pendingLimit else { pauseReason = .backlog; return }
        phase = .recording; pauseReason = nil
        clock.run()
        startChannels()
        onStateChange?()
    }

    /// Stops capture and waits for queued utterances before saving the transcript.
    func finish() {
        guard phase == .recording || phase == .paused else { return }
        if phase == .recording { clock.pause(); stopChannels(flush: true) }
        phase = .finishing; pauseReason = nil
        onStateChange?()
        completeIfDone()
    }

    /// Ends a finishing meeting now; utterances still waiting are marked as not recognized.
    func finishNow() {
        guard phase == .finishing else { return }
        cancelRecognition()
        meeting?.failPending()
        complete()
    }

    /// Discards the meeting: capture stops and its transcript is deleted.
    func cancel() {
        guard isActive, let id = meeting?.id else { return }
        if phase == .recording { clock.pause() }
        stopChannels(flush: false)
        cancelRecognition()
        store.delete(id)
        meeting = nil
        reset()
        onStateChange?()
    }

    /// Ordinary dictation and voice editing share the microphone during a meeting. Their speech
    /// is addressed to a text field, so it stays out of the meeting transcript.
    func noteDictation(active: Bool) {
        dictating = active
        guard phase == .recording || phase == .paused else { return }
        if active { if dictations.last?.end != nil || dictations.isEmpty { dictations.append((clock.millis, nil)) } }
        else if let last = dictations.indices.last, dictations[last].end == nil { dictations[last].end = clock.millis }
    }

    // MARK: Capture

    private func startChannels() {
        for channel in channels {
            let (run, leftover) = channel.begin()
            stopping.remove(channel.speaker)
            enqueue(leftover)
            runs[channel.speaker] = run
            setState(.starting, for: channel)
            let speaker = channel.speaker
            Task { [weak self] in
                do {
                    try await channel.recorder.start(keepAudio: false, onReady: {}, onChunk: { [weak self] chunk in
                        let captured = channel.receive(chunk, run: run)
                        guard !captured.isEmpty else { return }
                        Task { @MainActor [weak self] in self?.enqueue(captured) }
                    }, onLevel: { [weak self] level in
                        Task { @MainActor [weak self] in self?.levels.set(level, speaker: speaker) }
                    }, onError: { [weak self] _ in
                        Task { @MainActor [weak self] in
                            guard let self, self.runs[speaker] == run else { return }
                            self.setState(.failed, for: channel)
                        }
                    })
                    // A pause or finish while macOS was still starting this channel already queued its stop.
                    guard let self, self.runs[speaker] == run else { return }
                    self.setState(.live, for: channel)
                } catch {
                    guard let self, self.runs[speaker] == run else { return }
                    self.setState(.failed, for: channel)
                }
            }
        }
    }

    private func stopChannels(flush: Bool) {
        for channel in channels {
            let run = runs[channel.speaker] ?? 0
            runs[channel.speaker] = nil
            setState(.off, for: channel)
            levels.set(0, speaker: channel.speaker)
            if !flush { channel.discard(); Task { _ = await channel.recorder.stop() }; continue }
            stopping.insert(channel.speaker)
            let flushRun: @MainActor () -> Void = { [weak self] in
                guard let self, self.stopping.contains(channel.speaker), self.runs[channel.speaker] == nil else { return }
                self.stopping.remove(channel.speaker)
                self.enqueue(channel.end(run: run))
                self.resolveEcho(); self.completeIfDone()
            }
            Task { @MainActor in _ = await channel.recorder.stop(); flushRun() }
            // Stopping can wait while macOS asks for System Audio Recording permission;
            // the meeting flushes what it has instead of waiting for it.
            Task { @MainActor in try? await Task.sleep(for: .seconds(3)); flushRun() }
        }
    }

    private func setState(_ state: ChannelState, for channel: MeetingChannel) {
        if channel.speaker == Meeting.meID { microphone = state } else { callAudio = state }
    }

    private func tick() {
        elapsedMillis = clock.millis
        if phase == .recording, callAudio == .live || callAudio == .starting {
            let call = channels.first { $0.speaker == Meeting.otherID }
            // macOS delivers silence (or nothing) until System Audio Recording is allowed.
            callAudioSilent = elapsedMillis > 15_000 && (call?.lastSound == nil)
        } else if phase != .recording { callAudioSilent = false }
        resolveEcho()
        if dirty && Date().timeIntervalSince(lastSave) >= 2, let meeting {
            var snapshot = meeting; snapshot.durationMillis = elapsedMillis
            store.save(snapshot); dirty = false; lastSave = Date()
        }
    }

    // MARK: Recognition

    private func enqueue(_ captured: [MeetingChannel.Captured]) {
        guard meeting != nil, phase != .idle else { return }
        for item in captured {
            if item.speaker == Meeting.meID && overlapsDictation(item) { continue }
            let utterance = MeetingUtterance(speaker: item.speaker, startMillis: item.startMillis,
                                             durationMillis: item.durationMillis)
            meeting?.insert(utterance)
            queue.append(Job(utterance: utterance.id, pcm: item.pcm))
            pendingBytes += item.pcm.count
        }
        dirty = true
        pump()
        if phase == .recording && pendingBytes >= pendingLimit { pause(.backlog) }
    }

    private func overlapsDictation(_ item: MeetingChannel.Captured) -> Bool {
        let end = item.startMillis + item.durationMillis
        let overlap = dictations.reduce(0) { total, interval in
            total + max(0, min(end, interval.end ?? .max) - max(item.startMillis, interval.start))
        }
        return overlap * 2 >= max(1, item.durationMillis)
    }

    private func pump() {
        guard let settings else { return }
        while running.count < MeetingLimits.maximumConcurrentRequests, !queue.isEmpty {
            let job = queue.removeFirst()
            let transcribe = self.transcribe
            running[job.utterance] = Task { [weak self] in
                do {
                    let text = try await transcribe(job.pcm, settings.key, settings.context, settings.timeout)
                    self?.recognized(job, text: text)
                } catch is CancellationError {
                } catch {
                    self?.failed(job, error: error)
                }
            }
        }
    }

    private func recognized(_ job: Job, text: String) {
        guard running.removeValue(forKey: job.utterance) != nil, let settings else { return }
        pendingBytes -= job.pcm.count
        issue = nil
        let result = settings.replacements.apply(TranscriptFormatter.format(text))
        let speaker = meeting?.utterances.first { $0.id == job.utterance }?.speaker
        if result.isEmpty {
            meeting?.remove(job.utterance)
        } else if speaker == Meeting.meID && channels.contains(where: { $0.speaker == Meeting.otherID && $0.hasAudio }) {
            awaitingEcho[job.utterance] = (result, clock.millis)
        } else {
            meeting?.update(job.utterance) { $0.text = result; $0.status = .recognized }
        }
        dirty = true
        resolveEcho()
        pump()
        completeIfDone()
    }

    private func failed(_ job: Job, error: Error) {
        guard running.removeValue(forKey: job.utterance) != nil else { return }
        let status: Int? = if case DictationError.api(let code) = error { code } else { nil }
        let message = (error as? DictationError)?.message(russian: preferences.isRussian)
            ?? t("Recognition failed. Retrying…", "Распознавание не удалось. Повтор…")
        if MeetingLimits.isRetryable(status: status) && phase != .idle {
            issue = message
            var next = job; next.attempt += 1
            let delay = MeetingLimits.retryDelaySeconds(attempt: next.attempt)
            retries[job.utterance] = Task { [weak self] in
                try? await Task.sleep(for: .seconds(delay))
                guard !Task.isCancelled, let self, self.retries.removeValue(forKey: job.utterance) != nil else { return }
                self.queue.insert(next, at: 0)
                self.pump()
            }
        } else {
            // The same audio would fail again; keep the timestamp and mark the gap.
            pendingBytes -= job.pcm.count
            meeting?.update(job.utterance) { $0.status = .failed }
            dirty = true
            completeIfDone()
        }
        pump()
    }

    /// Microphone text is final once the call text around it is known.
    private func resolveEcho() {
        guard !awaitingEcho.isEmpty, let meeting else { return }
        let call = channels.first { $0.speaker == Meeting.otherID }
        let callCapturing = phase == .recording && callAudio == .live
        let covered = call?.coveredMillis ?? .max
        let callSpans = meeting.utterances.filter { $0.speaker == Meeting.otherID }
        for (id, value) in awaitingEcho {
            guard let utterance = meeting.utterances.first(where: { $0.id == id }) else { awaitingEcho[id] = nil; continue }
            let end = utterance.startMillis + utterance.durationMillis
            let window = (utterance.startMillis - MeetingEcho.windowMillis)...(end + MeetingEcho.windowMillis)
            let nearby = callSpans.filter { $0.startMillis <= window.upperBound && $0.startMillis + $0.durationMillis >= window.lowerBound }
            let callDecided = !callCapturing || covered >= window.upperBound || clock.millis - value.since > 20_000
            guard callDecided, !nearby.contains(where: { $0.status == .pending }) || clock.millis - value.since > 20_000 else { continue }
            awaitingEcho[id] = nil
            let span = MeetingEcho.Span(startMillis: utterance.startMillis, endMillis: end, text: value.text)
            let heard = nearby.filter { $0.status == .recognized }.map {
                MeetingEcho.Span(startMillis: $0.startMillis, endMillis: $0.startMillis + $0.durationMillis, text: $0.text)
            }
            if let text = MeetingEcho.removingEcho(from: span, call: heard) {
                self.meeting?.update(id) { $0.text = text; $0.status = .recognized }
            } else {
                self.meeting?.remove(id)
            }
            dirty = true
        }
        completeIfDone()
    }

    private func cancelRecognition() {
        for task in running.values { task.cancel() }
        for task in retries.values { task.cancel() }
        running.removeAll(); retries.removeAll(); queue.removeAll(); awaitingEcho.removeAll(); pendingBytes = 0
    }

    private func completeIfDone() {
        guard phase == .finishing, stopping.isEmpty, queue.isEmpty, running.isEmpty, retries.isEmpty else { return }
        if !awaitingEcho.isEmpty { resolveEcho(); if !awaitingEcho.isEmpty { return } }
        complete()
    }

    private func complete() {
        guard var finished = meeting else { reset(); return }
        finished.durationMillis = clock.millis
        finished.state = .finished
        finished.failPending()
        store.save(finished)
        lastFinishedID = finished.id
        meeting = finished
        export(finished)
        reset()
        onStateChange?()
    }

    private func reset() {
        timer?.invalidate(); timer = nil
        channels.removeAll(); runs.removeAll(); stopping.removeAll()
        settings = nil; phase = .idle; pauseReason = nil
        microphone = .off; callAudio = .off; callAudioSilent = false
        levels.set(0, speaker: Meeting.meID); levels.set(0, speaker: Meeting.otherID)
        dirty = false
    }

    /// Renames a speaker of the meeting being recorded; saved meetings go through the store.
    func rename(speaker id: String, to name: String) {
        meeting?.rename(speaker: id, to: name)
        dirty = true
    }

    // MARK: Export

    func markdown(_ meeting: Meeting) -> String { MeetingFormat.markdown(meeting, russian: preferences.isRussian) }

    /// The newest meeting, including one being recorded.
    var latestMeeting: Meeting? { meeting.flatMap { isActive ? $0 : nil } ?? store.meetings.first }

    func copyLatest(to pasteboard: NSPasteboard = .general) -> Bool {
        guard let latest = latestMeeting else { return false }
        pasteboard.clearContents()
        return pasteboard.setString(markdown(latest), forType: .string)
    }

    /// Writes a finished meeting to the auto-export folder, never replacing an existing file.
    private func export(_ meeting: Meeting) {
        guard let folder = preferences.meetingExportFolder else { return }
        do {
            _ = try Self.write(markdown(meeting), named: MeetingFormat.fileName(meeting, russian: preferences.isRussian),
                               in: URL(fileURLWithPath: folder, isDirectory: true))
        } catch {
            let message = t("Couldn't save the meeting to the export folder. Check the folder in Settings → Meetings.",
                            "Не удалось сохранить встречу в папку экспорта. Проверьте папку в Настройках → Встречи.")
            issue = message; notify(message)
        }
    }

    nonisolated static func write(_ text: String, named name: String, in folder: URL) throws -> URL {
        let base = (name as NSString).deletingPathExtension
        var url = folder.appendingPathComponent(name)
        var index = 2
        while FileManager.default.fileExists(atPath: url.path) {
            url = folder.appendingPathComponent("\(base) \(index).md"); index += 1
        }
        try Data(text.utf8).write(to: url, options: [.withoutOverwriting])
        return url
    }

    /// Quitting during a meeting keeps the text recognized so far; waiting audio is lost.
    func abandonForQuit() {
        guard isActive, var current = meeting else { return }
        clock.pause()
        stopChannels(flush: false)
        cancelRecognition()
        current.durationMillis = clock.millis
        current.state = .interrupted
        current.failPending()
        store.save(current)
        meeting = nil
        reset()
    }

    #if DEBUG
    /// Shows a meeting state for native UI review without capturing audio or calling OpenAI.
    func preview(_ meeting: Meeting?, phase: Phase, elapsedMillis: Int = 0, pauseReason: PauseReason? = nil,
                 microphone: ChannelState = .live, callAudio: ChannelState = .live, callAudioSilent: Bool = false,
                 issue: String? = nil, levels: (Float, Float) = (0, 0)) {
        self.meeting = meeting; self.phase = phase; self.elapsedMillis = elapsedMillis; self.pauseReason = pauseReason
        self.microphone = microphone; self.callAudio = callAudio; self.callAudioSilent = callAudioSilent; self.issue = issue
        self.levels.set(levels.0, speaker: Meeting.meID); self.levels.set(levels.1, speaker: Meeting.otherID)
    }
    #endif

    func shutdown() {
        if let sleepObserver { NSWorkspace.shared.notificationCenter.removeObserver(sleepObserver) }
        timer?.invalidate()
    }
}

/// Channel input levels for the meeting window's meters, published apart from the transcript.
@MainActor
final class MeetingLevels: ObservableObject {
    @Published private(set) var me: Float = 0
    @Published private(set) var other: Float = 0
    func set(_ level: Float, speaker: String) {
        if speaker == Meeting.meID { if me != level { me = level } } else if other != level { other = level }
    }
}

extension MeetingAudioSource {
    var displayName: String? { if case .application(_, let name) = self { return name }; return nil }
}
