import Foundation

/// Without headphones the microphone also hears the call, which would repeat the other
/// side's words under "Me". Both channels are recognized separately, so echo shows up as
/// microphone text that repeats call text recognized at the same time. Those word runs are
/// removed; the user's own words in the same utterance are kept.
public enum MeetingEcho {
    /// Call audio reaches the microphone after output and room latency; recognition
    /// boundaries also differ between channels.
    public static let windowMillis = 1_500
    /// A sentence with at least this share of echoed words is removed whole; below it, only
    /// the echoed runs are removed. Misrecognized echo sentences measured at half echo words.
    public static let sentenceShare = 0.5

    public struct Span: Sendable, Equatable {
        public let startMillis: Int
        public let endMillis: Int
        public let text: String
        public init(startMillis: Int, endMillis: Int, text: String) {
            self.startMillis = startMillis; self.endMillis = endMillis; self.text = text
        }
    }

    private struct Token {
        let word: String
        let range: Range<String.Index>
    }

    private static func tokens(_ text: String) -> [Token] {
        var result = [Token]()
        text.enumerateSubstrings(in: text.startIndex..<text.endIndex, options: [.byWords, .localized]) { word, range, _, _ in
            guard let word else { return }
            result.append(Token(word: word.lowercased().replacingOccurrences(of: "ё", with: "е"), range: range))
        }
        return result
    }

    public static func words(_ text: String) -> [String] { tokens(text).map(\.word) }

    /// Call utterances near the microphone utterance in time.
    public static func overlapping(_ microphone: Span, in call: [Span]) -> [Span] {
        call.filter { $0.startMillis <= microphone.endMillis + windowMillis && $0.endMillis >= microphone.startMillis - windowMillis }
            .sorted { $0.startMillis < $1.startMillis }
    }

    /// The microphone text without echoed call speech, or nil when nothing of the user's own remains.
    public static func removingEcho(from microphone: Span, call: [Span]) -> String? {
        let spoken = tokens(microphone.text)
        guard !spoken.isEmpty else { return nil }
        let nearby = overlapping(microphone, in: call)
        guard !nearby.isEmpty else { return microphone.text }
        let heard = nearby.flatMap { words($0.text) }
        guard !heard.isEmpty else { return microphone.text }
        var echo = echoMask(spoken.map(\.word), heard)
        // A sentence that is mostly echo is echo, including its misrecognized words.
        let text = microphone.text
        text.enumerateSubstrings(in: text.startIndex..<text.endIndex, options: [.bySentences, .substringNotRequired]) { _, range, _, _ in
            let members = spoken.indices.filter { range.contains(spoken[$0].range.lowerBound) }
            guard !members.isEmpty else { return }
            let share = Double(members.filter { echo[$0] }.count) / Double(members.count)
            for index in members { echo[index] = share >= sentenceShare }
        }
        if spoken.count < 3 {
            // A short reply ("yes", "да") during the other side's speech is common and genuine.
            // Only treat it as echo when its timing nearly coincides with the call utterance.
            let duration = max(1, microphone.endMillis - microphone.startMillis)
            let covered = nearby.map { max(0, min($0.endMillis, microphone.endMillis) - max($0.startMillis, microphone.startMillis)) }.max() ?? 0
            if !echo.allSatisfy({ $0 }) || Double(covered) / Double(duration) < 0.5 {
                echo = Array(repeating: false, count: spoken.count)
            }
        }
        guard echo.contains(false) else { return nil }
        guard echo.contains(true) else { return microphone.text }
        // Rebuild from the original text so the user's punctuation and capitalization survive.
        var chunks = [String]()
        var index = 0
        while index < spoken.count {
            guard !echo[index] else { index += 1; continue }
            var end = index
            while end + 1 < spoken.count && !echo[end + 1] { end += 1 }
            let upper = end + 1 < spoken.count ? spoken[end + 1].range.lowerBound : microphone.text.endIndex
            let chunk = microphone.text[spoken[index].range.lowerBound..<upper]
                .trimmingCharacters(in: .whitespacesAndNewlines)
            chunks.append(chunk)
            index = end + 1
        }
        let result = chunks.joined(separator: " ")
        return result.isEmpty ? nil : result
    }

    /// Marks microphone words that repeat call text: runs of at least two consecutive words
    /// that also occur consecutively in the call, plus misrecognized words between runs.
    static func echoMask(_ spoken: [String], _ heard: [String]) -> [Bool] {
        struct Run { let start: Int; let length: Int; let heardStart: Int }
        let m = spoken.count, n = heard.count
        var runs = [Run]()
        var index = 0, cursor = 0
        while index < m {
            var best: Run?
            for j in 0..<n {
                var length = 0
                while index + length < m && j + length < n && spoken[index + length] == heard[j + length] { length += 1 }
                guard length >= 2 else { continue }
                // Prefer longer runs, then call positions that continue the previous run.
                if best == nil || length > best!.length ||
                    (length == best!.length && abs(j - cursor) < abs(best!.heardStart - cursor)) {
                    best = Run(start: index, length: length, heardStart: j)
                }
            }
            if let best {
                runs.append(best); index += best.length; cursor = best.heardStart + best.length
            } else { index += 1 }
        }
        var mask = Array(repeating: false, count: m)
        for run in runs { for k in run.start..<run.start + run.length { mask[k] = true } }
        // Between two runs, words where the call also has a similar number of words are a
        // misrecognition of the same speech. Words the call has no room for are interjections.
        for (run, next) in zip(runs, runs.dropFirst()) {
            let gap = next.start - (run.start + run.length), heardGap = next.heardStart - (run.heardStart + run.length)
            if gap > 0 && gap <= 4 && heardGap > 0 && abs(gap - heardGap) <= 1 {
                for k in (run.start + run.length)..<next.start { mask[k] = true }
            }
        }
        return mask
    }
}
