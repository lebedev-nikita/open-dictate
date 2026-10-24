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
    typealias EngineFactory = (@escaping @Sendable () -> Void) -> any AudioCaptureEngine
    private struct Engine {
        let id: UUID
        let graph: any AudioCaptureEngine
    }
    private let queue = DispatchQueue(label: "com.opendictate.mac.audio", qos: .userInitiated)
    private let makeEngine: EngineFactory
    private let inputDevice: () -> AudioDeviceID?
    private let canPrepare: (AudioDeviceID) -> Bool
    private var engine: Engine?
    private var preparedEngine: Engine?
    private var preparedInputDevice: AudioDeviceID?
    private var capture: AudioCapturePipeline?
    private var drainTimer: DispatchSourceTimer?
    private var onError: (@Sendable (DictationError) -> Void)?
    private var recoveryAttempts = 0

    init(makeEngine: @escaping EngineFactory = { NativeAudioCaptureEngine(onConfigurationChange: $0) },
         inputDevice: @escaping () -> AudioDeviceID? = AudioRecorder.defaultInputDevice,
         canPrepare: @escaping (AudioDeviceID) -> Bool = AudioRecorder.canPrepareInput) {
        self.makeEngine = makeEngine; self.inputDevice = inputDevice; self.canPrepare = canPrepare
        queue.async { self.prepareNextEngine() }
    }

    /// Allocates the graph ahead of a shortcut, without starting audio hardware.
    private func prepareNextEngine() {
        guard engine == nil, preparedEngine == nil,
              let device = inputDevice(), canPrepare(device) else { return }
        let engine = newEngine()
        engine.graph.prepare()
        preparedEngine = engine
        preparedInputDevice = device
    }

    private static func defaultInputDevice() -> AudioDeviceID? {
        var address = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyDefaultInputDevice,
            mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
        var device = AudioDeviceID(0)
        var size = UInt32(MemoryLayout<AudioDeviceID>.size)
        guard AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &address, 0, nil, &size, &device) == noErr,
              device != kAudioObjectUnknown else { return nil }
        return device
    }

    private static func canPrepareInput(_ device: AudioDeviceID) -> Bool {
        guard AVCaptureDevice.authorizationStatus(for: .audio) == .authorized else { return false }
        var address = AudioObjectPropertyAddress(mSelector: kAudioDevicePropertyTransportType,
            mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
        var transport: UInt32 = 0, size = UInt32(MemoryLayout<UInt32>.size)
        guard AudioObjectGetPropertyData(device, &address, 0, nil, &size, &transport) == noErr else { return false }
        // Opening Bluetooth I/O can itself negotiate a new format. Do it only when
        // dictation starts, rather than caching an idle graph across profile changes.
        return transport != kAudioDeviceTransportTypeBluetooth && transport != kAudioDeviceTransportTypeBluetoothLE
    }

    private func newEngine() -> Engine {
        let id = UUID()
        return Engine(id: id, graph: makeEngine { [weak self] in
            self?.queue.async { [weak self] in self?.configurationChanged(id: id) }
        })
    }

    private func configurationChanged(id: UUID) {
        if preparedEngine?.id == id {
            preparedEngine = nil; preparedInputDevice = nil
            return
        }
        guard engine?.id == id, let capture else { return }
        // A notification queued before start() can arrive after a successful start.
        guard engine?.graph.isRunning != true || engine?.graph.format != capture.format else { return }
        stopEngine()
        do {
            try resumeCapture(capture)
            startDraining(capture)
        } catch { fail(error as? DictationError ?? .audio) }
    }

    private func resumeCapture(_ capture: AudioCapturePipeline) throws {
        while recoveryAttempts < 3 {
            recoveryAttempts += 1
            let next = newEngine()
            try capture.reconfigure(format: next.graph.format)
            engine = next
            do {
                try next.graph.start(buffer: capture.buffer, format: capture.format)
                return
            } catch AudioCaptureEngineError.configurationChanged {
                stopEngine()
            }
        }
        throw DictationError.audio
    }

    private func startDraining(_ capture: AudioCapturePipeline) {
        // Poll only during recording. The realtime callback performs no queue wakeups.
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now(), repeating: .milliseconds(5), leeway: .milliseconds(1))
        timer.setEventHandler { [weak self, weak capture] in
            guard let self, let capture, self.capture === capture else { return }
            do {
                try capture.drain()
                if capture.hasCurrentAudio { self.recoveryAttempts = 0 }
            } catch { self.fail(error as? DictationError ?? .audio) }
        }
        drainTimer = timer
        timer.resume()
    }

    func start(keepAudio: Bool, onReady: @escaping @Sendable () -> Void, onChunk: @escaping @Sendable (Data) -> Void,
               onLevel: @escaping @Sendable (Float) -> Void,
               onError: @escaping @Sendable (DictationError) -> Void) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            queue.async { [self] in
                do {
                    // A switch to/from AirPods while idle must use the new default device.
                    let engine = self.preparedInputDevice == self.inputDevice()
                        ? self.preparedEngine ?? self.newEngine() : self.newEngine()
                    self.preparedEngine = nil
                    self.preparedInputDevice = nil
                    let format = engine.graph.format
                    let capture = try AudioCapturePipeline(format: format, keepAudio: keepAudio,
                        onReady: onReady, onChunk: onChunk, onLevel: onLevel)
                    self.capture = capture; self.onError = onError
                    self.engine = engine
                    self.recoveryAttempts = 0
                    do {
                        try engine.graph.start(buffer: capture.buffer, format: format)
                    } catch AudioCaptureEngineError.configurationChanged {
                        self.stopEngine()
                        try self.resumeCapture(capture)
                    }
                    self.startDraining(capture)
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
        capture?.invalidate(error)
        let callback = onError; onError = nil
        callback?(error)
    }

    private func stopEngine() {
        drainTimer?.cancel(); drainTimer = nil
        engine?.graph.stop()
        engine = nil
    }
}
