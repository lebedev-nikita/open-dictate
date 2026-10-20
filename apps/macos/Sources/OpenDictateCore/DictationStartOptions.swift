import Foundation

/// A per-session choice; resolving it never changes saved preferences.
public struct DictationStartOptions: Equatable, Sendable {
    public let mode: DictationMode
    public let postProcessing: PostProcessingSettings

    public init(mode: DictationMode, postProcessing: PostProcessingSettings, postProcessingOverride: Bool? = nil) {
        self.mode = postProcessingOverride == nil ? mode : .accurate
        var processing = postProcessing.forMode(self.mode)
        if let postProcessingOverride {
            processing.enabled = postProcessingOverride
            if postProcessingOverride && !processing.shouldProcess { processing.punctuation = true }
        }
        self.postProcessing = processing
    }
}

public enum DictationProcessingStage: CaseIterable, Sendable {
    case transcription, postProcessing, transformation

    public var symbol: String {
        self == .transcription ? "waveform" : "wand.and.stars"
    }

    public func label(russian: Bool) -> String {
        switch self {
        case .transcription: return russian ? "Распознавание" : "Transcribing"
        case .postProcessing: return russian ? "Постобработка" : "Post-processing"
        case .transformation: return russian ? "Изменение текста" : "Editing text"
        }
    }
}
