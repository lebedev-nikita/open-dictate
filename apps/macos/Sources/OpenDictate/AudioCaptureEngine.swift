import AVFoundation
import OpenDictateAudio

/// Accessed only on AudioRecorder's worker; configuration callbacks may arrive on any thread.
protocol AudioCaptureEngine: AnyObject {
    var format: AVAudioFormat { get }
    var isRunning: Bool { get }
    func prepare()
    func start(buffer: ODAudioCaptureBuffer, format: AVAudioFormat) throws
    func stop()
}

enum AudioCaptureEngineError: Error { case configurationChanged }

final class NativeAudioCaptureEngine: AudioCaptureEngine {
    private let engine = AVAudioEngine()
    private var sink: AVAudioSinkNode?
    private var observer: NSObjectProtocol?

    init(onConfigurationChange: @escaping @Sendable () -> Void) {
        observer = NotificationCenter.default.addObserver(forName: .AVAudioEngineConfigurationChange,
            object: engine, queue: nil) { _ in onConfigurationChange() }
    }

    deinit { if let observer { NotificationCenter.default.removeObserver(observer) } }

    var format: AVAudioFormat { engine.inputNode.outputFormat(forBus: 0) }
    var isRunning: Bool { engine.isRunning }
    func prepare() { _ = engine.inputNode; engine.prepare() }

    func start(buffer: ODAudioCaptureBuffer, format: AVAudioFormat) throws {
        let sink = buffer.makeSinkNode()
        self.sink = sink
        engine.attach(sink)
        guard buffer.connectInput(of: engine, to: sink) else { throw AudioCaptureEngineError.configurationChanged }
        engine.prepare()
        try engine.start()
    }

    func stop() {
        engine.stop()
        if let sink {
            engine.disconnectNodeInput(sink)
            engine.detach(sink)
            self.sink = nil
        }
    }
}
