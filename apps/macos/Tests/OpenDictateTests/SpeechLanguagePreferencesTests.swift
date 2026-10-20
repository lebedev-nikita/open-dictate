import XCTest
@testable import OpenDictate
import OpenDictateCore

final class SpeechLanguagePreferencesTests: XCTestCase {
    func testMigrationPersistsAndDoesNotRestoreClearedLegacySelection() async {
        await MainActor.run {
            let domain = "com.opendictate.tests.languages.\(UUID().uuidString)"
            let defaults = UserDefaults(suiteName: domain)!
            defer { defaults.removePersistentDomain(forName: domain) }
            defaults.set("ru-en", forKey: "speechLanguage")
            let preferences = Preferences(defaults: defaults)
            XCTAssertEqual(preferences.speechLanguages, ["ru", "en"])
            XCTAssertEqual(defaults.stringArray(forKey: "speechLanguages"), ["en", "ru"])
            preferences.speechLanguages = []
            XCTAssertTrue(Preferences(defaults: defaults).speechLanguages.isEmpty)
            XCTAssertTrue(preferences.context.languages.isEmpty)
        }
    }

    func testMultilingualSelectionPersistsAndReachesBothProtocols() async throws {
        try await MainActor.run {
            let domain = "com.opendictate.tests.languages.\(UUID().uuidString)"
            let defaults = UserDefaults(suiteName: domain)!
            defer { defaults.removePersistentDomain(forName: domain) }
            let preferences = Preferences(defaults: defaults)
            preferences.speechLanguages = ["ka", "de", "en", "uk"]
            let reloaded = Preferences(defaults: defaults)
            XCTAssertEqual(reloaded.speechLanguages, preferences.speechLanguages)
            let context = reloaded.context
            XCTAssertEqual(context.languages, ["de", "en", "ka", "uk"])
            let event = OpenAIRequest.sessionUpdate(context: context)
            let session = try XCTUnwrap(event["session"] as? [String: Any])
            let audio = try XCTUnwrap(session["audio"] as? [String: Any])
            let input = try XCTUnwrap(audio["input"] as? [String: Any])
            let transcription = try XCTUnwrap(input["transcription"] as? [String: Any])
            XCTAssertEqual(transcription["languages"] as? [String], context.languages)
            let body = String(decoding: OpenAIRequest.multipart(wav: Data(), context: context, boundary: "test"), as: UTF8.self)
            for code in context.languages {
                XCTAssertTrue(body.contains("name=\"languages[]\"\r\n\r\n\(code)\r\n"))
            }
            preferences.speechLanguages.remove("de")
            XCTAssertEqual(Preferences(defaults: defaults).context.languages, ["en", "ka", "uk"])
            XCTAssertEqual(context.languages, ["de", "en", "ka", "uk"], "An active session keeps its captured selection")
        }
    }

    func testAutomaticOmitsHintsAndInvalidStoredCodesAreFiltered() async throws {
        try await MainActor.run {
            let domain = "com.opendictate.tests.languages.\(UUID().uuidString)"
            let defaults = UserDefaults(suiteName: domain)!
            defer { defaults.removePersistentDomain(forName: domain) }
            defaults.set(["unknown", "ru", "uk", "ru"], forKey: "speechLanguages")
            let preferences = Preferences(defaults: defaults)
            XCTAssertEqual(preferences.context.languages, ["ru", "uk"])
            preferences.speechLanguages = []
            let data = try JSONSerialization.data(withJSONObject: OpenAIRequest.sessionUpdate(context: preferences.context))
            XCTAssertFalse(String(decoding: data, as: UTF8.self).contains("languages"))
            let body = OpenAIRequest.multipart(wav: Data(), context: preferences.context, boundary: "test")
            XCTAssertFalse(String(decoding: body, as: UTF8.self).contains("languages[]"))
        }
    }
}
