import Foundation

/// Roles come from capture channels: the microphone is the user, call audio is the other side.
public enum MeetingRole: String, Codable, Sendable, CaseIterable {
    case me, other

    public func title(russian: Bool) -> String {
        switch self {
        case .me: return russian ? "Я" : "Me"
        case .other: return russian ? "Собеседник" : "Other speaker"
        }
    }
}

/// A speaker in the stored transcript. This version has one speaker per channel; the id
/// field lets a later version split a channel into several speakers.
public struct MeetingSpeaker: Codable, Equatable, Sendable, Identifiable {
    public let id: String
    public var role: MeetingRole
    /// A name the user chose for the whole transcript; nil uses the localized role.
    public var name: String?

    public init(id: String, role: MeetingRole, name: String? = nil) {
        self.id = id; self.role = role; self.name = name
    }

    public func displayName(russian: Bool) -> String { name ?? role.title(russian: russian) }
    public static let maximumNameLength = 80
}

public struct MeetingUtterance: Codable, Equatable, Sendable, Identifiable {
    public enum Status: String, Codable, Sendable {
        /// Audio is waiting in memory for recognition.
        case pending
        case recognized
        /// Recognition gave up; the audio is gone. Timestamps remain so the gap is visible.
        case failed
    }
    public let id: UUID
    public var speaker: String
    /// Milliseconds of recorded meeting time; pauses are excluded.
    public var startMillis: Int
    public var durationMillis: Int
    public var text: String
    public var status: Status

    public init(id: UUID = UUID(), speaker: String, startMillis: Int, durationMillis: Int,
                text: String = "", status: Status = .pending) {
        self.id = id; self.speaker = speaker; self.startMillis = startMillis
        self.durationMillis = durationMillis; self.text = text; self.status = status
    }
}

public struct Meeting: Codable, Equatable, Sendable, Identifiable {
    public enum State: String, Codable, Sendable {
        case recording
        case finished
        /// The app stopped (crash, forced quit) before the meeting finished; text so far is kept.
        case interrupted
    }
    public static let currentVersion = 1
    public static let meID = "me"
    public static let otherID = "other"

    public var version = Meeting.currentVersion
    public let id: UUID
    public var startedAt: Date
    /// Recorded time, excluding pauses.
    public var durationMillis: Int
    /// The application whose audio was captured; nil when all system audio was captured.
    public var sourceName: String?
    public var sourceBundleID: String?
    public var transcriptionModel: String?
    public var state: State
    public var speakers: [MeetingSpeaker]
    /// Ordered by start time; channels interleave by the shared meeting clock.
    public private(set) var utterances: [MeetingUtterance]

    public init(id: UUID = UUID(), startedAt: Date, sourceName: String?, sourceBundleID: String?,
                transcriptionModel: String? = nil, state: State = .recording) {
        self.id = id; self.startedAt = startedAt; durationMillis = 0
        self.sourceName = sourceName; self.sourceBundleID = sourceBundleID
        self.transcriptionModel = transcriptionModel; self.state = state
        speakers = [MeetingSpeaker(id: Self.meID, role: .me), MeetingSpeaker(id: Self.otherID, role: .other)]
        utterances = []
    }

    public func speaker(_ id: String) -> MeetingSpeaker? { speakers.first { $0.id == id } }

    /// Inserts by start time. Equal starts keep arrival order, so a channel's own order holds.
    public mutating func insert(_ utterance: MeetingUtterance) {
        let index = utterances.lastIndex { $0.startMillis <= utterance.startMillis }.map { $0 + 1 } ?? 0
        utterances.insert(utterance, at: index)
    }

    public mutating func update(_ id: UUID, _ change: (inout MeetingUtterance) -> Void) {
        guard let index = utterances.firstIndex(where: { $0.id == id }) else { return }
        change(&utterances[index])
    }

    public mutating func remove(_ id: UUID) { utterances.removeAll { $0.id == id } }

    /// Renames a speaker across the whole transcript. A blank name restores the role name.
    public mutating func rename(speaker id: String, to name: String) {
        guard let index = speakers.firstIndex(where: { $0.id == id }) else { return }
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
            .components(separatedBy: .newlines).joined(separator: " ")
        speakers[index].name = trimmed.isEmpty ? nil : String(trimmed.prefix(MeetingSpeaker.maximumNameLength))
    }

    /// Waiting utterances are failed when the meeting ends without them.
    public mutating func failPending() {
        for index in utterances.indices where utterances[index].status == .pending {
            utterances[index].status = .failed
        }
    }

    public var pendingCount: Int { utterances.filter { $0.status == .pending }.count }

    /// Diacritic- and case-insensitive match on text or speaker name, like history search.
    public func search(_ query: String, russian: Bool) -> [MeetingUtterance] {
        let query = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return utterances }
        return utterances.filter { utterance in
            let name = speaker(utterance.speaker)?.displayName(russian: russian) ?? ""
            return [utterance.text, name].contains {
                $0.range(of: query, options: [.caseInsensitive, .diacriticInsensitive]) != nil
            }
        }
    }

    public func decodedValid() -> Bool {
        version <= Self.currentVersion && durationMillis >= 0 && !speakers.isEmpty &&
            utterances.allSatisfy { utterance in speakers.contains { $0.id == utterance.speaker } }
    }
}

public enum MeetingLimits {
    /// Untranscribed audio kept in memory: 15 minutes of 24 kHz mono PCM16 (about 43 MB).
    /// Reaching it pauses capture visibly instead of dropping speech.
    public static let maximumPendingAudioBytes = AudioConstants.sampleRate * 2 * 60 * 15
    /// Concurrent recognition requests for one meeting.
    public static let maximumConcurrentRequests = 3

    /// Exponential backoff for one utterance: 1, 2, 4, 8, 16, then every 30 seconds.
    public static func retryDelaySeconds(attempt: Int) -> Int {
        min(30, 1 << min(max(0, attempt - 1), 5))
    }

    /// Network, timeout, rate limit, server and authorization failures retry the same
    /// utterance. Other client errors would fail again with the same audio.
    public static func isRetryable(status: Int?) -> Bool {
        guard let status else { return true }
        return status == 401 || status == 403 || status == 408 || status == 409 || status == 429 || status >= 500
    }
}

public enum MeetingFormat {
    /// `00:00:04`, hours always present so long meetings sort and align.
    public static func timestamp(_ milliseconds: Int) -> String {
        let seconds = max(0, milliseconds) / 1_000
        return String(format: "%02d:%02d:%02d", seconds / 3_600, seconds / 60 % 60, seconds % 60)
    }

    /// Whole minutes, at least one.
    public static func minutes(_ milliseconds: Int, russian: Bool) -> String {
        let minutes = max(1, Int((Double(max(0, milliseconds)) / 60_000).rounded()))
        return "\(minutes) \(russian ? "мин" : "min")"
    }

    /// `HH:MM:SS` for the live meeting window, `MM:SS` under an hour.
    public static func elapsed(_ milliseconds: Int) -> String {
        let seconds = max(0, milliseconds) / 1_000
        return seconds >= 3_600 ? timestamp(milliseconds) : String(format: "%02d:%02d", seconds / 60, seconds % 60)
    }

    public static func day(_ date: Date, timeZone: TimeZone = .current) -> String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        let parts = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
    }

    public static func title(_ meeting: Meeting, russian: Bool, timeZone: TimeZone = .current) -> String {
        [russian ? "Встреча \(day(meeting.startedAt, timeZone: timeZone))" : "Meeting \(day(meeting.startedAt, timeZone: timeZone))",
         meeting.sourceName, minutes(meeting.durationMillis, russian: russian)].compactMap { $0 }.joined(separator: ", ")
    }

    /// The transcript as Markdown: one bold timestamp and speaker per utterance, blank lines
    /// between them so renderers keep each line. Waiting utterances are omitted.
    public static func markdown(_ meeting: Meeting, russian: Bool, timeZone: TimeZone = .current) -> String {
        var lines = ["# " + title(meeting, russian: russian, timeZone: timeZone)]
        for utterance in meeting.utterances where utterance.status != .pending {
            let name = meeting.speaker(utterance.speaker)?.displayName(russian: russian) ?? utterance.speaker
            let text = utterance.status == .failed ? (russian ? "*(не распознано)*" : "*(not recognized)*")
                : utterance.text.components(separatedBy: .newlines).joined(separator: " ")
            lines.append("**[\(timestamp(utterance.startMillis))] \(escape(name)):** \(text)")
        }
        return lines.joined(separator: "\n\n") + "\n"
    }

    /// `Meeting 2026-11-16 14-05 Zoom.md`, safe for Finder and shells.
    public static func fileName(_ meeting: Meeting, russian: Bool, timeZone: TimeZone = .current) -> String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        let time = calendar.dateComponents([.hour, .minute], from: meeting.startedAt)
        let parts = [russian ? "Встреча" : "Meeting", day(meeting.startedAt, timeZone: timeZone),
                     String(format: "%02d-%02d", time.hour ?? 0, time.minute ?? 0), meeting.sourceName]
        let name = parts.compactMap { $0 }.joined(separator: " ")
            .components(separatedBy: CharacterSet(charactersIn: "/:\\\0").union(.newlines)).joined(separator: "-")
        return String(name.prefix(120)) + ".md"
    }

    private static func escape(_ name: String) -> String {
        name.replacingOccurrences(of: "*", with: "\\*").replacingOccurrences(of: "_", with: "\\_")
    }
}
