import Foundation

public enum HistoryTiming {
    public struct Detail: Equatable, Sendable {
        public let title: String
        public let duration: String?
    }
    public static func seconds(_ milliseconds: Int64, locale: Locale) -> String {
        precondition(milliseconds >= 0)
        let separator = locale.decimalSeparator ?? "."
        if milliseconds < 100 { return "<0\(separator)1" }
        let tenths = milliseconds / 100 + (milliseconds % 100 >= 50 ? 1 : 0)
        return "\(tenths / 10)\(separator)\(tenths % 10)"
    }

    public static func labels(for entry: HistoryEntry, russian: Bool, abbreviated: Bool = true) -> [String] {
        details(for: entry, russian: russian, abbreviated: abbreviated).map {
            [$0.title, $0.duration].compactMap { $0 }.joined(separator: " ")
        }
    }

    public static func details(for entry: HistoryEntry, russian: Bool, abbreviated: Bool = true) -> [Detail] {
        let locale = Locale(identifier: russian ? "ru_RU" : "en_US")
        let unit = russian ? "с" : "s"
        let live = entry.mode == "live"
        let transcription = live ? (abbreviated ? "Live" : (russian ? "Диктовка Live" : "Live dictation")) :
            (abbreviated ? (russian ? "Транскр." : "Transcr.") : (russian ? "Транскрипция" : "Transcription"))
        let postProcessing = abbreviated ? (russian ? "Постобр." : "Post-proc.") : (russian ? "Постобработка" : "Post-processing")
        let processingModel = entry.postProcessingModelID.map {
            TextProcessingModel.title(TextProcessingModel.selection(model: $0,
                tier: TextProcessingServiceTier(rawValue: entry.postProcessingServiceTier ?? "") ?? .standard))
        }
        return [(transcription, entry.transcriptionModelID, live ? nil : entry.transcriptionMillis, live),
                (postProcessing, live ? nil : processingModel, live ? nil : entry.postProcessingMillis, false)]
            .compactMap { label, model, milliseconds, alwaysShow in
                let model = model.flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : $0 }
                let milliseconds = milliseconds.flatMap { $0 >= 0 ? $0 : nil }
                guard alwaysShow || model != nil || milliseconds != nil else { return nil }
                return Detail(title: label + (model.map { ": \($0)" } ?? ""),
                              duration: milliseconds.map { "\(seconds($0, locale: locale)) \(unit)" })
            }
    }
}
