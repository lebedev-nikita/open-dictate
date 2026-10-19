import Foundation

public enum HistoryTiming {
    public static func seconds(_ milliseconds: Int64, locale: Locale) -> String {
        precondition(milliseconds >= 0)
        let separator = locale.decimalSeparator ?? "."
        if milliseconds < 100 { return "<0\(separator)1" }
        let tenths = milliseconds / 100 + (milliseconds % 100 >= 50 ? 1 : 0)
        return "\(tenths / 10)\(separator)\(tenths % 10)"
    }

    public static func labels(for entry: HistoryEntry, russian: Bool, abbreviated: Bool = true) -> [String] {
        if entry.mode == "live" { return [abbreviated ? "Live" : (russian ? "Диктовка Live" : "Live dictation")] }
        let locale = Locale(identifier: russian ? "ru_RU" : "en_US")
        let unit = russian ? "с" : "s"
        return [(entry.transcriptionMillis, russian ? "Транскр." : "Transcr.", russian ? "Транскрипция" : "Transcription"),
                (entry.postProcessingMillis, russian ? "Постобр." : "Post-proc.", russian ? "Постобработка" : "Post-processing")]
            .compactMap { milliseconds, short, full in
                guard let milliseconds, milliseconds >= 0 else { return nil }
                return "\(abbreviated ? short : full) \(seconds(milliseconds, locale: locale)) \(unit)"
            }
    }
}
