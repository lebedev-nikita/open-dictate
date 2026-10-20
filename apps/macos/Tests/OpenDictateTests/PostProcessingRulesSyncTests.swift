import XCTest
import OpenDictateCore
@testable import OpenDictate

final class PostProcessingRulesSyncTests: XCTestCase {
    @MainActor func testRulesSyncWhileAllSwitchesStayLocalAndDoNotTriggerSync() throws {
        let name = "rules-sync-\(UUID().uuidString)", defaults = UserDefaults(suiteName: name)!
        defer { defaults.removePersistentDomain(forName: name) }
        let preferences = Preferences(defaults: defaults)
        var formats = ValueFormatting.systemDefaults(locale: Locale(identifier: "en_US"))
        formats[.integer].enabled = false
        preferences.postProcessing = .init(enabled: false, punctuation: false, formats: formats)
        let initial = try preferences.syncDocument()
        preferences.postProcessing.enabled = true
        preferences.postProcessing.punctuation = true
        preferences.postProcessing.formatValues = true
        XCTAssertEqual(initial, try preferences.syncDocument())
        var remoteFormats = formats
        remoteFormats[.integer].enabled = true
        remoteFormats.setRule(.init(kind: .integer, language: "en", example: "1,234,567"))
        let remote = PostProcessingSettings(formats: remoteFormats)
        let encoded = try remote.encodedRules()
        XCTAssertFalse(encoded.contains("enabled"))
        var cloud = SettingsSyncDocument()
        cloud.entries["postprocessingRules"] = .init(value: encoded, modifiedAt: Int64.max - 10, deviceId: "other")
        _ = try preferences.mergeSync(cloud)
        XCTAssertTrue(preferences.postProcessing.enabled)
        XCTAssertTrue(preferences.postProcessing.punctuation)
        XCTAssertTrue(preferences.postProcessing.formatValues)
        XCTAssertFalse(preferences.postProcessing.formats![.integer].enabled)
        XCTAssertEqual(preferences.postProcessing.formats?.customRules, remoteFormats.customRules)
        XCTAssertEqual(Preferences(defaults: defaults).postProcessing, preferences.postProcessing)
        cloud.entries["postprocessingRules"] = .init(value: try PostProcessingSettings().encodedRules(), modifiedAt: Int64.max - 5, deviceId: "other")
        _ = try preferences.mergeSync(cloud)
        XCTAssertTrue(preferences.postProcessing.enabled)
        XCTAssertTrue(preferences.postProcessing.formatValues)
        XCTAssertNil(preferences.postProcessing.formats)
        XCTAssertEqual(Preferences(defaults: defaults).postProcessing, preferences.postProcessing)
    }
}
