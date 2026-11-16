import AppKit
import AVFoundation
import CoreAudio
import OpenDictateAudio

/// Where a meeting hears the other side: one application's output or all system audio.
enum MeetingAudioSource: Hashable, Codable, Sendable {
    case system
    case application(bundleID: String, name: String)

    var bundleID: String? { if case .application(let id, _) = self { return id }; return nil }
}

/// Core Audio process objects: the HAL's view of each process connected to audio.
enum AudioProcesses {
    struct Process: Equatable {
        let object: AudioObjectID
        let pid: pid_t
        let bundleID: String
    }

    static func all() -> [Process] {
        var address = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyProcessObjectList,
            mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
        var size: UInt32 = 0
        guard AudioObjectGetPropertyDataSize(AudioObjectID(kAudioObjectSystemObject), &address, 0, nil, &size) == noErr,
              size > 0 else { return [] }
        var objects = [AudioObjectID](repeating: 0, count: Int(size) / MemoryLayout<AudioObjectID>.size)
        guard AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &address, 0, nil, &size, &objects) == noErr
        else { return [] }
        return objects.compactMap { object in
            guard let pid: pid_t = value(object, kAudioProcessPropertyPID) else { return nil }
            return Process(object: object, pid: pid, bundleID: string(object, kAudioProcessPropertyBundleID) ?? "")
        }
    }

    static func object(for pid: pid_t) -> AudioObjectID? {
        var address = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyTranslatePIDToProcessObject,
            mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
        var input = pid, object = AudioObjectID(kAudioObjectUnknown)
        var size = UInt32(MemoryLayout<AudioObjectID>.size)
        let status = AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &address,
            UInt32(MemoryLayout<pid_t>.size), &input, &size, &object)
        return status == noErr && object != kAudioObjectUnknown ? object : nil
    }

    static func isRunningOutput(_ object: AudioObjectID) -> Bool {
        (value(object, kAudioProcessPropertyIsRunningOutput) as UInt32?).map { $0 != 0 } ?? false
    }

    static func parent(of pid: pid_t) -> pid_t? {
        var info = kinfo_proc()
        var size = MemoryLayout<kinfo_proc>.stride
        var name: [Int32] = [CTL_KERN, KERN_PROC, KERN_PROC_PID, pid]
        guard sysctl(&name, 4, &info, &size, nil, 0) == 0, size > 0 else { return nil }
        let parent = info.kp_eproc.e_ppid
        return parent > 1 ? parent : nil
    }

    /// An application's audio may come from helper processes (browser renderers, call
    /// engines). They share its bundle identifier prefix or descend from its main process.
    static func belongs(_ process: Process, to bundleID: String, mainPIDs: Set<pid_t>,
                        parent: (pid_t) -> pid_t? = AudioProcesses.parent) -> Bool {
        if process.bundleID == bundleID || process.bundleID.hasPrefix(bundleID + ".") { return true }
        var current: pid_t? = process.pid
        for _ in 0..<6 {
            guard let pid = current else { return false }
            if mainPIDs.contains(pid) { return true }
            current = parent(pid)
        }
        return false
    }

    static func matching(bundleID: String) -> [AudioObjectID] {
        let mainPIDs = Set(NSRunningApplication.runningApplications(withBundleIdentifier: bundleID).map(\.processIdentifier))
        return all().filter { belongs($0, to: bundleID, mainPIDs: mainPIDs) }.map(\.object).sorted()
    }

    private static func value<T>(_ object: AudioObjectID, _ selector: AudioObjectPropertySelector) -> T? {
        var address = AudioObjectPropertyAddress(mSelector: selector, mScope: kAudioObjectPropertyScopeGlobal,
                                                 mElement: kAudioObjectPropertyElementMain)
        var size = UInt32(MemoryLayout<T>.size)
        let pointer = UnsafeMutableRawPointer.allocate(byteCount: MemoryLayout<T>.size, alignment: MemoryLayout<T>.alignment)
        defer { pointer.deallocate() }
        guard AudioObjectGetPropertyData(object, &address, 0, nil, &size, pointer) == noErr else { return nil }
        return pointer.load(as: T.self)
    }

    private static func string(_ object: AudioObjectID, _ selector: AudioObjectPropertySelector) -> String? {
        var address = AudioObjectPropertyAddress(mSelector: selector, mScope: kAudioObjectPropertyScopeGlobal,
                                                 mElement: kAudioObjectPropertyElementMain)
        var size = UInt32(MemoryLayout<CFString?>.size)
        var value: Unmanaged<CFString>?
        guard AudioObjectGetPropertyData(object, &address, 0, nil, &size, &value) == noErr,
              let value else { return nil }
        return value.takeRetainedValue() as String
    }
}

/// Captures a process tap through a private aggregate device, like a microphone for
/// `AudioRecorder`. macOS asks for System Audio Recording permission (not Screen Recording)
/// the first time such a device starts; until it is allowed, the tap delivers silence.
/// Created and used only on AudioRecorder's worker. Requires macOS 14.2.
final class ProcessTapCaptureEngine: AudioCaptureEngine {
    private let source: MeetingAudioSource
    private let onConfigurationChange: @Sendable () -> Void
    private let listenerQueue = DispatchQueue(label: "com.opendictate.mac.meeting-tap")
    private let description: CATapDescription
    private var tapID = AudioObjectID(kAudioObjectUnknown)
    private var aggregateID = AudioObjectID(kAudioObjectUnknown)
    private var procID: AudioDeviceIOProcID?
    private var processListener: AudioObjectPropertyListenerBlock?
    private var formatListener: AudioObjectPropertyListenerBlock?
    private var processes = [AudioObjectID]()
    private var initialFormat = AVAudioFormat()
    /// The tap's current format, so AudioRecorder rebuilds capture when it changes.
    var format: AVAudioFormat {
        tapID == kAudioObjectUnknown || aggregateID == kAudioObjectUnknown ? AVAudioFormat()
            : Self.tapFormat(tapID) ?? initialFormat
    }
    private(set) var isRunning = false

    init(source: MeetingAudioSource, onConfigurationChange: @escaping @Sendable () -> Void) {
        self.source = source; self.onConfigurationChange = onConfigurationChange
        switch source {
        case .system:
            let own = AudioProcesses.object(for: getpid()).map { [$0] } ?? []
            description = CATapDescription(stereoGlobalTapButExcludeProcesses: own)
        case .application(let bundleID, _):
            processes = AudioProcesses.matching(bundleID: bundleID)
            description = CATapDescription(stereoMixdownOfProcesses: processes)
        }
        description.name = "OpenDictate meeting"
        description.isPrivate = true
        description.muteBehavior = .unmuted
        guard AudioHardwareCreateProcessTap(description, &tapID) == noErr, tapID != kAudioObjectUnknown,
              let tapFormat = Self.tapFormat(tapID) else { tapID = kAudioObjectUnknown; return }
        initialFormat = tapFormat
        // Only the tap: an output sub-device would also open its microphone (AirPods would
        // switch to their call profile). No tap auto-start: with it, AudioDeviceStart waits
        // until the tapped app plays audio.
        let aggregate: [String: Any] = [
            kAudioAggregateDeviceNameKey: "OpenDictate meeting",
            kAudioAggregateDeviceUIDKey: "com.opendictate.mac.meeting.\(UUID().uuidString)",
            kAudioAggregateDeviceIsPrivateKey: true,
            kAudioAggregateDeviceIsStackedKey: false,
            kAudioAggregateDeviceTapListKey: [[kAudioSubTapUIDKey: description.uuid.uuidString,
                                               kAudioSubTapDriftCompensationKey: true]]
        ]
        guard AudioHardwareCreateAggregateDevice(aggregate as CFDictionary, &aggregateID) == noErr else {
            aggregateID = kAudioObjectUnknown; return
        }
        installListeners()
    }

    deinit {
        stop()
        removeListeners()
        if aggregateID != kAudioObjectUnknown { AudioHardwareDestroyAggregateDevice(aggregateID) }
        if tapID != kAudioObjectUnknown { AudioHardwareDestroyProcessTap(tapID) }
    }

    func prepare() {}

    func start(buffer: ODAudioCaptureBuffer, format: AVAudioFormat) throws {
        guard aggregateID != kAudioObjectUnknown, procID == nil else { throw DictationError.audio }
        let block = buffer.makeDeviceIOBlock(firstBuffer: try firstTapBuffer(format: format))
        guard AudioDeviceCreateIOProcIDWithBlock(&procID, aggregateID, nil, block) == noErr, let procID else {
            throw DictationError.audio
        }
        guard AudioDeviceStart(aggregateID, procID) == noErr else {
            AudioDeviceDestroyIOProcID(aggregateID, procID); self.procID = nil
            throw AudioCaptureEngineError.configurationChanged
        }
        isRunning = true
    }

    func stop() {
        guard let procID else { return }
        AudioDeviceStop(aggregateID, procID)
        AudioDeviceDestroyIOProcID(aggregateID, procID)
        self.procID = nil; isRunning = false
    }

    /// The tap's input buffers follow any sub-device inputs of the aggregate device.
    private func firstTapBuffer(format: AVAudioFormat) throws -> UInt32 {
        var address = AudioObjectPropertyAddress(mSelector: kAudioDevicePropertyStreamConfiguration,
            mScope: kAudioObjectPropertyScopeInput, mElement: kAudioObjectPropertyElementMain)
        var size: UInt32 = 0
        guard AudioObjectGetPropertyDataSize(aggregateID, &address, 0, nil, &size) == noErr, size > 0 else {
            throw DictationError.audio
        }
        let list = UnsafeMutableRawPointer.allocate(byteCount: Int(size), alignment: MemoryLayout<AudioBufferList>.alignment)
        defer { list.deallocate() }
        guard AudioObjectGetPropertyData(aggregateID, &address, 0, nil, &size, list) == noErr else { throw DictationError.audio }
        let count = list.load(as: AudioBufferList.self).mNumberBuffers
        let tapBuffers = format.isInterleaved ? 1 : format.channelCount
        guard count >= tapBuffers else { throw DictationError.audio }
        return count - tapBuffers
    }

    private static func tapFormat(_ tap: AudioObjectID) -> AVAudioFormat? {
        var address = AudioObjectPropertyAddress(mSelector: kAudioTapPropertyFormat,
            mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
        var description = AudioStreamBasicDescription()
        var size = UInt32(MemoryLayout<AudioStreamBasicDescription>.size)
        guard AudioObjectGetPropertyData(tap, &address, 0, nil, &size, &description) == noErr,
              description.mSampleRate > 0 else { return nil }
        return AVAudioFormat(streamDescription: &description)
    }

    private func installListeners() {
        let change = onConfigurationChange
        var formatAddress = AudioObjectPropertyAddress(mSelector: kAudioTapPropertyFormat,
            mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
        let formatListener: AudioObjectPropertyListenerBlock = { _, _ in change() }
        if AudioObjectAddPropertyListenerBlock(tapID, &formatAddress, listenerQueue, formatListener) == noErr {
            self.formatListener = formatListener
        }
        guard case .application(let bundleID, _) = source else { return }
        // Call apps start helper processes (a browser tab, a call engine) after the meeting starts.
        var processAddress = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyProcessObjectList,
            mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
        let tap = tapID, description = self.description
        var known = processes
        let processListener: AudioObjectPropertyListenerBlock = { _, _ in
            let current = AudioProcesses.matching(bundleID: bundleID)
            guard current != known else { return }
            known = current
            description.processes = current
            var address = AudioObjectPropertyAddress(mSelector: kAudioTapPropertyDescription,
                mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
            var value: CATapDescription = description
            _ = AudioObjectSetPropertyData(tap, &address, 0, nil, UInt32(MemoryLayout<CATapDescription>.size), &value)
        }
        if AudioObjectAddPropertyListenerBlock(AudioObjectID(kAudioObjectSystemObject), &processAddress,
                                               listenerQueue, processListener) == noErr {
            self.processListener = processListener
        }
    }

    private func removeListeners() {
        if let formatListener {
            var address = AudioObjectPropertyAddress(mSelector: kAudioTapPropertyFormat,
                mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
            AudioObjectRemovePropertyListenerBlock(tapID, &address, listenerQueue, formatListener)
        }
        if let processListener {
            var address = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyProcessObjectList,
                mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
            AudioObjectRemovePropertyListenerBlock(AudioObjectID(kAudioObjectSystemObject), &address,
                                                   listenerQueue, processListener)
        }
        // Listener blocks may be running on the queue; wait for them before the tap is destroyed.
        listenerQueue.sync {}
    }
}
