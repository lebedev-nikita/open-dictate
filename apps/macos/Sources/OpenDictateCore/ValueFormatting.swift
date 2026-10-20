import Foundation

public struct ValueFormat: Codable, Equatable, Sendable {
    public var enabled: Bool
    public var example: String
    public init(enabled: Bool = true, example: String) { self.enabled = enabled; self.example = example }
    public var isValid: Bool {
        (1...96).contains(example.utf16.count) && example.unicodeScalars.contains(where: { [.decimalNumber, .letterNumber, .otherNumber].contains($0.properties.generalCategory) }) &&
        !example.unicodeScalars.contains(where: { $0.value < 32 || (127...159).contains($0.value) })
    }
}

public enum ValueKind: String, Codable, CaseIterable, Sendable {
    case integer, decimal, date, time, duration, percent, currency, measurement, range, fraction
    private var definition: PostProcessingRules.Definition { PostProcessingRules.root.types.first { $0.id == rawValue }! }
    public func title(russian: Bool) -> String { russian ? definition.ru : definition.en }
    public var examples: [String] { definition.examples }
    public func source(russian: Bool) -> String { russian ? definition.sourceRu : definition.sourceEn }
    fileprivate func instruction(_ format: ValueFormat) -> [String: String] {
        ["example": format.example, "reference": definition.reference, "rule": definition.rule, "type": rawValue]
    }
}

public struct ValueFormatRule: Codable, Equatable, Sendable, Identifiable {
    public var kind: ValueKind
    /// nil applies to every input language; a specific language overrides it.
    public var language: String?
    public var example: String
    public init(kind: ValueKind, language: String? = nil, example: String) {
        self.kind = kind; self.language = language; self.example = example
    }
    public var id: String { kind.rawValue + ":" + (language ?? "*") }
    public var isValid: Bool { ValueFormat(example: example).isValid && (language == nil || PostProcessingLanguages.supportedCodes.contains(language!)) }
    public func languageTitle(russian: Bool) -> String {
        guard let language else { return russian ? "Все языки" : "All languages" }
        let locale = Locale(identifier: russian ? "ru" : "en")
        return (locale.localizedString(forLanguageCode: language) ?? language).capitalized(with: locale)
    }
}

public struct ValueFormatting: Equatable, Sendable {
    public var entries: [String: ValueFormat]
    /// nil denotes the previous fixed per-type format; decoded without losing its styles.
    public var customRules: [ValueFormatRule]?
    public var languageLocales: [String: String]?
    public init(entries: [String: ValueFormat], customRules: [ValueFormatRule]? = nil, languageLocales: [String: String]? = nil) {
        self.entries = entries; self.customRules = customRules; self.languageLocales = languageLocales
    }
    public var rules: [ValueFormatRule] {
        customRules ?? ValueKind.allCases.map { ValueFormatRule(kind: $0, example: self[$0].example) }
    }
    public func sortedRules(russian: Bool) -> [ValueFormatRule] {
        rules.sorted { a, b in
            if a.kind != b.kind { return ValueKind.allCases.firstIndex(of: a.kind)! < ValueKind.allCases.firstIndex(of: b.kind)! }
            if a.language == nil || b.language == nil { return a.language == nil && b.language != nil }
            let order = a.languageTitle(russian: russian).compare(b.languageTitle(russian: russian), options: [.caseInsensitive, .numeric], locale: Locale(identifier: russian ? "ru" : "en"))
            return order == .orderedSame ? a.id < b.id : order == .orderedAscending
        }
    }
    public mutating func setRule(_ rule: ValueFormatRule, replacing id: String? = nil) {
        customRules = rules.filter { $0.id != rule.id && $0.id != id } + [rule]
    }
    public mutating func removeRule(id: String) { customRules = rules.filter { $0.id != id } }
    public static var supportedLanguages: [String] { PostProcessingLanguages.supportedCodes.sorted() }
    public subscript(_ kind: ValueKind) -> ValueFormat {
        get { entries[kind.rawValue]! }
        set { entries[kind.rawValue] = newValue }
    }
    public var isValid: Bool {
        Set(entries.keys) == Set(ValueKind.allCases.map(\.rawValue)) && entries.values.allSatisfy(\.isValid) &&
        (customRules == nil || (customRules!.count <= 64 && customRules!.allSatisfy(\.isValid) && Set(customRules!.map(\.id)).count == customRules!.count)) &&
        (languageLocales == nil || (customRules != nil && Set(languageLocales!.keys).isSubset(of: PostProcessingLanguages.supportedCodes) && languageLocales!.values.allSatisfy { $0.count <= 35 && $0.range(of: "^[A-Za-z]{2,3}([_-][A-Za-z0-9]{2,8})*$", options: .regularExpression) != nil })) && canonicalJSON(jsonObject).utf8.count <= PostProcessingSettings.maximumPromptBytes - 256
    }
    public var hasEnabledTypes: Bool { entries.values.contains { $0.enabled } }
    var instruction: String { instruction(languages: []) }
    func instruction(languages: [String]) -> String {
        let active = ValueKind.allCases.filter { self[$0].enabled }
        guard customRules != nil else {
            return PostProcessingRules.root.valuesInstructions + "\n\n" + canonicalJSON(active.map { $0.instruction(self[$0]) })
        }
        let globals = Dictionary(uniqueKeysWithValues: rules.filter { $0.language == nil }.map { ($0.kind, $0.example) })
        let scopes = Set(languages.isEmpty ? rules.compactMap(\.language) : languages).intersection(PostProcessingLanguages.supportedCodes).sorted()
        let defaults = Dictionary(uniqueKeysWithValues: scopes.map { ($0, Self.systemDefaults(locale: Locale(identifier: languageLocales?[$0] ?? $0), automatic: false)) })
        func style(_ kind: ValueKind, _ language: String, _ example: String) -> [String: String] {
            var item = kind.instruction(ValueFormat(example: example))
            item["language"] = language; item["source_value"] = kind.source(russian: false)
            return item
        }
        var styles: [[String: String]] = []
        for kind in active {
            styles.append(style(kind, "*", globals[kind] ?? self[kind].example))
            for language in scopes {
                let specific = rules.first { $0.kind == kind && $0.language == language }
                if let example = specific?.example ?? (!languages.isEmpty && globals[kind] == nil ? defaults[language]![kind].example : nil) {
                    styles.append(style(kind, language, example))
                }
            }
        }
        return PostProcessingRules.root.valuesInstructions + "\n\n" + PostProcessingRules.root.styleSelection + "\n\n" + canonicalJSON(styles)
    }
    var jsonObject: [String: Any] {
        var result: [String: Any] = entries.mapValues { ["enabled": $0.enabled, "example": $0.example] as [String: Any] }
        if let customRules {
            result["customRules"] = customRules.sorted { $0.id < $1.id }.map {
                ["example": $0.example, "language": $0.language as Any? ?? NSNull(), "type": $0.kind.rawValue]
            }
        }
        if let languageLocales { result["languageLocales"] = languageLocales }
        return result
    }
    public static func decode(_ object: Any) throws -> Self {
        guard var raw = object as? [String: Any] else { throw SyncFormatError.invalid }
        let rawRules = raw.removeValue(forKey: "customRules"), rawLocales = raw.removeValue(forKey: "languageLocales")
        let data = try JSONSerialization.data(withJSONObject: raw)
        let entries = try JSONDecoder().decode([String: ValueFormat].self, from: data)
        var rules: [ValueFormatRule]?
        if let rawRules {
            guard let array = rawRules as? [[String: Any]], array.count <= 64 else { throw SyncFormatError.invalid }
            rules = try array.map { item in
                guard let type = item["type"] as? String, let kind = ValueKind(rawValue: type), let example = item["example"] as? String,
                      let lang = item["language"], lang is NSNull || lang is String else { throw SyncFormatError.invalid }
                return ValueFormatRule(kind: kind, language: lang as? String, example: example)
            }
        }
        guard rawLocales == nil || rawLocales is [String: String] else { throw SyncFormatError.invalid }
        let result = Self(entries: entries, customRules: rules, languageLocales: rawLocales as? [String: String])
        guard result.isValid else { throw SyncFormatError.invalid }
        return result
    }
    /// Capture the system region on first use; synchronize the resulting examples, not a device-dependent default.
    public static func systemDefaults(locale: Locale = .current, automatic: Bool = true) -> Self {
        func number(_ value: Double, fraction: Int = 0) -> String {
            let formatter = NumberFormatter(); formatter.locale = locale; formatter.numberStyle = .decimal
            formatter.minimumFractionDigits = fraction; formatter.maximumFractionDigits = fraction
            return formatter.string(from: NSNumber(value: value))!
        }
        var calendar = Calendar(identifier: .gregorian); calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        let date = calendar.date(from: DateComponents(year: 2026, month: 3, day: 5, hour: 14, minute: 5))!
        let dateFormat = DateFormatter(); dateFormat.locale = locale; dateFormat.calendar = calendar
        dateFormat.timeZone = calendar.timeZone; dateFormat.dateStyle = .short
        dateFormat.dateFormat = dateFormat.dateFormat.replacingOccurrences(of: "y+", with: "yyyy", options: .regularExpression)
        let timeFormat = DateFormatter(); timeFormat.locale = locale; timeFormat.timeZone = calendar.timeZone; timeFormat.timeStyle = .short
        let percent = NumberFormatter(); percent.locale = locale; percent.numberStyle = .percent; percent.maximumFractionDigits = 1
        let money = NumberFormatter(); money.locale = locale; money.numberStyle = .currency; money.currencyCode = "USD"; money.minimumFractionDigits = 2; money.maximumFractionDigits = 2
        let ru = locale.language.languageCode?.identifier == "ru"
        let values = [number(1234567), number(12345.67, fraction: 2), dateFormat.string(from: date), timeFormat.string(from: date),
                      ru ? "2 ч 15 мин" : "2 h 15 min", percent.string(from: 0.125)!, money.string(from: 1234.5)!,
                      number(1.5, fraction: 1) + (ru ? " кг" : " kg"), ru ? "5–10 км" : "5–10 km", "3/4"]
        let locales = automatic ? Dictionary(uniqueKeysWithValues: supportedLanguages.map { language -> (String, String) in
            let preferred = Locale.preferredLanguages.first { Locale(identifier: $0).language.languageCode?.identifier == language && Locale(identifier: $0).region != nil }
            let fallback = language == "en" ? "en_" + (locale.region?.identifier ?? "US") : language
            let chosen = Locale(identifier: preferred ?? (locale.language.languageCode?.identifier == language ? locale.identifier : fallback))
            return (language, language + (chosen.region.map { "_" + $0.identifier } ?? ""))
        }) : nil
        let result = Self(entries: Dictionary(uniqueKeysWithValues: zip(ValueKind.allCases, values).map { ($0.rawValue, ValueFormat(example: $1)) }), customRules: automatic ? [] : nil, languageLocales: locales)
        precondition(result.isValid)
        return result
    }
}

enum PostProcessingRules {
    struct Definition: Decodable { let id: String; let en: String; let ru: String; let reference: String; let examples: [String]; let rule: String; let sourceEn: String; let sourceRu: String }
    struct Root: Decodable { let punctuation: String; let punctuationWithValues: String; let valuesInstructions: String; let styleSelection: String; let types: [Definition] }
    static let root: Root = {
        guard let url = Bundle.module.url(forResource: "postprocessing-rules", withExtension: "json"),
              let data = try? Data(contentsOf: url), let result = try? JSONDecoder().decode(Root.self, from: data) else {
            preconditionFailure("Missing post-processing rules")
        }
        return result
    }()
}

func canonicalJSON(_ object: Any) -> String {
    String(decoding: try! JSONSerialization.data(withJSONObject: object, options: [.sortedKeys, .withoutEscapingSlashes]), as: UTF8.self)
}
