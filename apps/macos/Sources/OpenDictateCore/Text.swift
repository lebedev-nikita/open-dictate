import Foundation

public struct EditableTextSnapshot: Equatable, Sendable {
    public let original: String
    public let selection: NSRange

    public init(original: String, selection: NSRange) {
        self.original = original
        let length = (original as NSString).length
        let start = min(max(0, selection.location), length)
        self.selection = NSRange(location: start, length: min(max(0, selection.length), length - start))
    }

    public func compose(_ transcript: String) -> String {
        (original as NSString).replacingCharacters(in: selection, with: transcript)
    }

    public func cursor(after transcript: String) -> NSRange {
        NSRange(location: selection.location + (transcript as NSString).length, length: 0)
    }

    public var selectedText: String { (original as NSString).substring(with: selection) }

    public var transformationTarget: EditableTextSnapshot {
        selection.length == 0
            ? Self(original: original, selection: NSRange(location: 0, length: (original as NSString).length))
            : self
    }
}

/// A user's own edits or cursor movement invalidate delivery, including within the same app.
public struct TextDeliveryGuard: Sendable {
    public let snapshot: EditableTextSnapshot
    public private(set) var expectedText: String
    public private(set) var expectedSelection: NSRange

    public init(snapshot: EditableTextSnapshot) {
        self.snapshot = snapshot
        expectedText = snapshot.original
        expectedSelection = snapshot.selection
    }

    public func accepts(text: String, selection: NSRange) -> Bool {
        text == expectedText && selection == expectedSelection
    }

    public mutating func didApply(_ transcript: String) {
        expectedText = snapshot.compose(transcript)
        expectedSelection = snapshot.cursor(after: transcript)
    }
}

public enum TranscriptFormatter {
    public static func format(_ text: String) -> String {
        text.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

public enum DictionaryTerms {
    public static func normalize(_ input: String) -> [String] {
        var seen = Set<String>()
        return input.components(separatedBy: .newlines).compactMap { line in
            let term = line.trimmingCharacters(in: .whitespacesAndNewlines)
                .replacingOccurrences(of: "<", with: "").replacingOccurrences(of: ">", with: "")
            guard !term.isEmpty, seen.insert(term.lowercased()).inserted else { return nil }
            return String(term.prefix(120))
        }.prefix(200).map { $0 }
    }
}

public struct HistoryEntry: Codable, Identifiable, Equatable, Sendable {
    public let id: UUID
    public let date: Date
    public let text: String
    public let mode: String
    public let transcriptionMillis: Int64?
    public let postProcessingMillis: Int64?
    public let transcriptionModelID: String?
    public let postProcessingModelID: String?
    public let postProcessingServiceTier: String?
    public init(id: UUID = UUID(), date: Date = Date(), text: String, mode: String,
                transcriptionMillis: Int64? = nil, postProcessingMillis: Int64? = nil,
                transcriptionModelID: String? = nil, postProcessingModelID: String? = nil,
                postProcessingServiceTier: String? = nil) {
        self.id = id; self.date = date; self.text = text; self.mode = mode
        self.transcriptionMillis = mode == "live" ? nil : transcriptionMillis.flatMap { $0 >= 0 ? $0 : nil }
        self.postProcessingMillis = mode == "live" ? nil : postProcessingMillis.flatMap { $0 >= 0 ? $0 : nil }
        self.transcriptionModelID = transcriptionModelID
        self.postProcessingModelID = mode == "live" ? nil : postProcessingModelID
        self.postProcessingServiceTier = mode == "live" ? nil : postProcessingServiceTier
    }
}

public enum HistorySearch {
    public static func matches(_ text: String, query: String) -> Bool {
        let haystack = text.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: .current)
        let words = query.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: .current)
            .split(whereSeparator: { $0.isWhitespace }).map(String.init)
        return words.allSatisfy { word in
            if haystack.contains(word) { return true }
            guard word.count >= 4 else { return false }
            return haystack.split(whereSeparator: { !$0.isLetter && !$0.isNumber }).contains {
                distance(String($0), word) <= 1
            }
        }
    }

    private static func distance(_ a: String, _ b: String) -> Int {
        let left = Array(a), right = Array(b)
        guard abs(left.count - right.count) <= 1 else { return 2 }
        var row = Array(0...right.count)
        for (i, char) in left.enumerated() {
            var next = [i + 1]
            for (j, other) in right.enumerated() {
                next.append(min(next[j] + 1, row[j + 1] + 1, row[j] + (char == other ? 0 : 1)))
            }
            row = next
        }
        return row.last ?? 0
    }
}
