import XCTest
import OpenDictateCore
@testable import OpenDictate

final class RetiredTranscriptionPromptTests: XCTestCase {
    @MainActor func testOldPromptsAreRemovedWithoutChangingPostProcessing() throws {
        let suite = "retired-prompt-\(UUID().uuidString)", defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.set("Old instructions", forKey: "customTranscriptionPrompt")
        defaults.set(true, forKey: "transcriptionPromptConfigured")
        let preferences = Preferences(defaults: defaults)
        XCTAssertNil(defaults.object(forKey: "customTranscriptionPrompt"))
        XCTAssertNil(defaults.object(forKey: "transcriptionPromptConfigured"))
        let settings = preferences.postProcessing
        var remote = SettingsSyncDocument()
        remote.entries["transcriptionPrompt"] = .init(value: "Ignored", modifiedAt: 500, deviceId: "old")
        remote.entries["dictionary"] = .init(value: "Updated", modifiedAt: 500, deviceId: "old")
        XCTAssertNil(try preferences.mergeSync(remote).entries["transcriptionPrompt"])
        XCTAssertEqual(preferences.dictionary, "Updated")
        XCTAssertEqual(preferences.postProcessing, settings)
        let body = String(decoding: OpenAIRequest.multipart(wav: Data(), context: preferences.context, boundary: "test"), as: UTF8.self)
        XCTAssertFalse(body.contains("name=\"prompt\""))
    }
}
