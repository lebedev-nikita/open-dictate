import Foundation

/// Wire format shared with Android. New sections add keys without replacing old clients' data.
public struct SettingsSyncDocument: Codable, Equatable, Sendable {
    public struct Entry: Codable, Equatable, Sendable {
        private enum CodingKeys: String, CodingKey { case value, modifiedAt, deviceId }
        public var value: String?
        public var modifiedAt: Int64
        public var deviceId: String
        public init(value: String?, modifiedAt: Int64, deviceId: String) {
            self.value = value; self.modifiedAt = modifiedAt; self.deviceId = deviceId
        }
        public init(from decoder: Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            guard container.contains(.value) else { throw SyncFormatError.invalid }
            value = try container.decodeIfPresent(String.self, forKey: .value)
            modifiedAt = try container.decode(Int64.self, forKey: .modifiedAt)
            deviceId = try container.decode(String.self, forKey: .deviceId)
        }
        public func encode(to encoder: Encoder) throws {
            var container = encoder.container(keyedBy: CodingKeys.self)
            if let value { try container.encode(value, forKey: .value) }
            else { try container.encodeNil(forKey: .value) }
            try container.encode(modifiedAt, forKey: .modifiedAt)
            try container.encode(deviceId, forKey: .deviceId)
        }

    }
    public var schemaVersion: Int { syncEntries.values.contains { $0.value == nil } ? 2 : 1 }
    private enum CodingKeys: String, CodingKey { case schemaVersion, entries }
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let version = try container.decode(Int.self, forKey: .schemaVersion)
        entries = try container.decode([String: Entry].self, forKey: .entries)
        guard [1, 2].contains(version), entries.allSatisfy({ $0.value.value != nil || (version == 2 && $0.key == "transcriptionPrompt") }) else { throw SyncFormatError.invalid }
    }
    public var entries: [String: Entry] = [:]
    public init() {}
    public static let keys = ["dictionary", "mode", "liveModel", "accurateModel", "textModel", "wordReplacements", "wordReplacementsEnabled", "postprocessing"]
    // Retired fields are decoded for compatibility, then removed from journals and replicas.
    private static let retiredKeys: Set<String> = ["accuratePunctuationEnabled", "transcriptionPrompt"]
    private var syncEntries: [String: Entry] { entries.filter { !Self.retiredKeys.contains($0.key) } }
    public static let maximumBytes = 1_048_576

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(schemaVersion, forKey: .schemaVersion)
        try container.encode(syncEntries, forKey: .entries)
    }

    public static func decode(_ data: Data) throws -> Self {
        guard data.count <= maximumBytes else { throw SyncFormatError.invalid }
        var document = try JSONDecoder().decode(Self.self, from: data)
        guard document.entries.count <= 1024,
              document.entries.allSatisfy({ $0.key.utf8.count <= 128 && ($0.value.value?.utf8.count ?? 0) <= 262_144 &&
                  $0.value.modifiedAt >= 0 && $0.value.modifiedAt < Int64.max - 1 && $0.value.deviceId.utf8.count <= 128 })
        else { throw SyncFormatError.invalid }
        document.entries = document.syncEntries
        return document
    }

    public mutating func merge(_ other: Self) {
        entries = syncEntries
        for (key, incoming) in other.syncEntries {
            guard let existing = entries[key] else { entries[key] = incoming; continue }
            if incoming.modifiedAt > existing.modifiedAt ||
                (incoming.modifiedAt == existing.modifiedAt && (incoming.deviceId > existing.deviceId ||
                    (incoming.deviceId == existing.deviceId && Self.valuePrecedes(existing.value, incoming.value)))) {
                entries[key] = incoming
            }
        }
    }

    private static func valuePrecedes(_ left: String?, _ right: String?) -> Bool {
        guard let right else { return false }
        guard let left else { return true }
        return left < right
    }

    public mutating func record(_ values: [String: String?], deviceId: String, now: Int64, seed: Bool = false) {
        entries = syncEntries
        let clock = max(now, (entries.values.map(\.modifiedAt).max() ?? 0) + 1)
        for key in Self.keys {
            guard let value = values[key],
                  entries[key] == nil || entries[key]?.value != value else { continue }
            entries[key] = Entry(value: value, modifiedAt: seed ? 0 : clock, deviceId: seed ? "" : deviceId)
        }
    }

    public enum ConnectionSource: Sendable { case cloud, local }

    /// Compare user-visible synced values, excluding timestamps and unknown future settings.
    public func hasSameSettings(as other: Self) -> Bool {
        Self.keys.allSatisfy { entries[$0]?.value == other.entries[$0]?.value }
    }

    public func needsConnectionChoice(with remote: Self) -> Bool {
        Self.keys.contains { key in
            remote.entries[key].map { $0.value != entries[key]?.value } ?? false
        }
    }

    /// An explicit source choice overrides offline clocks, preserving unknown future entries.
    public func resolvingConnection(with remote: Self, source: ConnectionSource,
                                    deviceId: String, now: Int64) -> Self {
        var result = self
        result.merge(remote)
        let preferred = source == .cloud ? remote : self
        let values = preferred.entries.mapValues(\.value)
        // Include losing entries too, so stale replicas cannot undo the explicit choice.
        let latest = max(syncEntries.values.map(\.modifiedAt).max() ?? 0,
                         remote.syncEntries.values.map(\.modifiedAt).max() ?? 0)
        result.record(values, deviceId: deviceId, now: max(now, latest + 1))
        result.promoteSeeds(deviceId: deviceId, now: now)
        return result
    }

    /// Defaults on a newly linked device must not supersede an existing cloud preference.
    public mutating func promoteSeeds(deviceId: String, now: Int64) {
        entries = syncEntries
        let clock = max(now, (entries.values.map(\.modifiedAt).max() ?? 0) + 1)
        for key in Self.keys where entries[key]?.modifiedAt == 0 {
            entries[key]?.modifiedAt = clock; entries[key]?.deviceId = deviceId
        }
    }
}

public enum SyncFormatError: Error { case invalid }
