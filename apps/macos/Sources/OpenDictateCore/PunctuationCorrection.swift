import Foundation

/// Validation for the built-in punctuation-only post-processing preset.
public enum PunctuationCorrection {
    private static let editable = CharacterSet(charactersIn: ".,!?;:…—–()[]{}\"'«»“”„‘’")

    public static func validated(_ candidate: String, source: String) -> String {
        let candidate = candidate.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !candidate.isEmpty, signature(candidate) == signature(source), numbers(candidate) == numbers(source) else { return source }
        return candidate
    }

    // Only edge punctuation may change. Internal dots, slashes, apostrophes and hyphens
    // remain significant, protecting decimals, addresses and compound words.
    private static func signature(_ text: String) -> [String] {
        text.split(whereSeparator: { $0.isWhitespace }).compactMap {
            let raw = String($0).trimmingCharacters(in: editable)
            let token = raw.contains("://") || raw.contains("@") || raw.contains("/") ? raw : raw.lowercased()
            return token.isEmpty ? nil : token
        }
    }

    private static func numbers(_ text: String) -> [String] {
        let regex = try! NSRegularExpression(pattern: #"[+-]?(?:[.,]\p{N}+|\p{N}+(?:[.,:/]\p{N}+)*)"#)
        let string = text as NSString
        return regex.matches(in: text, range: NSRange(location: 0, length: string.length))
            .map { string.substring(with: $0.range) }
    }

}
