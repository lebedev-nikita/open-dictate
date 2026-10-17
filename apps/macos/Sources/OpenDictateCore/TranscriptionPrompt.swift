import Foundation

public enum TranscriptionPrompt {
    // A shared UTF-8 storage/editor bound, independent of model-specific API limits.
    public static let maximumBytes = 16_384
    private struct Defaults: Decodable {
        let base: String
        let russianOrthography: String
        let russianPunctuation: String
    }
    private static let defaults: Defaults = {
        guard let url = Bundle.module.url(forResource: "default-prompts", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let value = try? JSONDecoder().decode(Defaults.self, from: data) else {
            preconditionFailure("Missing bundled transcription defaults")
        }
        return value
    }()
    public static func defaultText(languages: [String]) -> String {
        var parts = [defaults.base]
        if languages.contains("ru") && !languages.contains("uk") { parts.append(defaults.russianOrthography) }
        if languages.contains("ru") { parts.append(defaults.russianPunctuation) }
        return parts.joined(separator: "\n")
    }
    public static func isValid(_ text: String) -> Bool { text.utf8.count <= maximumBytes }
}
