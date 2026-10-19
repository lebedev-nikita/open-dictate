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
    fileprivate func instruction(_ format: ValueFormat) -> [String: String] {
        ["example": format.example, "reference": definition.reference, "rule": definition.rule, "type": rawValue]
    }
}

public struct ValueFormatting: Equatable, Sendable {
    public var entries: [String: ValueFormat]
    public init(entries: [String: ValueFormat]) { self.entries = entries }
    public subscript(_ kind: ValueKind) -> ValueFormat {
        get { entries[kind.rawValue]! }
        set { entries[kind.rawValue] = newValue }
    }
    public var isValid: Bool {
        Set(entries.keys) == Set(ValueKind.allCases.map(\.rawValue)) && entries.values.allSatisfy(\.isValid)
    }
    public var hasEnabledTypes: Bool { entries.values.contains { $0.enabled } }
    var instruction: String {
        let examples = ValueKind.allCases.filter { self[$0].enabled }.map { $0.instruction(self[$0]) }
        return PostProcessingRules.root.valuesInstructions + "\n\n" + canonicalJSON(examples)
    }
    var jsonObject: [String: Any] { entries.mapValues { ["enabled": $0.enabled, "example": $0.example] as [String: Any] } }
    static func decode(_ object: Any) throws -> Self {
        let data = try JSONSerialization.data(withJSONObject: object)
        let entries = try JSONDecoder().decode([String: ValueFormat].self, from: data)
        let result = Self(entries: entries)
        guard result.isValid else { throw SyncFormatError.invalid }
        return result
    }
    /// Capture the system region on first use; synchronize the resulting examples, not a device-dependent default.
    public static func systemDefaults(locale: Locale = .current) -> Self {
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
        let result = Self(entries: Dictionary(uniqueKeysWithValues: zip(ValueKind.allCases, values).map { ($0.rawValue, ValueFormat(example: $1)) }))
        precondition(result.isValid)
        return result
    }
}

enum PostProcessingRules {
    struct Definition: Decodable { let id: String; let en: String; let ru: String; let reference: String; let examples: [String]; let rule: String }
    struct Root: Decodable { let punctuation: String; let punctuationWithValues: String; let valuesInstructions: String; let types: [Definition] }
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
