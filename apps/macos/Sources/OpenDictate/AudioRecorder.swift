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
    private var capture: AudioCapturePipeline?
    private var drainTimer: DispatchSourceTimer?
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
            queue.async { [self] in
                do {
                    // A switch to/from AirPods while idle must use the new default device.
                    let engine = self.preparedInputDevice == self.defaultInputDevice()
                        ? self.preparedEngine ?? AVAudioEngine() : AVAudioEngine()
                    self.preparedEngine = nil
                    self.preparedInputDevice = nil
                    let input = engine.inputNode
                    let format = input.outputFormat(forBus: 0)
                    let capture = try AudioCapturePipeline(format: format, keepAudio: keepAudio,
                        onReady: onReady, onChunk: onChunk, onLevel: onLevel)
                    self.capture = capture; self.onError = onError
                    self.engine = engine
                    let sink = capture.buffer.makeSinkNode()
                    engine.attach(sink)
                    engine.connect(input, to: sink, format: format)
                    engine.prepare()
                    try engine.start()
                    // Poll only during recording. The realtime callback performs no queue wakeups.
                    let timer = DispatchSource.makeTimerSource(queue: self.queue)
                    timer.schedule(deadline: .now(), repeating: .milliseconds(5), leeway: .milliseconds(1))
                    timer.setEventHandler { [weak self, weak capture] in
                        guard let self, let capture, self.capture === capture else { return }
                        do { try capture.drain() }
                        catch { self.fail(error as? DictationError ?? .audio) }
                    }
                    self.drainTimer = timer
                    timer.resume()
                    continuation.resume()
                } catch {
                    self.stopEngine()
                    self.capture = nil; self.onError = nil
                    continuation.resume(throwing: DictationError.audio)
                }
            }
        }
    }

    func stop() async -> (Data, Int) {
        await withCheckedContinuation { continuation in
            queue.async {
                self.stopEngine()
                let result: (Data, Int)
                do { result = try self.capture?.finish() ?? (Data(), 0) }
                catch {
                    self.onError?(error as? DictationError ?? .audio)
                    // A valid prefix remains available for an explicit Accurate retry after failure.
                    // Zero usable bytes prevents normal completion from submitting truncated audio.
                    result = (self.capture?.retainedAudio ?? Data(), 0)
                }
                self.capture = nil; self.onError = nil
                continuation.resume(returning: result)
            }
        }
    }

    private func fail(_ error: DictationError) {
        stopEngine()
        onError?(error)
    }

    private func stopEngine() {
        drainTimer?.cancel(); drainTimer = nil
        engine?.stop()
        engine = nil
    }
}
