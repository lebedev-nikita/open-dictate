import AVFoundation
import OpenDictateAudio
import OpenDictateCore

/// Consumed only on AudioRecorder's worker. The sink callback touches only the native ring.
final class AudioCapturePipeline {
    let buffer: ODAudioCaptureBuffer
    private let input: AVAudioPCMBuffer
    private let output: AVAudioPCMBuffer
    private let converter: AVAudioConverter
    private let keepAudio: Bool
    private var recording = Data()
    private var byteCount = 0
    private var chunker = PCMChunker()
    private var onReady: (@Sendable () -> Void)?
    private let onChunk: @Sendable (Data) -> Void
    private let onLevel: @Sendable (Float) -> Void
    private var levelEnergy: Double = 0
    private var levelSamples = 0
    private var failure: DictationError?
    private var finished = false
    var retainedAudio: Data { recording }

    init(format: AVAudioFormat, keepAudio: Bool, onReady: @escaping @Sendable () -> Void,
         onChunk: @escaping @Sendable (Data) -> Void, onLevel: @escaping @Sendable (Float) -> Void) throws {
        guard format.sampleRate > 0, format.sampleRate <= 384_000, format.channelCount > 0,
              let buffer = ODAudioCaptureBuffer(format: format, capacityFrames: AVAudioFrameCount(format.sampleRate)),
              let input = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(ceil(format.sampleRate * 0.04))),
              let target = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 24_000, channels: 1, interleaved: true),
              let converter = AVAudioConverter(from: format, to: target),
              let output = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: 1_088) else { throw DictationError.audio }
        converter.downmix = true
        self.buffer = buffer; self.input = input; self.output = output; self.converter = converter
        self.keepAudio = keepAudio; self.onReady = onReady; self.onChunk = onChunk; self.onLevel = onLevel
    }

    func drain() throws {
        guard !finished, failure == nil else { return }
        do {
            guard !buffer.hasFailed else { throw DictationError.audio }
            while buffer.read(into: input) > 0 {
                var supplied = false, error: NSError?
                let status = converter.convert(to: output, error: &error) { [input] _, state in
                    if supplied { state.pointee = .noDataNow; return nil }
                    supplied = true; state.pointee = .haveData; return input
                }
                guard error == nil, status != .error else { throw DictationError.audio }
                try consumeOutput()
            }
            guard !buffer.hasFailed else { throw DictationError.audio }
        } catch {
            failure = error as? DictationError ?? .audio
            throw error
        }
    }

    /// Called after engine.stop(): include every frame already delivered by the hardware.
    func finish() throws -> (Data, Int) {
        guard !finished else { return (Data(), 0) }
        defer { finished = true; onReady = nil }
        do { try drain() } catch DictationError.tooLong {}
        if let failure {
            if case .tooLong = failure {} else { throw failure }
        } else if byteCount > 0 {
            var status = AVAudioConverterOutputStatus.haveData
            repeat {
                var error: NSError?
                status = converter.convert(to: output, error: &error) { _, state in
                    state.pointee = .endOfStream; return nil
                }
                guard error == nil, status != .error else { throw DictationError.audio }
                // Reaching the recording limit during the converter's tail is an ordinary stop.
                do { try consumeOutput() } catch DictationError.tooLong { break }
            } while status == .haveData
        }
        let tail = chunker.finish()
        if !tail.isEmpty { onChunk(tail) }
        let result = (recording, byteCount)
        recording.removeAll()
        return result
    }

    private func consumeOutput() throws {
        guard let samples = output.int16ChannelData?.pointee, output.frameLength > 0 else { return }
        let count = Int(output.frameLength)
        let data = Data(bytes: samples, count: count * 2)
        guard byteCount + data.count <= AudioConstants.maximumRecordingBytes else { throw DictationError.tooLong }
        byteCount += data.count
        if keepAudio { recording.append(data) }
        let ready = onReady; onReady = nil
        ready?()
        for index in 0..<count {
            let value = Double(samples[index]) / 32768
            levelEnergy += value * value
        }
        levelSamples += count
        if levelSamples >= AudioConstants.chunkBytes / 2 {
            onLevel(Float(min(1, sqrt(levelEnergy / Double(levelSamples)) * 5)))
            levelEnergy = 0; levelSamples = 0
        }
        for chunk in chunker.append(data) { onChunk(chunk) }
    }
}
