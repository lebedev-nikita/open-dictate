import Foundation
import Combine
import OpenDictateCore

@MainActor
final class ReplacementStore: ObservableObject {
    enum Status { case local, invalid, storageError }
    @Published private(set) var document: ReplacementDocument
    @Published var enabled: Bool {
        didSet { defaults.set(enabled, forKey: "wordReplacementEnabled"); onChange?() }
    }
    @Published private(set) var status: Status = .local
    private let defaults: UserDefaults
    private var storageAvailable = true
    var onChange: (() -> Void)?
    var rules: [WordReplacement] { document.rules.sorted { $0.replacement.localizedCaseInsensitiveCompare($1.replacement) == .orderedAscending } }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        enabled = defaults.object(forKey: "wordReplacementEnabled") as? Bool ?? true
        if let data = defaults.data(forKey: "wordReplacementDocument") {
            if let saved = try? JSONDecoder().decode(ReplacementDocument.self, from: data), saved.isValid { document = saved }
            else { document = ReplacementDocument(); storageAvailable = false; status = .storageError }
        } else { document = ReplacementDocument() }
    }
    func engine() -> WordReplacementEngine { WordReplacementEngine(rules: enabled ? document.rules : []) }
    @discardableResult func save(id: String? = nil, source: String, replacement: String) -> Bool {
        let source = source.trimmingCharacters(in: .whitespacesAndNewlines).precomposedStringWithCanonicalMapping
        let replacement = replacement.trimmingCharacters(in: .whitespacesAndNewlines)
        guard storageAvailable else { status = .storageError; return false }
        guard !rules.contains(where: { $0.id != id && $0.source.compare(source, options: [.caseInsensitive, .literal]) == .orderedSame }) else { status = .invalid; return false }
        let previous = document.rules.first { $0.id == id }
        let rule = WordReplacement(id: id ?? UUID().uuidString.lowercased(), source: source, replacement: replacement,
                                   enabled: previous?.enabled ?? true)
        var next = document; next.rules.removeAll { $0.id == rule.id }; next.rules.append(rule)
        guard next.isValid else { status = .invalid; return false }
        persist(next); status = .local; return true
    }
    func update(_ rule: WordReplacement, enabled: Bool) {
        guard storageAvailable else { return }
        var next = document
        guard let index = next.rules.firstIndex(where: { $0.id == rule.id }) else { return }
        next.rules[index].enabled = enabled
        persist(next); status = .local
    }
    func delete(_ rule: WordReplacement) {
        guard storageAvailable else { return }
        var next = document; next.rules.removeAll { $0.id == rule.id }; persist(next); status = .local
    }
    private func persist(_ next: ReplacementDocument) {
        guard let data = try? JSONEncoder().encode(next) else { status = .storageError; return }
        defaults.set(data, forKey: "wordReplacementDocument"); document = next
        onChange?()
    }
    var syncValues: [String: String] {
        get throws {
            guard storageAvailable else { throw SyncFormatError.invalid }
            let data = defaults.data(forKey: "wordReplacementDocument") ?? Data(#"{"schemaVersion":1,"rules":[]}"#.utf8)
            let saved = try JSONDecoder().decode(ReplacementDocument.self, from: data)
            guard saved.isValid, let json = String(data: data, encoding: .utf8) else { throw SyncFormatError.invalid }
            return ["wordReplacements": json, "wordReplacementsEnabled": enabled ? "true" : "false"]
        }
    }
    /// Validate before any preference is changed. Preserve incoming JSON to avoid restamping it.
    func validateSync(json: String?) throws {
        guard storageAvailable else { throw SyncFormatError.invalid }
        if let json {
            let document = try JSONDecoder().decode(ReplacementDocument.self, from: Data(json.utf8))
            guard document.isValid else { throw SyncFormatError.invalid }
        }
    }
    func applySync(json: String?, enabled nextEnabled: Bool?) throws {
        try validateSync(json: json)
        if let json {
            let data = Data(json.utf8)
            let next = try JSONDecoder().decode(ReplacementDocument.self, from: data)
            defaults.set(data, forKey: "wordReplacementDocument"); document = next
        }
        if let nextEnabled, enabled != nextEnabled { enabled = nextEnabled }
        status = .local
    }
}
