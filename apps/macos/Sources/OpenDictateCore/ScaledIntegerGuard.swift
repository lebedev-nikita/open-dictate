import Foundation

/// Checks complete Russian/English scaled cardinals; never guesses unsupported number grammar.
enum ScaledIntegerGuard {
    private struct Vocabulary: Decodable { let small: [String: Int64]; let scales: [String: Int64] }
    private static let vocabulary: Vocabulary = {
        guard let url = Bundle.module.url(forResource: "scaled-integers", withExtension: "json"),
              let data = try? Data(contentsOf: url), let value = try? JSONDecoder().decode(Vocabulary.self, from: data) else {
            preconditionFailure("Missing scaled-integer vocabulary")
        }
        return value
    }()
    private static let tokens = try! NSRegularExpression(pattern: #"[\p{L}]+|[\p{Nd}]+"#)
    private static let gap = try! NSRegularExpression(pattern: #"^[\s\p{Z}-]+$"#)
    private static let fractionContext: Set<String> = ["point", "comma", "half", "полтора", "полторы", "половиной", "целая", "целое", "целых", "десятых", "сотых"]
    private static let ordinalContext = try! NSRegularExpression(pattern: #"^(?:first|second|third|.*(?:th|st|nd|rd)|(?:перв|втор|трет|четв[её]рт|пят|шест|седьм|восьм|девят|десят|одиннадцат|двенадцат|тринадцат|четырнадцат|пятнадцат|шестнадцат|семнадцат|восемнадцат|девятнадцат|двадцат|тридцат|сороков|пятидесят|шестидесят|семидесят|восьмидесят|девяност|сот|тысячн|миллионн|миллиардн).*)$"#)
    private static let durationContext = try! NSRegularExpression(pattern: #"^(?:hours?|minutes?|seconds?|days?|weeks?|час.*|минут.*|секунд.*|дней|дня|день|недел.*)$"#)
    // Standard and Indian grouping. Fractional tails are outside this integer-magnitude check.
    private static let minusWord = try! NSRegularExpression(pattern: #"(?:minus|negative|минус)\s*$"#, options: .caseInsensitive)
    private static let numeric = try! NSRegularExpression(pattern: #"(?<![\p{N}])[-−+]?[ \p{Zs}]*(?:\p{Nd}{1,2}(?:,\p{Nd}{2})+,\p{Nd}{3}|\p{Nd}{1,3}(?:[ \p{Zs},.'’٬_]\p{Nd}{3})+|\p{Nd}+)(?![\p{N}])"#)

    static func preserves(source: String, candidate: String) -> Bool {
        let required = spoken(source)
        guard !required.isEmpty else { return true }
        let ns = candidate as NSString
        var available = spoken(candidate) + numeric.matches(in: candidate, range: NSRange(location: 0, length: ns.length)).compactMap { match -> Int64? in
            let raw = ns.substring(with: match.range).trimmingCharacters(in: .whitespaces)
            let digits = raw.compactMap { $0.wholeNumberValue }.map(String.init).joined()
            guard let value = Int64(digits) else { return nil }
            let prefix = ns.substring(to: match.range.location)
            return raw.hasPrefix("-") || raw.hasPrefix("−") || matches(minusWord, prefix) ? -value : value
        }
        for value in required {
            guard let i = available.firstIndex(of: value) else { return false }
            available.remove(at: i)
        }
        return true
    }
    private static func known(_ word: String) -> Bool {
        vocabulary.small[word] != nil || vocabulary.scales[word] != nil || word == "hundred" || word == "and" || Int64(word) != nil
    }
    private static func matches(_ pattern: NSRegularExpression, _ text: String) -> Bool {
        pattern.firstMatch(in: text, range: NSRange(location: 0, length: (text as NSString).length)) != nil
    }
    private static func spoken(_ text: String) -> [Int64] {
        let normalized = text.lowercased(), ns = normalized as NSString
        let spans = tokens.matches(in: normalized, range: NSRange(location: 0, length: ns.length)).map(\.range)
        let words = spans.map { ns.substring(with: $0) }
        func between(_ left: NSRange, _ right: NSRange) -> String {
            ns.substring(with: NSRange(location: NSMaxRange(left), length: right.location - NSMaxRange(left)))
        }
        var values: [Int64] = [], i = 0
        while i < words.count {
            let start = i
            guard known(words[i]) else { i += 1; continue }
            while i + 1 < words.count, known(words[i + 1]), matches(gap, between(spans[i], spans[i + 1])) { i += 1 }
            let end = i; i += 1
            let nearby = words[max(0, start - 2)..<min(words.count, end + 3)]
            if nearby.contains(where: { fractionContext.contains($0) }) { continue }
            if end + 1 < words.count, matches(durationContext, words[end + 1]) || matches(ordinalContext, words[end + 1]) { continue }
            if start > 0, words[start - 1].first?.isNumber == true, [".", ","].contains(between(spans[start - 1], spans[start])) { continue }
            if end + 1 < words.count, words[end + 1].first?.isNumber == true, [".", ","].contains(between(spans[end], spans[end + 1])) { continue }
            guard let value = parse(Array(words[start...end])) else { continue }
            let prefixStart = start > 0 ? NSMaxRange(spans[start - 1]) : 0
            let prefix = ns.substring(with: NSRange(location: prefixStart, length: spans[start].location - prefixStart)).trimmingCharacters(in: .whitespacesAndNewlines)
            let negative = (start > 0 && ["minus", "negative", "минус"].contains(words[start - 1])) || ["-", "−"].contains(prefix)
            values.append(negative ? -value : value)
        }
        return values
    }
    private static func parse(_ words: [String]) -> Int64? {
        guard words.contains(where: { vocabulary.scales[$0] != nil }) else { return nil }
        var total: Int64 = 0, previousScale = Int64.max, chunk: [String] = []
        for word in words where word != "and" {
            if let scale = vocabulary.scales[word] {
                guard let value = group(chunk), value > 0, scale < previousScale else { return nil }
                total += value * scale; previousScale = scale; chunk = []
            } else { chunk.append(word) }
        }
        guard let tail = chunk.isEmpty ? 0 : group(chunk), tail < previousScale else { return nil }
        return total + tail
    }
    private static func group(_ words: [String]) -> Int64? {
        guard !words.isEmpty else { return nil }
        if words.count == 1, let value = Int64(words[0]) { return (0...999).contains(value) ? value : nil }
        var value: Int64 = 0, maximum: Int64 = 999
        for (i, word) in words.enumerated() {
            if word == "hundred" {
                guard i == 1, (1...9).contains(value) else { return nil }
                value *= 100; maximum = 99
            } else {
                guard let n = vocabulary.small[word], n <= maximum else { return nil }
                value += n
                maximum = n >= 100 ? 99 : n >= 20 && n % 10 == 0 ? 9 : -1
            }
        }
        return value
    }
}
