public enum TextProcessingServiceTier: String, Sendable {
    case standard = "default", fast
}

public enum TextProcessingModel {
    public static let id = "gpt-6-luna"
    public static let fastChoice = "gpt-6-luna:fast"
    public static let defaults = [id]
    public static func title(_ choice: String) -> String {
        choice == fastChoice ? "GPT-6 Luna Fast" : choice == id ? "GPT-6 Luna" : choice
    }
    public static func selection(model: String, tier: TextProcessingServiceTier) -> String {
        model == id && tier == .fast ? fastChoice : model
    }
    public static func model(for choice: String) -> String { choice == fastChoice ? id : choice }
    public static func tier(for choice: String) -> TextProcessingServiceTier { .standard }
}
