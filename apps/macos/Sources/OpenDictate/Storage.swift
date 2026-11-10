import Foundation
import Combine
import Security
import OpenDictateCore

@MainActor
final class Preferences: ObservableObject {
    private let defaults: UserDefaults
    let replacements: ReplacementStore
    @Published var theme: AppTheme { didSet { defaults.set(theme.rawValue, forKey: "theme") } }
    @Published var mode: DictationMode { didSet { defaults.set(mode.rawValue, forKey: "mode"); recordSyncChange() } }
    @Published var speechLanguages: Set<String> {
        didSet { defaults.set(SpeechLanguage.normalize(speechLanguages), forKey: "speechLanguages") }
    }
    @Published var interfaceLanguage: String { didSet { defaults.set(interfaceLanguage, forKey: "interfaceLanguage") } }
    @Published var dictionary: String { didSet { defaults.set(dictionary, forKey: "dictionary"); recordSyncChange() } }
    @Published var postProcessing: PostProcessingSettings {
        didSet {
            if let encoded = try? postProcessing.encoded() { defaults.set(encoded, forKey: "postprocessingOptions") }
            recordSyncChange()
        }
    }
    @Published var saveHistory: Bool { didSet { defaults.set(saveHistory, forKey: "saveHistory") } }
    @Published var shortcuts: ShortcutBindings {
        didSet { if let data = try? JSONEncoder().encode(shortcuts) { defaults.set(data, forKey: "shortcuts") } }
    }
    @Published var timeout: Int { didSet { defaults.set(timeout, forKey: "timeout") } }
    @Published var excludedApps: [String] { didSet { defaults.set(excludedApps, forKey: "excludedApps") } }
    @Published var showStatus: Bool { didSet { defaults.set(showStatus, forKey: "showStatus") } }
    @Published var indicatorStyle: RecordingIndicatorStyle {
        didSet { defaults.set(indicatorStyle.rawValue, forKey: "indicatorStyle") }
    }

    @Published var textProcessingModel: String { didSet { defaults.set(textProcessingModel, forKey: "textProcessingModel"); recordSyncChange() } }
    // Retain the sync key and history tiers for compatibility, but never enable Fast.
    let textProcessingServiceTier: TextProcessingServiceTier = .standard
    var textProcessingSelection: String {
        get { TextProcessingModel.selection(model: textProcessingModel, tier: textProcessingServiceTier) }
        set {
            textProcessingModel = TextProcessingModel.model(for: newValue)
        }
    }
    @Published var liveModel: String { didSet { defaults.set(liveModel, forKey: "liveModel"); recordSyncChange() } }
    @Published var accurateModel: String { didSet { defaults.set(accurateModel, forKey: "accurateModel"); recordSyncChange() } }
    @Published var driveSyncEnabled: Bool { didSet { defaults.set(driveSyncEnabled, forKey: "driveSyncEnabled") } }
    var driveConnectionPending: Bool {
        get { defaults.bool(forKey: "driveConnectionPending") }
        set { defaults.set(newValue, forKey: "driveConnectionPending") }
    }
    @Published var driveClientID: String { didSet { defaults.set(driveClientID, forKey: "driveClientID") } }
    let syncDeviceID: String
    private var applyingSync = false
    let syncChanges = PassthroughSubject<Void, Never>()
    private(set) var syncStorageFailed = false

    init(defaults: UserDefaults = .standard, replacements: ReplacementStore? = nil) {
        self.defaults = defaults
        self.replacements = replacements ?? ReplacementStore(defaults: defaults)
        theme = AppTheme(rawValue: defaults.string(forKey: "theme") ?? "") ?? .dark
        syncDeviceID = defaults.string(forKey: "syncDeviceID") ?? UUID().uuidString.lowercased()
        defaults.set(syncDeviceID, forKey: "syncDeviceID")
        textProcessingModel = TextProcessingModel.model(for: defaults.string(forKey: "textProcessingModel") ?? TextProcessingModel.id)
        liveModel = defaults.string(forKey: "liveModel") ?? DictationMode.live.model
        accurateModel = defaults.string(forKey: "accurateModel") ?? DictationMode.accurate.model
        driveSyncEnabled = defaults.bool(forKey: "driveSyncEnabled")
        driveClientID = defaults.string(forKey: "driveClientID") ?? ""
        mode = DictationMode(rawValue: defaults.string(forKey: "mode") ?? "") ?? .live
        let languages = SpeechLanguage.restore(
            stored: defaults.stringArray(forKey: "speechLanguages"),
            legacy: defaults.string(forKey: "speechLanguage"))
        speechLanguages = Set(languages)
        defaults.set(languages, forKey: "speechLanguages")
        interfaceLanguage = defaults.string(forKey: "interfaceLanguage") ?? "auto"
        dictionary = defaults.string(forKey: "dictionary") ?? "OpenDictate"
        defaults.removeObject(forKey: "customTranscriptionPrompt")
        defaults.removeObject(forKey: "transcriptionPromptConfigured")
        postProcessing = defaults.string(forKey: "postprocessingOptions").flatMap { try? PostProcessingSettings.decode($0) }
            ?? defaults.string(forKey: "postprocessing").flatMap { try? PostProcessingSettings.migrateLegacy($0) }
            ?? PostProcessingSettings(enabled: defaults.bool(forKey: "accuratePunctuationEnabled"))
        defaults.removeObject(forKey: "postprocessing")
        saveHistory = defaults.object(forKey: "saveHistory") as? Bool ?? true
        if let data = defaults.data(forKey: "shortcuts"),
           let saved = try? JSONDecoder().decode(ShortcutBindings.self, from: data), saved.isValid {
            shortcuts = saved
        } else {
            shortcuts = .legacy(modifiers: defaults.string(forKey: "shortcutModifiers") ?? "option",
                                key: defaults.string(forKey: "shortcutKey") ?? "space")
        }
        defaults.removeObject(forKey: "textModel")
        timeout = [30, 60, 120].contains(defaults.integer(forKey: "timeout")) ? defaults.integer(forKey: "timeout") : 60
        excludedApps = defaults.stringArray(forKey: "excludedApps") ?? []
        showStatus = defaults.object(forKey: "showStatus") as? Bool ?? true
        indicatorStyle = RecordingIndicatorStyle(rawValue: defaults.string(forKey: "indicatorStyle") ?? "") ?? .compact
        defaults.set(try? postProcessing.encoded(), forKey: "postprocessingOptions")
        defaults.removeObject(forKey: "accuratePunctuationEnabled")
        do {
            var seed = try defaults.data(forKey: "syncDocument").map(SettingsSyncDocument.decode) ?? SettingsSyncDocument()
            seed.record(try syncValues.filter { seed.entries[$0.key] == nil }, deviceId: syncDeviceID, now: 0, seed: true)
            defaults.set(try JSONEncoder().encode(seed), forKey: "syncDocument")
        } catch { syncStorageFailed = true }
        self.replacements.onChange = { [weak self] in
            self?.recordSyncChange()
            self?.objectWillChange.send()
        }
    }

    private var syncValues: [String: String?] {
        get throws {
            var values: [String: String?] = ["dictionary": dictionary, "mode": mode.rawValue, "liveModel": liveModel,
                          "accurateModel": accurateModel, "postprocessingRules": try postProcessing.encodedRules(), "textProcessingModel": textProcessingModel, "textProcessingServiceTier": textProcessingServiceTier.rawValue]
            values.merge(try replacements.syncValues) { _, new in new }
            guard values.values.allSatisfy({ ($0?.utf8.count ?? 0) <= 262_144 }) else { throw SyncFormatError.invalid }
            return values
        }
    }
    func syncDocument() throws -> SettingsSyncDocument {
        _ = try syncValues
        guard let data = defaults.data(forKey: "syncDocument") else { throw SyncFormatError.invalid }
        return try SettingsSyncDocument.decode(data)
    }
    private func recordSyncChange() {
        guard !applyingSync else { return }
        do {
            var document = try defaults.data(forKey: "syncDocument").map(SettingsSyncDocument.decode) ?? SettingsSyncDocument()
            let previous = document
            document.record(try syncValues, deviceId: syncDeviceID, now: Int64(Date().timeIntervalSince1970 * 1000))
            let data = try JSONEncoder().encode(document)
            _ = try SettingsSyncDocument.decode(data)
            defaults.set(data, forKey: "syncDocument"); syncStorageFailed = false
            if document != previous { syncChanges.send() }
        } catch { syncStorageFailed = true }
    }
    func mergeSync(_ remote: SettingsSyncDocument,
                   source: SettingsSyncDocument.ConnectionSource? = nil) throws -> SettingsSyncDocument {
        var merged = try syncDocument()
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        if let source {
            merged = merged.resolvingConnection(with: remote, source: source, deviceId: syncDeviceID, now: now)
        } else {
            merged.merge(remote)
            merged.promoteSeeds(deviceId: syncDeviceID, now: now)
        }
        try validateSync(merged)
        let replacementJSON = merged.entries["wordReplacements"]?.value
        let replacementEnabled = merged.entries["wordReplacementsEnabled"]?.value
        let encoded = try JSONEncoder().encode(merged)
        applyingSync = true
        defer { applyingSync = false }
        try replacements.applySync(json: replacementJSON, enabled: replacementEnabled.map { $0 == "true" })
        if let value = merged.entries["dictionary"]?.value, dictionary != value { dictionary = value }
        if let value = merged.entries["mode"]?.value, let next = DictationMode(rawValue: value), mode != next { mode = next }
        if let value = merged.entries["liveModel"]?.value, liveModel != value { liveModel = value }
        if let value = merged.entries["accurateModel"]?.value, accurateModel != value { accurateModel = value }
        if let value = merged.entries["textProcessingModel"]?.value {
            let model = TextProcessingModel.model(for: value)
            if textProcessingModel != model { textProcessingModel = model }
        }
        if let value = merged.entries["postprocessingRules"]?.value {
            let next = try postProcessing.applyingRules(value)
            if postProcessing != next { postProcessing = next }
        }
        defaults.set(encoded, forKey: "syncDocument")
        return merged
    }

    func validateSync(_ merged: SettingsSyncDocument) throws {
        if let value = merged.entries["postprocessingRules"]?.value { _ = try PostProcessingSettings().applyingRules(value) }
        if let value = merged.entries["mode"]?.value, DictationMode(rawValue: value) == nil { throw SyncFormatError.invalid }
        for key in ["liveModel", "accurateModel", "textProcessingModel"] {
            if let value = merged.entries[key]?.value,
               value.range(of: "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$", options: .regularExpression) == nil { throw SyncFormatError.invalid }
        }
        if let value = merged.entries["textProcessingServiceTier"]?.value,
           TextProcessingServiceTier(rawValue: value) == nil { throw SyncFormatError.invalid }
        let replacementJSON = merged.entries["wordReplacements"]?.value
        try replacements.validateSync(json: replacementJSON)
        let replacementEnabled = merged.entries["wordReplacementsEnabled"]?.value
        guard replacementEnabled == nil || replacementEnabled == "true" || replacementEnabled == "false" else { throw SyncFormatError.invalid }
        let encoded = try JSONEncoder().encode(merged)
        _ = try SettingsSyncDocument.decode(encoded)
    }

    var isRussian: Bool {
        interfaceLanguage == "ru" || (interfaceLanguage == "auto" && Locale.preferredLanguages.first?.hasPrefix("ru") == true)
    }
    func t(_ english: String, _ russian: String) -> String { isRussian ? russian : english }
    var context: TranscriptionContext {
        var context = TranscriptionContext(languages: SpeechLanguage.normalize(speechLanguages),
                                           dictionary: DictionaryTerms.normalize(dictionary))
        context.liveModel = liveModel; context.accurateModel = accurateModel
        return context
    }
    var shortcutLabel: String { shortcuts.dictate.label }
    var editShortcutLabel: String { shortcuts.transform.label }
}

enum APIKeyStore {
    static func load() throws -> String? {
        try CredentialVault.shared.read(.apiKey).flatMap { String(data: $0, encoding: .utf8) }
    }
    static func save(_ key: String) throws {
        let value = key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty, !value.contains(where: { $0.isWhitespace }) else { throw DictationError.missingKey }
        try CredentialVault.shared.write(Data(value.utf8), to: .apiKey)
    }
    static func delete() throws {
        try CredentialVault.shared.delete(.apiKey)
    }
}

@MainActor
final class HistoryStore: ObservableObject {
    @Published private(set) var entries = [HistoryEntry]()
    @Published private(set) var error: String?
    private let url: URL?
    init(inMemory: Bool = false, fileURL: URL? = nil) {
        if inMemory { url = nil; return }
        let historyURL = fileURL ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("OpenDictate/history.json")
        let folder = historyURL.deletingLastPathComponent()
        url = historyURL
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true,
                                                    attributes: [.posixPermissions: 0o700])
            if FileManager.default.fileExists(atPath: historyURL.path) {
                entries = try JSONDecoder().decode([HistoryEntry].self, from: Data(contentsOf: historyURL))
            }
        } catch { self.error = DictationError.storage.localizedDescription }
    }
    func add(_ text: String, mode: String, transcriptionMillis: Int64? = nil, postProcessingMillis: Int64? = nil,
             transcriptionModelID: String? = nil, postProcessingModelID: String? = nil, postProcessingServiceTier: String? = nil) {
        guard !text.isEmpty else { return }
        entries.insert(HistoryEntry(text: text, mode: mode,
            transcriptionMillis: transcriptionMillis, postProcessingMillis: postProcessingMillis,
            transcriptionModelID: transcriptionModelID, postProcessingModelID: postProcessingModelID,
            postProcessingServiceTier: postProcessingServiceTier), at: 0)
        entries = Array(entries.prefix(500)); persist()
    }
    func delete(_ id: UUID) { entries.removeAll { $0.id == id }; persist() }
    func clear() { entries.removeAll(); persist() }
    private func persist() {
        guard let url else { return }
        do {
            try JSONEncoder().encode(entries).write(to: url, options: [.atomic])
            try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
            error = nil
        } catch { self.error = DictationError.storage.localizedDescription }
    }
}
