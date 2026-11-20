import Foundation

public struct WordReplacement: Codable, Equatable, Identifiable, Sendable {
    public var id: String
    public var source: String
    public var replacement: String
    public var enabled: Bool
    /// Pattern rules match as written and may reference groups with `$1` or `${12}`.
    public var regex: Bool
    /// Matches the source's exact letter case instead of ignoring case.
    public var matchCase: Bool
    /// Matches only text with no letter, mark, digit or underscore on either side.
    /// Defaults to on for phrases and off for patterns, as both behaved before the option covered phrases.
    public var wholeWord: Bool

    private enum CodingKeys: String, CodingKey { case id, source, replacement, enabled, regex, matchCase, wholeWord }

    public init(id: String = UUID().uuidString.lowercased(), source: String, replacement: String,
                enabled: Bool = true, regex: Bool = false, matchCase: Bool = false, wholeWord: Bool? = nil) {
        self.id = id; self.source = source; self.replacement = replacement
        self.enabled = enabled; self.regex = regex; self.matchCase = matchCase; self.wholeWord = wholeWord ?? !regex
    }
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        source = try container.decode(String.self, forKey: .source)
        replacement = try container.decode(String.self, forKey: .replacement)
        enabled = try container.decode(Bool.self, forKey: .enabled)
        regex = try container.decodeIfPresent(Bool.self, forKey: .regex) ?? false
        matchCase = try container.decodeIfPresent(Bool.self, forKey: .matchCase) ?? false
        wholeWord = try container.decodeIfPresent(Bool.self, forKey: .wholeWord) ?? !regex
    }
    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(source, forKey: .source)
        try container.encode(replacement, forKey: .replacement)
        try container.encode(enabled, forKey: .enabled)
        // Rules with default options keep the version 1 wire format.
        if regex { try container.encode(true, forKey: .regex) }
        if matchCase { try container.encode(true, forKey: .matchCase) }
        if wholeWord == regex { try container.encode(wholeWord, forKey: .wholeWord) }
    }
    /// Structural validity only. Patterns compile per platform, so synced rules never invalidate a document.
    public var isValid: Bool {
        UUID(uuidString: id) != nil && id == id.lowercased() && source.utf16.count <= 256 && replacement.utf16.count <= 2048 &&
        (regex ? !source.isEmpty : !source.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            !replacement.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
    }
    /// Checked when a rule is saved: the pattern compiles, cannot match empty text and references existing groups.
    public var isUsable: Bool {
        guard isValid else { return false }
        guard regex else { return true }
        guard let pattern = WordReplacementEngine.compile(source, matchCase: matchCase, wholeWord: wholeWord),
              pattern.firstMatch(in: "", range: NSRange(location: 0, length: 0)) == nil else { return false }
        return WordReplacementEngine.groupReferences(replacement).allSatisfy { $0 <= pattern.numberOfCaptureGroups }
    }
}

public struct ReplacementDocument: Codable, Equatable, Sendable {
    public var rules: [WordReplacement]
    public init(rules: [WordReplacement] = []) { self.rules = rules }
    /// Versions 2 to 5 mark documents with pattern, case-sensitive, whole-word pattern and in-word phrase rules,
    /// so older clients reject rather than misread them.
    public var schemaVersion: Int {
        rules.contains { !$0.regex && !$0.wholeWord } ? 5 : rules.contains { $0.regex && $0.wholeWord } ? 4 :
            rules.contains(where: \.matchCase) ? 3 : rules.contains(where: \.regex) ? 2 : 1
    }
    private enum CodingKeys: String, CodingKey { case schemaVersion, rules }
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let version = try container.decode(Int.self, forKey: .schemaVersion)
        rules = try container.decode([WordReplacement].self, forKey: .rules)
        guard version == schemaVersion else { throw SyncFormatError.invalid }
    }
    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(schemaVersion, forKey: .schemaVersion)
        try container.encode(rules, forKey: .rules)
    }
    public var isValid: Bool {
        rules.count <= 500 && rules.allSatisfy(\.isValid) && Set(rules.map(\.id)).count == rules.count
    }

}

/// Compiled once per session. Matches the original transcript once; replacements never cascade.
public struct WordReplacementEngine: @unchecked Sendable {
    private struct Matcher { let regex: NSRegularExpression; let rules: [WordReplacement]; let literal: Bool }
    private let matchers: [Matcher]

    public init(rules: [WordReplacement]) {
        let active = rules.filter { $0.isValid && $0.enabled }.sorted {
            if $0.source.utf16.count != $1.source.utf16.count { return $0.source.utf16.count > $1.source.utf16.count }
            return $0.id < $1.id
        }
        let literals = active.filter { !$0.regex }
        // Each phrase carries its own boundaries, so whole-word and in-word phrases share one alternation.
        let alternatives = literals.map {
            let escaped = NSRegularExpression.escapedPattern(for: $0.source.precomposedStringWithCanonicalMapping)
            let cased = $0.matchCase ? "(?-i:" + escaped + ")" : escaped
            return "(" + ($0.wholeWord ? Self.bounded(cased) : cased) + ")"
        }.joined(separator: "|")
        var matchers: [Matcher] = []
        if !alternatives.isEmpty, let regex = try? NSRegularExpression(pattern: alternatives, options: [.caseInsensitive]) {
            matchers.append(Matcher(regex: regex, rules: literals, literal: true))
        }
        // A pattern this platform cannot compile is skipped instead of disabling other rules.
        for rule in active where rule.regex {
            if let regex = Self.compile(rule.source, matchCase: rule.matchCase, wholeWord: rule.wholeWord) { matchers.append(Matcher(regex: regex, rules: [rule], literal: false)) }
        }
        self.matchers = matchers
    }

    static func compile(_ pattern: String, matchCase: Bool = false, wholeWord: Bool = false) -> NSRegularExpression? {
        let pattern = pattern.precomposedStringWithCanonicalMapping
        let options: NSRegularExpression.Options = matchCase ? [] : [.caseInsensitive]
        // The pattern must stand alone, so the wrapper never completes a fragment such as `a)(b`.
        guard let bare = try? NSRegularExpression(pattern: pattern, options: options) else { return nil }
        guard wholeWord else { return bare }
        return try? NSRegularExpression(pattern: bounded(pattern + (endsInQuote(pattern) ? "\\E" : "")), options: options)
    }

    /// A non-capturing wrapper keeps group numbers. Neighbours are letters, combining marks, digits and `_`.
    private static func bounded(_ pattern: String) -> String {
        "(?<![\\p{L}\\p{M}\\p{N}_])(?:" + pattern + ")(?![\\p{L}\\p{M}\\p{N}_])"
    }

    /// An unterminated `\Q` quotes the rest of the pattern, which would swallow the wrapper.
    private static func endsInQuote(_ pattern: String) -> Bool {
        let scalars = Array(pattern.unicodeScalars)
        var index = 0, quoted = false
        while index < scalars.count {
            guard scalars[index] == "\\", index + 1 < scalars.count else { index += 1; continue }
            // Inside a quote only `\E` is special; outside it every escape consumes the next character.
            if quoted { if scalars[index + 1] == "E" { quoted = false; index += 2 } else { index += 1 } }
            else { if scalars[index + 1] == "Q" { quoted = true }; index += 2 }
        }
        return quoted
    }

    public func apply(_ text: String, final: Bool = true) -> String {
        guard !matchers.isEmpty else { return text }
        let normalized = text.precomposedStringWithCanonicalMapping
        let value = normalized as NSString
        let range = NSRange(location: 0, length: value.length)
        // Each rule set is enumerated once; empty matches never replace text.
        let candidates = matchers.map { matcher in matcher.regex.matches(in: normalized, range: range).filter { $0.range.length > 0 } }
        var next = Array(repeating: 0, count: matchers.count)
        var result = "", cursor = 0
        while true {
            var best: (matcher: Int, match: NSTextCheckingResult)?
            for index in matchers.indices {
                while next[index] < candidates[index].count, candidates[index][next[index]].range.location < cursor { next[index] += 1 }
                guard next[index] < candidates[index].count else { continue }
                let match = candidates[index][next[index]]
                // Earliest match wins, then the longer one, then literal rules and rule order.
                if let current = best, match.range.location > current.match.range.location ||
                    (match.range.location == current.match.range.location && match.range.length <= current.match.range.length) { continue }
                best = (index, match)
            }
            // A live trailing token may still grow (e.g. cat → catalog).
            guard let best, final || NSMaxRange(best.match.range) < value.length else { break }
            let matcher = matchers[best.matcher]
            result += value.substring(with: NSRange(location: cursor, length: best.match.range.location - cursor))
            if matcher.literal {
                guard let index = matcher.rules.indices.first(where: { best.match.range(at: $0 + 1).location != NSNotFound }) else { break }
                result += matcher.rules[index].replacement
            } else {
                result += Self.expand(matcher.rules[0].replacement, match: best.match, in: value)
            }
            cursor = NSMaxRange(best.match.range)
        }
        return result + value.substring(from: cursor)
    }

    /// `$n` and `${n}` insert a group, `$$` inserts `$`; any other `$` is literal.
    private static func expand(_ template: String, match: NSTextCheckingResult, in value: NSString) -> String {
        var result = ""
        for token in tokens(template) {
            switch token {
            case .text(let text): result += text
            case .group(let number):
                guard number < match.numberOfRanges else { continue }
                let range = match.range(at: number)
                if range.location != NSNotFound { result += value.substring(with: range) }
            }
        }
        return result
    }

    static func groupReferences(_ template: String) -> [Int] {
        tokens(template).compactMap { if case .group(let number) = $0 { number } else { nil } }
    }

    private enum Token { case text(String), group(Int) }
    private static func tokens(_ template: String) -> [Token] {
        // Scalars match Kotlin's UTF-16 parsing: a combining mark after `$1` does not hide the reference.
        let scalars = Array(template.unicodeScalars)
        func digit(_ scalar: Unicode.Scalar) -> Int? { ("0"..."9").contains(scalar) ? Int(scalar.value) - 48 : nil }
        var tokens: [Token] = [], text = String.UnicodeScalarView(), index = 0
        func group(_ number: Int) { tokens.append(.text(String(text))); text = .init(); tokens.append(.group(number)) }
        while index < scalars.count {
            let scalar = scalars[index]
            guard scalar == "$", index + 1 < scalars.count else { text.append(scalar); index += 1; continue }
            let next = scalars[index + 1]
            if next == "$" { text.append("$"); index += 2; continue }
            if let number = digit(next) { group(number); index += 2; continue }
            if next == "{", let close = scalars[(index + 2)...].firstIndex(of: "}"), (1...3).contains(close - index - 2),
               scalars[(index + 2)..<close].allSatisfy({ digit($0) != nil }) {
                group(scalars[(index + 2)..<close].reduce(0) { $0 * 10 + digit($1)! }); index = close + 1; continue
            }
            text.append(scalar); index += 1
        }
        tokens.append(.text(String(text)))
        return tokens
    }
}
