import Combine
import OpenDictateCore

enum RecordingIndicatorStyle: String, CaseIterable {
    case compact, waveform

    func title(russian: Bool) -> String {
        switch self {
        case .compact: return russian ? "Микрофон" : "Microphone"
        case .waveform: return russian ? "Полоски" : "Waveform"
        }
    }
}

/// Only the HUD observes microphone levels, so audio updates never rebuild menu state.
@MainActor
final class RecordingAudioLevels: ObservableObject {
    @Published private(set) var history = AudioLevelHistory()
    private let clockOrigin = ContinuousClock.now

    func append(_ level: Float) { append(level, at: clockOrigin.duration(to: .now)) }

    func append(_ level: Float, at time: Duration) {
        var updated = history
        if updated.append(level, at: time) { history = updated }
    }

    func reset() { history.reset() }
}
