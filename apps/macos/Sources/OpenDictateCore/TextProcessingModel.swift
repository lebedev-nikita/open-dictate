public enum TextProcessingModel {
    public static let id = "gpt-6-luna"
    public static let defaults = [id]
    public static func title(_ id: String) -> String { id == Self.id ? "GPT-6 Luna" : id }
}
