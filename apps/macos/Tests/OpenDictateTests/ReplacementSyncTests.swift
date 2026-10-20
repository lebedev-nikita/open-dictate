import XCTest
import OpenDictateCore
@testable import OpenDictate

final class ReplacementSyncTests: XCTestCase {
    @MainActor func testFormattingRulesPersistSyncWhileDisabledAndNeverChangeLocalSwitch() throws {
        let suite = "formatting-sync-\(UUID().uuidString)", defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = Preferences(defaults: defaults)
        XCTAssertEqual(preferences.formattingRules, "")
        XCTAssertEqual(try preferences.syncDocument().entries["formattingRules"]?.modifiedAt, 0)
        var events = 0
        let subscription = preferences.syncChanges.sink { events += 1 }
        defer { subscription.cancel() }
        preferences.formattingRules = "Без точки в конце.\nНовый абзац для каждой мысли."
        XCTAssertEqual(events, 1)
        XCTAssertFalse(preferences.accuratePunctuationEnabled)
        XCTAssertEqual(Preferences(defaults: defaults).formattingRules, preferences.formattingRules)
        var remote = SettingsSyncDocument()
        remote.entries["formattingRules"] = .init(value: "Lowercase 📝", modifiedAt: Int64.max - 10, deviceId: "mac")
        remote.entries["accuratePunctuationEnabled"] = .init(value: "true", modifiedAt: Int64.max - 10, deviceId: "mac")
        _ = try preferences.mergeSync(remote)
        XCTAssertEqual(preferences.formattingRules, "Lowercase 📝")
        XCTAssertFalse(preferences.accuratePunctuationEnabled)
        XCTAssertEqual(events, 1)
        preferences.accuratePunctuationEnabled = true
        preferences.formattingRules = ""
        XCTAssertEqual(events, 2)
        _ = try preferences.mergeSync(remote)
        XCTAssertTrue(preferences.accuratePunctuationEnabled)
        XCTAssertEqual(preferences.formattingRules, "")
        XCTAssertEqual(Preferences(defaults: defaults).formattingRules, "")
        XCTAssertNil(try preferences.syncDocument().entries["accuratePunctuationEnabled"])
    }

    @MainActor func testOversizedFormattingRulesRejectImportBeforeApplyingAnySettings() throws {
        let suite = "formatting-limit-\(UUID().uuidString)", defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = Preferences(defaults: defaults)
        var remote = SettingsSyncDocument()
        remote.entries["dictionary"] = .init(value: "Must not apply", modifiedAt: 100, deviceId: "mac")
        remote.entries["formattingRules"] = .init(value: String(repeating: "📝", count: 2_001), modifiedAt: 100, deviceId: "mac")
        let before = try preferences.syncDocument()
        XCTAssertThrowsError(try preferences.mergeSync(remote))
        XCTAssertEqual(try preferences.syncDocument(), before)
        XCTAssertEqual(preferences.dictionary, "OpenDictate")
        XCTAssertEqual(preferences.formattingRules, "")
    }

    @MainActor func testSyncEventsIncludeOnlyActualLocalChangesAndNeverRemoteImports() throws {
        let suite = "sync-events-\(UUID().uuidString)", defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = Preferences(defaults: defaults)
        var events = 0
        let subscription = preferences.syncChanges.sink { events += 1 }
        defer { subscription.cancel() }
        preferences.saveHistory.toggle()
        preferences.dictionary = preferences.dictionary
        XCTAssertEqual(events, 0)
        preferences.dictionary = "Local edit"
        preferences.mode = preferences.mode == .live ? .accurate : .live
        XCTAssertTrue(preferences.replacements.save(source: "cat", replacement: "dog"))
        preferences.replacements.enabled = false
        XCTAssertEqual(events, 4)
        var remote = try preferences.syncDocument()
        remote.entries["dictionary"] = .init(value: "Remote edit", modifiedAt: Int64.max - 10, deviceId: "remote")
        _ = try preferences.mergeSync(remote)
        XCTAssertEqual(preferences.dictionary, "Remote edit")
        XCTAssertEqual(events, 4)
    }

    @MainActor func testPunctuationDefaultsOffPersistsLocallyAndIgnoresLegacySync() throws {
        let suite = "punctuation-sync-\(UUID().uuidString)", defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = Preferences(defaults: defaults)
        XCTAssertFalse(preferences.accuratePunctuationEnabled)
        XCTAssertNil(try preferences.syncDocument().entries["accuratePunctuationEnabled"])
        var events = 0
        let subscription = preferences.syncChanges.sink { events += 1 }
        defer { subscription.cancel() }
        var remote = SettingsSyncDocument()
        remote.entries["accuratePunctuationEnabled"] = .init(value: "true", modifiedAt: 42, deviceId: "android")
        _ = try preferences.mergeSync(remote)
        XCTAssertFalse(preferences.accuratePunctuationEnabled)
        preferences.accuratePunctuationEnabled = true
        XCTAssertTrue(Preferences(defaults: defaults).accuratePunctuationEnabled)
        XCTAssertEqual(events, 0)
        // Simulate a journal left by the previous app version.
        defaults.set(Data(#"{"schemaVersion":1,"entries":{"accuratePunctuationEnabled":{"value":"true","modifiedAt":42,"deviceId":"android"}}}"#.utf8), forKey: "syncDocument")
        let restored = Preferences(defaults: defaults)
        XCTAssertTrue(restored.accuratePunctuationEnabled)
        XCTAssertNil(try restored.syncDocument().entries["accuratePunctuationEnabled"])
        remote.entries["accuratePunctuationEnabled"] = .init(value: "invalid", modifiedAt: Int64.max - 10, deviceId: "android")
        remote.entries["dictionary"] = .init(value: "Cloud", modifiedAt: 100, deviceId: "android")
        _ = try preferences.mergeSync(remote)
        XCTAssertTrue(preferences.accuratePunctuationEnabled)
        XCTAssertEqual(preferences.dictionary, "Cloud")
        preferences.accuratePunctuationEnabled = false
        _ = try preferences.mergeSync(remote)
        XCTAssertFalse(Preferences(defaults: defaults).accuratePunctuationEnabled)
        XCTAssertNil(try preferences.syncDocument().entries["accuratePunctuationEnabled"])
        XCTAssertEqual(events, 0)
    }

    private func fixture() throws -> SettingsSyncDocument {
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        return try SettingsSyncDocument.decode(Data(contentsOf: root.appendingPathComponent("shared/settings-sync-replacements.json")))
    }

    @MainActor func testSharedImportRefreshesStorePreservesSessionAndDoesNotRestampRules() throws {
        let suite = "replacement-sync-\(UUID().uuidString)", defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let local = ReplacementDocument(rules: [.init(source: "опен диктейт", replacement: "Local")])
        defaults.set(try JSONEncoder().encode(local), forKey: "wordReplacementDocument")
        let store = ReplacementStore(defaults: defaults), preferences = Preferences(defaults: defaults, replacements: store)
        let active = store.engine(), remote = try fixture()
        let merged = try preferences.mergeSync(remote)
        XCTAssertEqual(active.apply("опен диктейт cat"), "Local cat")
        XCTAssertEqual(store.engine().apply("опен диктейт cat"), "OpenDictate 📝 cat")
        XCTAssertEqual(store.rules.first { $0.source == "опен диктейт" }?.id, "00000000-0000-0000-0000-000000000001")
        XCTAssertEqual(store.rules.first { $0.source == "cat" }?.enabled, false)
        XCTAssertEqual(merged.entries["wordReplacements"], remote.entries["wordReplacements"])
        preferences.dictionary = "An unrelated local edit"
        XCTAssertEqual(try preferences.syncDocument().entries["wordReplacements"], remote.entries["wordReplacements"])
        XCTAssertEqual(ReplacementStore(defaults: defaults).document, store.document)
    }

    @MainActor func testLocalDeletionAndDisableSurviveStaleRemoteAndInFlightLocalEditWins() throws {
        let suite = "replacement-sync-\(UUID().uuidString)", defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = Preferences(defaults: defaults), store = preferences.replacements, remote = try fixture()
        _ = try preferences.mergeSync(remote)
        let active = store.engine()
        for rule in store.rules { store.delete(rule) }
        store.enabled = false
        _ = try preferences.mergeSync(remote)
        XCTAssertTrue(store.rules.isEmpty)
        XCTAssertFalse(store.enabled)
        XCTAssertEqual(active.apply("опен диктейт"), "OpenDictate 📝")
        XCTAssertTrue(store.save(source: "cat", replacement: "bird"))
        let local = try preferences.syncDocument().entries["wordReplacements"]
        _ = try preferences.mergeSync(remote)
        XCTAssertEqual(store.rules.map(\.replacement), ["bird"])
        XCTAssertEqual(try preferences.syncDocument().entries["wordReplacements"], local)
    }

    @MainActor func testInvalidRuleOrSwitchDoesNotPartiallyApplyOtherPreferences() throws {
        let suite = "replacement-sync-\(UUID().uuidString)", defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = Preferences(defaults: defaults)
        for (key, value) in [("wordReplacements", #"{"schemaVersion":2,"rules":[]}"#),
                             ("wordReplacementsEnabled", "invalid")] {
            var remote = try fixture()
            remote.entries["dictionary"]?.value = "Must not apply"
            remote.entries[key]?.value = value
            let before = try preferences.syncDocument()
            XCTAssertThrowsError(try preferences.mergeSync(remote))
            XCTAssertEqual(preferences.dictionary, "OpenDictate")
            XCTAssertTrue(preferences.replacements.rules.isEmpty)
            XCTAssertEqual(try preferences.syncDocument(), before)
        }
    }

    @MainActor func testOlderJournalSeedsNewFieldsWithoutLosingExistingClocksOrFutureData() throws {
        let suite = "replacement-sync-\(UUID().uuidString)", defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        var old = SettingsSyncDocument()
        old.entries["dictionary"] = .init(value: "OpenDictate", modifiedAt: 70, deviceId: "mac")
        old.entries["future.preference"] = .init(value: "preserved", modifiedAt: 80, deviceId: "mac")
        defaults.set(try JSONEncoder().encode(old), forKey: "syncDocument")
        let preferences = Preferences(defaults: defaults)
        let migrated = try preferences.syncDocument()
        XCTAssertEqual(migrated.entries["dictionary"], old.entries["dictionary"])
        XCTAssertEqual(migrated.entries["future.preference"], old.entries["future.preference"])
        XCTAssertEqual(migrated.entries["wordReplacements"]?.modifiedAt, 0)
        XCTAssertEqual(migrated.entries["wordReplacementsEnabled"]?.modifiedAt, 0)
        _ = try preferences.mergeSync(fixture())
        XCTAssertEqual(preferences.replacements.rules.count, 2)
    }

    @MainActor func testOversizedReplacementListCanBeReducedAndSyncedWithoutRestart() throws {
        let suite = "replacement-sync-\(UUID().uuidString)", defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let large = ReplacementDocument(rules: (0..<70).map {
            .init(source: "phrase\($0)", replacement: String(repeating: "📝", count: 1024))
        })
        XCTAssertTrue(large.isValid)
        defaults.set(try JSONEncoder().encode(large), forKey: "wordReplacementDocument")
        let preferences = Preferences(defaults: defaults)
        XCTAssertThrowsError(try preferences.syncDocument())
        for rule in preferences.replacements.rules.prefix(10) { preferences.replacements.delete(rule) }
        let recovered = try preferences.syncDocument()
        XCTAssertGreaterThan(recovered.entries["wordReplacements"]!.modifiedAt, 42)
        _ = try preferences.mergeSync(fixture())
        XCTAssertEqual(preferences.replacements.rules.count, 60)
    }
}
