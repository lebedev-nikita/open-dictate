import Foundation

/// Shared reference phrases, independent of OS spellout support and interface/region preferences.
enum PostProcessingLanguages {
    private struct Example: Decodable { let spoken: String; let value: String }
    private struct Definition: Decodable { let language: String; let name: String; let scales: [Example]; let examples: [Example] }
    private struct Root: Decodable { let heading: String; let languages: [Definition] }
    private static let root: Root = {
        guard let url = CoreResources.url(forResource: "postprocessing-languages", withExtension: "json"),
              let data = try? Data(contentsOf: url), let value = try? JSONDecoder().decode(Root.self, from: data) else {
            preconditionFailure("Missing post-processing language examples")
        }
        return value
    }()
    private static let definitions = Dictionary(uniqueKeysWithValues: root.languages.map { ($0.language, $0) })
    static var supportedCodes: Set<String> { Set(definitions.keys) }

    private static func render(_ examples: [Example]) -> String {
        examples.map {
            let quoted = canonicalJSON([$0.spoken]).dropFirst().dropLast()
            return "\(quoted) = \($0.value)"
        }.joined(separator: "; ")
    }

    static func instruction(languages: [String]) -> String {
        let blocks = Set(languages).sorted().compactMap { code -> String? in
            guard let definition = definitions[code] else { return nil }
            return "[\(code)] \(definition.name). Scales: " + render(definition.scales) + ". Examples: " + render(definition.examples)
        }
        return blocks.isEmpty ? "" : root.heading + "\n" + blocks.joined(separator: "\n")
    }
}
