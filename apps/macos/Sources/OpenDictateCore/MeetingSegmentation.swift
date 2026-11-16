import Foundation

/// Cuts one channel of mono PCM16 at `AudioConstants.sampleRate` into utterances at pauses.
/// Silence is never emitted, so it is neither uploaded nor billed. Memory is bounded by the
/// longest utterance plus a short pre-roll.
public struct UtteranceSegmenter: Sendable {
    public struct Configuration: Sendable, Equatable {
        /// Analysis frame. 20 ms resolves short gaps between words without per-sample work.
        public var frameMillis = 20
        /// A frame is speech when it is this far above the tracked noise floor.
        public var speechMarginDecibels: Float = 12
        /// Frames below this level are never speech, however quiet the channel is.
        public var minimumSpeechDecibels: Float = -50
        /// Silence of this length ends an utterance.
        public var pauseMillis = 700
        /// Utterances with less detected speech are coughs, clicks or keyboard noise.
        public var minimumSpeechMillis = 300
        /// Audio kept before the first speech frame, so soft onsets are not clipped.
        public var preRollMillis = 300
        /// Audio kept after the last speech frame.
        public var tailMillis = 300
        /// After this length, a shorter pause ends the utterance.
        public var softMaximumMillis = 20_000
        public var softPauseMillis = 300
        /// Hard limit; the cut moves to the quietest frame of the last `cutSearchMillis`.
        public var maximumMillis = 30_000
        public var cutSearchMillis = 2_000
        /// The noise floor is the quietest 100 ms block within this window.
        public var noiseWindowMillis = 10_000
        /// The floor never rises above this, so continuous speech (or speech from the first
        /// frame) is not mistaken for background noise. Measured floors were −74 to −53 dBFS.
        public var maximumNoiseDecibels: Float = -45

        public init() {}
    }

    public struct Utterance: Sendable, Equatable {
        /// Sample offset of the first speech frame in this channel.
        public let speechStartSample: Int
        /// Sample offset of the first sample in `pcm` (including pre-roll).
        public let audioStartSample: Int
        public let pcm: Data
        public let speechMillis: Int
        public var durationMillis: Int { pcm.count / 2 * 1_000 / AudioConstants.sampleRate }
    }

    public let configuration: Configuration
    private let frameSamples: Int
    private var pending = Data()
    private var processedFrames = 0
    private var preRoll = [Data]()
    private var frames = [Data]()
    private var levels = [Float]()
    private var speech = [Bool]()
    private var utteranceStartFrame = 0
    private var firstSpeechFrame: Int?
    private var lastSpeechFrame = 0
    private var blockMinimum = Float.infinity
    private var blockFrames = 0
    private var blockMinimums = [Float]()
    /// Most recent frame level in dBFS, for meters and diagnostics.
    public private(set) var lastLevel: Float = -100
    public private(set) var noiseFloor: Float = -100

    public init(configuration: Configuration = Configuration()) {
        self.configuration = configuration
        frameSamples = AudioConstants.sampleRate * configuration.frameMillis / 1_000
    }

    public var isInUtterance: Bool { firstSpeechFrame != nil }

    public static func level(_ frame: Data) -> Float {
        let count = frame.count / 2
        guard count > 0 else { return -100 }
        var energy = 0.0
        frame.withUnsafeBytes { raw in
            for sample in raw.bindMemory(to: Int16.self) {
                let value = Double(Int16(littleEndian: sample)) / 32_768
                energy += value * value
            }
        }
        return Float(10 * log10(max(energy / Double(count), 1e-10)))
    }

    public mutating func append(_ pcm: Data) -> [Utterance] {
        pending.append(pcm)
        let frameBytes = frameSamples * 2
        var result = [Utterance]()
        var offset = 0
        while pending.count - offset >= frameBytes {
            let frame = pending.subdata(in: pending.startIndex + offset..<pending.startIndex + offset + frameBytes)
            offset += frameBytes
            if let utterance = process(frame) { result.append(utterance) }
        }
        pending.removeFirst(offset)
        return result
    }

    /// Ends the channel, emitting any utterance in progress.
    public mutating func finish() -> [Utterance] {
        defer { pending.removeAll(); preRoll.removeAll() }
        guard firstSpeechFrame != nil else { return [] }
        return emit(through: frames.count).map { [$0] } ?? []
    }

    /// Drops buffered audio without emitting it, for cancellation.
    public mutating func reset() {
        pending.removeAll(); preRoll.removeAll(); frames.removeAll(); levels.removeAll(); speech.removeAll()
        firstSpeechFrame = nil
    }

    private var preRollFrames: Int { configuration.preRollMillis / configuration.frameMillis }

    private mutating func process(_ frame: Data) -> Utterance? {
        let level = Self.level(frame)
        lastLevel = level
        trackNoise(level)
        let isSpeech = level >= max(noiseFloor + configuration.speechMarginDecibels, configuration.minimumSpeechDecibels)
        defer { processedFrames += 1 }
        if firstSpeechFrame == nil {
            guard isSpeech else {
                preRoll.append(frame)
                if preRoll.count > preRollFrames { preRoll.removeFirst() }
                return nil
            }
            utteranceStartFrame = processedFrames - preRoll.count
            frames = preRoll; preRoll.removeAll()
            levels = Array(repeating: -100, count: frames.count)
            speech = Array(repeating: false, count: frames.count)
            firstSpeechFrame = frames.count
        }
        frames.append(frame); levels.append(level); speech.append(isSpeech)
        if isSpeech { lastSpeechFrame = frames.count - 1 }
        let length = frames.count * configuration.frameMillis
        let pause = length >= configuration.softMaximumMillis ? configuration.softPauseMillis : configuration.pauseMillis
        let silence = (frames.count - 1 - lastSpeechFrame) * configuration.frameMillis
        if silence >= pause {
            let tail = configuration.tailMillis / configuration.frameMillis
            return emit(through: min(frames.count, lastSpeechFrame + 1 + tail))
        }
        if length >= configuration.maximumMillis {
            // Cut at the quietest recent frame so a word is less likely to be split.
            let search = max(1, configuration.cutSearchMillis / configuration.frameMillis)
            let lower = max((firstSpeechFrame ?? 0) + 1, frames.count - search)
            let cut = (lower..<frames.count).min { levels[$0] < levels[$1] }.map { $0 + 1 } ?? frames.count
            return emit(through: cut)
        }
        return nil
    }

    /// Emits frames[0..<end]; later frames start the next utterance or return to the pre-roll.
    private mutating func emit(through end: Int) -> Utterance? {
        let first = firstSpeechFrame ?? 0
        let included = frames[0..<end]
        let speechCount = speech[0..<end].filter { $0 }.count
        var pcm = Data(capacity: included.count * frameSamples * 2)
        for frame in included { pcm.append(frame) }
        let utterance = Utterance(speechStartSample: (utteranceStartFrame + first) * frameSamples,
                                  audioStartSample: utteranceStartFrame * frameSamples,
                                  pcm: pcm, speechMillis: speechCount * configuration.frameMillis)
        let remainingFrames = Array(frames[end...]), remainingLevels = Array(levels[end...]),
            remainingSpeech = Array(speech[end...])
        let nextStart = utteranceStartFrame + end
        frames.removeAll(); levels.removeAll(); speech.removeAll()
        firstSpeechFrame = nil
        if let firstSpeech = remainingSpeech.firstIndex(of: true) {
            // A hard cut inside continuous speech: the remainder starts the next utterance at once.
            utteranceStartFrame = nextStart
            frames = remainingFrames; levels = remainingLevels; speech = remainingSpeech
            firstSpeechFrame = firstSpeech
            lastSpeechFrame = remainingSpeech.lastIndex(of: true) ?? firstSpeech
        } else {
            preRoll = Array(remainingFrames.suffix(preRollFrames))
        }
        return speechCount * configuration.frameMillis >= configuration.minimumSpeechMillis ? utterance : nil
    }

    private mutating func trackNoise(_ level: Float) {
        blockMinimum = min(blockMinimum, level)
        blockFrames += 1
        let blockLength = max(1, 100 / configuration.frameMillis)
        guard blockFrames >= blockLength else {
            if blockMinimums.isEmpty { noiseFloor = min(blockMinimum, configuration.maximumNoiseDecibels) }
            return
        }
        blockMinimums.append(blockMinimum)
        if blockMinimums.count > max(1, configuration.noiseWindowMillis / 100) { blockMinimums.removeFirst() }
        blockMinimum = .infinity; blockFrames = 0
        noiseFloor = min(blockMinimums.min() ?? level, configuration.maximumNoiseDecibels)
    }
}
