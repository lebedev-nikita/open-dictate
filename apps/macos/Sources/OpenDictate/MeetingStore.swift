import Foundation
import OpenDictateCore

/// Meeting transcripts, one owner-only JSON file each, under Application Support. Text only:
/// audio is never stored. Not part of Drive synchronization or dictation history.
@MainActor
final class MeetingStore: ObservableObject {
    /// Newest first.
    @Published private(set) var meetings = [Meeting]()
    @Published private(set) var error: String?
    private let folder: URL?

    init(inMemory: Bool = false, folder: URL? = nil) {
        guard !inMemory else { self.folder = nil; return }
        let folder = folder ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("OpenDictate/Meetings", isDirectory: true)
        self.folder = folder
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true,
                                                    attributes: [.posixPermissions: 0o700])
            try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: folder.path)
            let files = try FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil)
                .filter { $0.pathExtension == "json" }
            var loaded = [Meeting]()
            for file in files {
                guard var meeting = try? JSONDecoder.meetings.decode(Meeting.self, from: Data(contentsOf: file)),
                      meeting.decodedValid(), file.deletingPathExtension().lastPathComponent == meeting.id.uuidString else { continue }
                // A meeting still marked as recording belonged to a process that ended without finishing it.
                if meeting.state == .recording {
                    meeting.state = .interrupted
                    meeting.failPending()
                    write(meeting)
                }
                loaded.append(meeting)
            }
            meetings = loaded.sorted { $0.startedAt > $1.startedAt }
        } catch { self.error = DictationError.storage.localizedDescription }
    }

    func meeting(_ id: UUID) -> Meeting? { meetings.first { $0.id == id } }

    /// Inserts or replaces, keeping newest first, and writes the file.
    func save(_ meeting: Meeting) {
        if let index = meetings.firstIndex(where: { $0.id == meeting.id }) { meetings[index] = meeting }
        else { meetings.insert(meeting, at: 0); meetings.sort { $0.startedAt > $1.startedAt } }
        write(meeting)
    }

    func rename(_ id: UUID, speaker: String, to name: String) {
        guard var meeting = meeting(id) else { return }
        meeting.rename(speaker: speaker, to: name)
        save(meeting)
    }

    func delete(_ id: UUID) {
        meetings.removeAll { $0.id == id }
        guard let url = url(id) else { return }
        do { if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.removeItem(at: url) }; error = nil }
        catch { self.error = DictationError.storage.localizedDescription }
    }

    /// Deletes every finished meeting; a meeting being recorded is kept.
    func deleteAll(except active: UUID? = nil) {
        for meeting in meetings where meeting.id != active { delete(meeting.id) }
    }

    private func url(_ id: UUID) -> URL? { folder?.appendingPathComponent(id.uuidString + ".json") }

    private func write(_ meeting: Meeting) {
        guard let url = url(meeting.id) else { return }
        do {
            try JSONEncoder.meetings.encode(meeting).write(to: url, options: [.atomic])
            try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
            error = nil
        } catch { self.error = DictationError.storage.localizedDescription }
    }
}

extension JSONEncoder {
    static var meetings: JSONEncoder {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.sortedKeys]
        return encoder
    }
}

extension JSONDecoder {
    static var meetings: JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return decoder
    }
}
