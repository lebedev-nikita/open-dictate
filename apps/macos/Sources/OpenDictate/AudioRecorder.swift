import AVFoundation
import CoreAudio
import OpenDictateCore

protocol AudioRecording: Sendable {
    func start(keepAudio: Bool, onReady: @escaping @Sendable () -> Void, onChunk: @escaping @Sendable (Data) -> Void,
               onLevel: @escaping @Sendable (Float) -> Void,
               onError: @escaping @Sendable (DictationError) -> Void) async throws
    func stop() async -> (Data, Int)
}

/// Every engine, conversion and recording operation runs on this serial worker.
final class AudioRecorder: AudioRecording, @unchecked Sendable {
    private let queue = DispatchQueue(label: "com.opendictate.mac.audio", qos: .userInitiated)
    private var engine: AVAudioEngine?
    private var preparedEngine: AVAudioEngine?
    private var preparedInputDevice: AudioDeviceID?
    private var recordingID: UUID?
    private var converter: AVAudioConverter?
    private var chunker = PCMChunker()
    private var recording = Data()
    private var byteCount = 0
    private var keepAudio = false
    private var onReady: (@Sendable () -> Void)?
    private var onChunk: (@Sendable (Data) -> Void)?
    private var onLevel: (@Sendable (Float) -> Void)?
    private var onError: (@Sendable (DictationError) -> Void)?

    init() {
        queue.async { self.prepareNextEngine() }
    }

    /// Allocates the graph ahead of a shortcut, without starting audio hardware.
    private func prepareNextEngine() {
        guard engine == nil, preparedEngine == nil,
              AVCaptureDevice.authorizationStatus(for: .audio) == .authorized,
              let device = defaultInputDevice() else { return }
        let engine = AVAudioEngine()
        _ = engine.inputNode
        engine.prepare()
        preparedEngine = engine
        preparedInputDevice = device
    }

    private func defaultInputDevice() -> AudioDeviceID? {
        var address = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyDefaultInputDevice,
            mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
        var device = AudioDeviceID(0)
        var size = UInt32(MemoryLayout<AudioDeviceID>.size)
        guard AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &address, 0, nil, &size, &device) == noErr,
              device != kAudioObjectUnknown else { return nil }
        return device
    }

    func start(keepAudio: Bool, onReady: @escaping @Sendable () -> Void, onChunk: @escaping @Sendable (Data) -> Void,
               onLevel: @escaping @Sendable (Float) -> Void,
               onError: @escaping @Sendable (DictationError) -> Void) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            queue.async {
                do {
                    // A switch to/from AirPods while idle must use the new default device.
                    let engine = self.preparedInputDevice == self.defaultInputDevice()
                        ? self.preparedEngine ?? AVAudioEngine() : AVAudioEngine()
                    self.preparedEngine = nil
                    self.preparedInputDevice = nil
                    let input = engine.inputNode
                    let format = input.outputFormat(forBus: 0)
                    guard format.sampleRate > 0, format.channelCount > 0,
                          let target = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 24_000,
                                                     channels: 1, interleaved: true),
                          let converter = AVAudioConverter(from: format, to: target) else { throw DictationError.audio }
                    converter.downmix = true
                    self.converter = converter
                    self.keepAudio = keepAudio
                    self.recording.removeAll(); self.byteCount = 0; self.chunker = PCMChunker()
                    self.onReady = onReady; self.onChunk = onChunk; self.onLevel = onLevel; self.onError = onError
                    self.engine = engine
                    let recordingID = UUID()
                    self.recordingID = recordingID
                    input.installTap(onBus: 0, bufferSize: AVAudioFrameCount(format.sampleRate * 0.04), format: format) { buffer, _ in
                        // Engine owns the tap buffer. Copy before returning, convert away from the audio callback.
                        guard let copy = AVAudioPCMBuffer(pcmFormat: buffer.format, frameCapacity: buffer.frameLength) else { return }
                        copy.frameLength = buffer.frameLength
                        let source = UnsafeMutableAudioBufferListPointer(buffer.mutableAudioBufferList)
                        let destination = UnsafeMutableAudioBufferListPointer(copy.mutableAudioBufferList)
                        for index in 0..<min(source.count, destination.count) {
                            if let src = source[index].mData, let dst = destination[index].mData {
                                memcpy(dst, src, Int(source[index].mDataByteSize))
                            }
                        }
                        self.queue.async { self.consume(copy, recordingID: recordingID) }
                    }
                    engine.prepare()
                    try engine.start()
                    continuation.resume()
                } catch {
                    self.stopEngine()
                    continuation.resume(throwing: DictationError.audio)
                }
            }
        }
    }

    func stop() async -> (Data, Int) {
        await withCheckedContinuation { continuation in
            queue.async {
                self.stopEngine()
                let tail = self.chunker.finish()
                if !tail.isEmpty { self.onChunk?(tail) }
                let result = (self.recording, self.byteCount)
                self.recording.removeAll(); self.onReady = nil; self.onChunk = nil; self.onLevel = nil; self.onError = nil
                continuation.resume(returning: result)
            }
        }
    }

    private func consume(_ input: AVAudioPCMBuffer, recordingID: UUID) {
        guard self.recordingID == recordingID, engine != nil, let converter else { return }
        let capacity = AVAudioFrameCount(ceil(Double(input.frameLength) * 24_000 / input.format.sampleRate) + 128)
        guard let output = AVAudioPCMBuffer(pcmFormat: converter.outputFormat, frameCapacity: capacity) else { return }
        var supplied = false, error: NSError?
        let status = converter.convert(to: output, error: &error) { _, state in
            if supplied { state.pointee = .noDataNow; return nil }
            supplied = true; state.pointee = .haveData; return input
        }
        guard error == nil, status != .error else { fail(.audio); return }
        guard let samples = output.int16ChannelData?.pointee, output.frameLength > 0 else { return }
        let count = Int(output.frameLength)
        let data = Data(bytes: samples, count: count * 2)
        guard byteCount + data.count <= AudioConstants.maximumRecordingBytes else { fail(.tooLong); return }
        byteCount += data.count
        if keepAudio { recording.append(data) }
        let ready = onReady; onReady = nil
        ready?()
        var energy: Double = 0
        for i in 0..<count { let value = Double(samples[i]) / 32768; energy += value * value }
        onLevel?(Float(min(1, sqrt(energy / Double(count)) * 5)))
        for chunk in chunker.append(data) { onChunk?(chunk) }
    }

    private func fail(_ error: DictationError) {
        stopEngine()
        onError?(error)
    }

    private func stopEngine() {
        if let engine {
            engine.inputNode.removeTap(onBus: 0)
            engine.stop()
        }
        engine = nil; converter = nil; recordingID = nil
    }
}
