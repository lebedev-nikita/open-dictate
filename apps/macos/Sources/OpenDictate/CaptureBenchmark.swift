#if DEBUG
import AVFoundation
import CoreAudio
import Darwin
import OpenDictateAudio
import OpenDictateCore

/// Development-only capture latency and recovery measurement through the production AudioRecorder.
/// Reports timings, counts and coverage only: no audio, device names, credentials or network.
enum CaptureBenchmark {
    struct Scenario: Sendable {
        let name: String
        let idleBefore: Double
        let duration: Double
    }

    static let roundScenarios = [
        Scenario(name: "cold", idleBefore: 5, duration: 2),
        Scenario(name: "warm", idleBefore: 0.3, duration: 2),
        Scenario(name: "cancel", idleBefore: 0.3, duration: 0.25),
    ]

    struct Cycle: Sendable {
        var scenario: String
        var round: Int
        var startReturnedMs: Double?
        var firstCallbackMs: Double?
        var firstPCMMs: Double?
        var firstChunkMs: Double?
        var stopMs: Double?
        var maxChunkGapMs: Double?
        var coverage: Double?
        var graphs = 0
        var configurationChanges = 0
        var failure: String?

        /// Excludes engine start, which drifts with system audio state independently of capture code.
        var pcmAfterStartMs: Double? { firstPCMMs.flatMap { pcm in startReturnedMs.map { pcm - $0 } } }

        var json: String {
            func number(_ value: Double?) -> String { value.map { String(format: "%.1f", $0) } ?? "null" }
            return "{\"scenario\":\"\(scenario)\",\"round\":\(round),"
                + "\"startReturnedMs\":\(number(startReturnedMs)),\"firstCallbackMs\":\(number(firstCallbackMs)),"
                + "\"firstPCMMs\":\(number(firstPCMMs)),\"firstChunkMs\":\(number(firstChunkMs)),"
                + "\"stopMs\":\(number(stopMs)),\"maxChunkGapMs\":\(number(maxChunkGapMs)),"
                + "\"coverage\":\(coverage.map { String(format: "%.3f", $0) } ?? "null"),"
                + "\"graphs\":\(graphs),\"configurationChanges\":\(configurationChanges),"
                + "\"failure\":\(failure.map { "\"\($0)\"" } ?? "null")}"
        }
    }

    /// Thread-safe event log for one cycle. Times are mach_absolute_time() ticks.
    private final class Probe: @unchecked Sendable {
        private let lock = NSLock()
        private var buffers = [ODAudioCaptureBuffer]()
        private var chunkTimes = [UInt64]()
        private var ready: UInt64?
        private var changes = 0, graphCount = 0
        private var error: DictationError?

        func graphCreated() { lock.withLock { graphCount += 1 } }
        func started(_ buffer: ODAudioCaptureBuffer) { lock.withLock { buffers.append(buffer) } }
        func configurationChanged() { lock.withLock { changes += 1 } }
        func readyNow() { let now = mach_absolute_time(); lock.withLock { if ready == nil { ready = now } } }
        func chunkNow() { let now = mach_absolute_time(); lock.withLock { chunkTimes.append(now) } }
        func failed(_ value: DictationError) { lock.withLock { if error == nil { error = value } } }

        func fill(_ cycle: inout Cycle, origin: UInt64) {
            lock.withLock {
                let firstWrites = buffers.map(\.firstWriteHostTime).filter { $0 > 0 }
                cycle.firstCallbackMs = firstWrites.min().map { milliseconds(origin, $0) }
                cycle.firstPCMMs = ready.map { milliseconds(origin, $0) }
                cycle.firstChunkMs = chunkTimes.first.map { milliseconds(origin, $0) }
                cycle.maxChunkGapMs = zip(chunkTimes, chunkTimes.dropFirst())
                    .map { milliseconds($0, $1) }.max()
                cycle.graphs = graphCount
                cycle.configurationChanges = changes
                if let error, cycle.failure == nil { cycle.failure = "\(error)" }
            }
        }
    }

    /// Wraps the production engine so only timing and lifecycle are observed.
    private final class ProbedEngine: AudioCaptureEngine {
        private let inner: NativeAudioCaptureEngine
        private let probe: Probe
        init(probe: Probe, changed: @escaping @Sendable () -> Void) {
            self.probe = probe
            inner = NativeAudioCaptureEngine { probe.configurationChanged(); changed() }
            probe.graphCreated()
        }
        var format: AVAudioFormat { inner.format }
        var isRunning: Bool { inner.isRunning }
        func prepare() { inner.prepare() }
        func start(buffer: ODAudioCaptureBuffer, format: AVAudioFormat) throws {
            probe.started(buffer)
            try inner.start(buffer: buffer, format: format)
        }
        func stop() { inner.stop() }
    }

    /// The recorder is created after the idle gap's start, as AppModel replaces its standby recorder.
    static func runCycle(scenario: Scenario, round: Int) async -> Cycle {
        var cycle = Cycle(scenario: scenario.name, round: round)
        let probe = Probe()
        let recorder = AudioRecorder(makeEngine: { ProbedEngine(probe: probe, changed: $0) })
        try? await Task.sleep(nanoseconds: UInt64(scenario.idleBefore * 1e9))
        let origin = mach_absolute_time()
        do {
            try await recorder.start(keepAudio: false, onReady: { probe.readyNow() },
                onChunk: { _ in probe.chunkNow() }, onLevel: { _ in }, onError: { probe.failed($0) })
            cycle.startReturnedMs = milliseconds(origin, mach_absolute_time())
        } catch {
            cycle.failure = "start"
        }
        try? await Task.sleep(nanoseconds: UInt64(scenario.duration * 1e9))
        let stopAt = mach_absolute_time()
        let (_, bytes) = await recorder.stop()
        let end = mach_absolute_time()
        cycle.stopMs = milliseconds(stopAt, end)
        probe.fill(&cycle, origin: origin)
        // Audio produced after the first callback, relative to the wall time it had available.
        if let first = cycle.firstCallbackMs {
            let available = milliseconds(origin, stopAt) - first
            if available > 0 { cycle.coverage = Double(bytes) / Double(AudioConstants.sampleRate * 2) * 1_000 / available }
        }
        // A cancel shorter than input startup legitimately produces nothing.
        if cycle.failure == nil, bytes == 0, scenario.duration >= 1 { cycle.failure = "noAudio" }
        return cycle
    }

    static func run(rounds: Int, soakSeconds: Double?) async {
        guard await AVCaptureDevice.requestAccess(for: .audio) else {
            fputs("Microphone access is not granted to this process.\n", stderr); exit(2)
        }
        print("# input: \(inputDescription())")
        var cycles = [Cycle]()
        if let soakSeconds {
            // Switch inputs or AirPods profiles by hand while it records.
            print("# soak: recording \(Int(soakSeconds)) s"); fflush(stdout)
            let cycle = await runCycle(scenario: Scenario(name: "soak", idleBefore: 1, duration: soakSeconds), round: 0)
            print(cycle.json); cycles.append(cycle)
        } else {
            for round in 0..<rounds {
                for scenario in roundScenarios {
                    let cycle = await runCycle(scenario: scenario, round: round)
                    print(cycle.json); fflush(stdout); cycles.append(cycle)
                }
            }
        }
        print(summary(cycles))
    }

    static func summary(_ cycles: [Cycle]) -> String {
        var lines = ["# scenario metric: n p50 p90 max (ms); failures"]
        let groups = Dictionary(grouping: cycles, by: \.scenario)
        for key in groups.keys.sorted() {
            let group = groups[key]!
            let failures = group.filter { $0.failure != nil }.count
            let metrics: [(String, KeyPath<Cycle, Double?>)] = [
                ("firstCallback", \.firstCallbackMs), ("firstPCM", \.firstPCMMs),
                ("firstChunk", \.firstChunkMs), ("startReturned", \.startReturnedMs),
                ("pcmAfterStart", \.pcmAfterStartMs),
                ("maxChunkGap", \.maxChunkGapMs),
            ]
            for (name, path) in metrics {
                let values = group.compactMap { $0[keyPath: path] }
                guard let stats = Distribution(values) else { continue }
                lines.append(String(format: "# %@ %@: %d %.1f %.1f %.1f; failures %d/%d",
                    key, name, values.count, stats.p50, stats.p90, stats.max, failures, group.count))
            }
            let coverage = group.compactMap(\.coverage)
            if let low = coverage.min() { lines.append(String(format: "# %@ coverage min: %.3f", key, low)) }
            let changes = group.map(\.configurationChanges).reduce(0, +)
            if changes > 0 { lines.append("# \(key) configuration changes: \(changes)") }
        }
        return lines.joined(separator: "\n")
    }

    struct Distribution: Equatable {
        let p50: Double, p90: Double, max: Double
        /// Nearest-rank percentiles; nil for an empty sample.
        init?(_ values: [Double]) {
            let sorted = values.sorted()
            guard let last = sorted.last else { return nil }
            func rank(_ p: Double) -> Double { sorted[Swift.max(0, Int((p * Double(sorted.count)).rounded(.up)) - 1)] }
            p50 = rank(0.5); p90 = rank(0.9); max = last
        }
    }

    private static func milliseconds(_ from: UInt64, _ to: UInt64) -> Double {
        var base = mach_timebase_info_data_t()
        mach_timebase_info(&base)
        let delta = to >= from ? Double(to - from) : -Double(from - to)
        return delta * Double(base.numer) / Double(base.denom) / 1e6
    }

    /// Transport and nominal rate only; device names may contain personal names.
    private static func inputDescription() -> String {
        var address = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyDefaultInputDevice,
            mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
        var device = AudioDeviceID(0), size = UInt32(MemoryLayout<AudioDeviceID>.size)
        guard AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &address, 0, nil, &size, &device) == noErr
        else { return "unknown" }
        address.mSelector = kAudioDevicePropertyTransportType
        var transport: UInt32 = 0
        size = UInt32(MemoryLayout<UInt32>.size)
        _ = AudioObjectGetPropertyData(device, &address, 0, nil, &size, &transport)
        let kind: String
        switch transport {
        case kAudioDeviceTransportTypeBuiltIn: kind = "built-in"
        case kAudioDeviceTransportTypeBluetooth, kAudioDeviceTransportTypeBluetoothLE: kind = "bluetooth"
        case kAudioDeviceTransportTypeUSB: kind = "usb"
        default: kind = "other"
        }
        // Read the nominal rate without opening the input: that could renegotiate a Bluetooth profile.
        address.mSelector = kAudioDevicePropertyNominalSampleRate
        var rate: Float64 = 0
        size = UInt32(MemoryLayout<Float64>.size)
        _ = AudioObjectGetPropertyData(device, &address, 0, nil, &size, &rate)
        return "\(kind), nominal \(Int(rate)) Hz"
    }
}
#endif
