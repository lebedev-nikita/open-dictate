import Foundation
import OpenDictateCore

// Generate in memory from the application implementation; never save request bodies.
let settings = PostProcessingSettings(enabled: true, formatValues: true,
    formats: ValueFormatting.systemDefaults(locale: Locale(identifier: "ru_RU")))
let templates: [String: Any] = [
    "ru": OpenAIRequest.postProcessing(source: "", settings: settings, languages: ["ru"]),
    "ru_en": OpenAIRequest.postProcessing(source: "", settings: settings, languages: ["ru", "en"])
]
FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject: templates, options: [.sortedKeys]))
