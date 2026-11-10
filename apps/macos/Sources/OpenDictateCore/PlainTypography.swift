import Foundation

/// Replaces typographic characters that rarely appear in typed messages with their keyboard equivalents.
/// Letters are never changed; `ё` can be replaced with a case-sensitive pattern rule.
public enum PlainTypography {
    private static let dash = try! NSRegularExpression(pattern: "[ \\t\\u00A0]*[\\u2013\\u2014]+[ \\t\\u00A0]*")
    private static let characters: [Character: String] = [
        "«": "\"", "»": "\"", "“": "\"", "”": "\"", "„": "\"",
        "‘": "'", "’": "'", "…": "...",
    ]

    public static func apply(_ text: String) -> String {
        let value = text.precomposedStringWithCanonicalMapping as NSString
        func at(_ index: Int) -> unichar? { index >= 0 && index < value.length ? value.character(at: index) : nil }
        func isDigit(_ unit: unichar?) -> Bool { unit.map { (48...57).contains($0) } ?? false }
        func isEdge(_ unit: unichar?) -> Bool { unit.map { $0 == 10 || $0 == 13 } ?? true }
        var result = "", cursor = 0
        for match in dash.matches(in: value as String, range: NSRange(location: 0, length: value.length)) {
            let range = match.range, before = at(range.location - 1), after = at(NSMaxRange(range))
            result += value.substring(with: NSRange(location: cursor, length: range.location - cursor))
            // Unspaced number ranges stay compact; other dashes become a spaced hyphen.
            let spaced = value.substring(with: range).contains { $0 == " " || $0 == "\t" || $0 == "\u{00A0}" }
            if !spaced && isDigit(before) && isDigit(after) { result += "-" }
            else { result += (isEdge(before) ? "" : " ") + "-" + (isEdge(after) ? "" : " ") }
            cursor = NSMaxRange(range)
        }
        result += value.substring(from: cursor)
        return String(result.flatMap { characters[$0] ?? String($0) })
    }
}
