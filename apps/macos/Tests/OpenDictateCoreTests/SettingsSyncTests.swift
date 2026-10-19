import XCTest
@testable import OpenDictateCore

final class SettingsSyncTests: XCTestCase {
    private func document(_ key: String, _ value: String, _ time: Int64, _ device: String) -> SettingsSyncDocument {
        var result = SettingsSyncDocument()
        result.entries[key] = .init(value: value, modifiedAt: time, deviceId: device)
        return result
    }
    func testSelectedTextModelSyncsAndFeedsBothTextRequests() throws {
        var seed = SettingsSyncDocument()
        seed.record(["textProcessingModel": "gpt-6-luna"], deviceId: "new", now: 0, seed: true)
        let remote = document("textProcessingModel", "test-text-model", 500, "other")
        var merged = seed; merged.merge(remote)
        var reversed = remote; reversed.merge(seed)
        XCTAssertEqual(merged, reversed)
        XCTAssertTrue(seed.needsConnectionChoice(with: remote))
        let selected = try XCTUnwrap(SettingsSyncDocument.decode(JSONEncoder().encode(merged)).entries["textProcessingModel"]?.value)
        XCTAssertEqual(selected, "test-text-model")
        XCTAssertEqual(OpenAIRequest.transform(source: "Text", instruction: "Edit", model: selected)["model"] as? String, selected)
        XCTAssertEqual(OpenAIRequest.postProcessing(source: "Text", settings: .init(enabled: true), model: selected)["model"] as? String, selected)
        XCTAssertEqual(OpenAIRequest.search(query: "Find", entries: [])["model"] as? String, "gpt-6-luna")
    }

    func testOldModelChoicesCannotAffectRequestsOrRequireSourceChoice() throws {
        let leftJSON = #"{"enabled":true,"model":"gpt-6-sol","prompt":"Keep text"}"#
        let rightJSON = #"{"enabled":true,"model":"gpt-4o-mini","prompt":"Keep text"}"#
        var local = document("postprocessing", leftJSON, 10, "mac")
        var remote = document("postprocessing", rightJSON, 20, "android")
        remote.entries["textModel"] = .init(value: "gpt-6-sol", modifiedAt: 30, deviceId: "old")
        XCTAssertTrue(local.hasSameSettings(as: remote))
        XCTAssertFalse(local.needsConnectionChoice(with: remote))
        local.merge(remote)
        XCTAssertNil(local.entries["textModel"])
        let settings = try PostProcessingSettings.decode(local.entries["postprocessing"]!.value!)
        let requests = [OpenAIRequest.transform(source: "Text", instruction: "Edit"),
                        OpenAIRequest.search(query: "Find", entries: []),
                        OpenAIRequest.postProcessing(source: "Text", settings: settings)]
        for request in requests { XCTAssertEqual(request["model"] as? String, "gpt-6-luna") }
        XCTAssertEqual(settings.prompt, "Keep text")
        XCTAssertTrue(settings.enabled)
        XCTAssertEqual(local.entries["postprocessing"]?.modifiedAt, 20)
    }

    func testRetiredPromptIsDroppedWithoutAConnectionChoice() throws {
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        let remote = try SettingsSyncDocument.decode(Data(contentsOf: root.appendingPathComponent("shared/settings-sync-prompts.json")))
        XCTAssertNil(remote.entries["transcriptionPrompt"])
        XCTAssertEqual(remote.entries["future.preference"]?.value, "preserved")
        let legacy = document("transcriptionPrompt", "Ignored", 500, "old")
        let local = SettingsSyncDocument()
        XCTAssertFalse(local.needsConnectionChoice(with: legacy))
        XCTAssertTrue(local.hasSameSettings(as: legacy))
        for source in [SettingsSyncDocument.ConnectionSource.local, .cloud] {
            XCTAssertNil(local.resolvingConnection(with: legacy, source: source, deviceId: "a", now: 1).entries["transcriptionPrompt"])
        }
        var edited = legacy
        edited.record(["dictionary": "Updated", "transcriptionPrompt": "Ignored"], deviceId: "a", now: 1)
        XCTAssertNil(edited.entries["transcriptionPrompt"])
        XCTAssertEqual(edited.entries["dictionary"]?.value, "Updated")
        XCTAssertFalse(String(decoding: try JSONEncoder().encode(legacy), as: UTF8.self).contains("transcriptionPrompt"))
        XCTAssertEqual(remote.schemaVersion, 1)
    }

    func testNullOnlyAllowedForPromptInVersionTwoAndNotAMissingValue() {
        for json in [
            #"{"schemaVersion":1,"entries":{"transcriptionPrompt":{"value":null,"modifiedAt":1,"deviceId":"a"}}}"#,
            #"{"schemaVersion":2,"entries":{"mode":{"value":null,"modifiedAt":1,"deviceId":"a"}}}"#,
            #"{"schemaVersion":2,"entries":{"transcriptionPrompt":{"modifiedAt":1,"deviceId":"a"}}}"#
        ] { XCTAssertThrowsError(try SettingsSyncDocument.decode(Data(json.utf8))) }
    }

    func testPunctuationIsExcludedFromSeedsLegacyJournalsMergesAndExports() throws {
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        let remote = try SettingsSyncDocument.decode(Data(contentsOf: root.appendingPathComponent("shared/settings-sync-punctuation.json")))
        XCTAssertNil(remote.entries["accuratePunctuationEnabled"])
        XCTAssertEqual(remote.entries["future.preference"]?.value, "preserved")
        var seed = SettingsSyncDocument()
        seed.record(["accuratePunctuationEnabled": "true"], deviceId: "mac", now: 0, seed: true)
        XCTAssertTrue(seed.entries.isEmpty)
        let legacy = document("accuratePunctuationEnabled", "invalid", 1000, "mac")
        XCTAssertFalse(String(decoding: try JSONEncoder().encode(legacy), as: UTF8.self).contains("accuratePunctuationEnabled"))
        var promoted = legacy; promoted.promoteSeeds(deviceId: "mac", now: 1)
        XCTAssertNil(promoted.entries["accuratePunctuationEnabled"])
        var left = legacy; left.merge(remote)
        var right = remote; right.merge(legacy)
        XCTAssertEqual(left, remote)
        XCTAssertEqual(right, remote)
        var edited = legacy; edited.record(["dictionary": "Local"], deviceId: "mac", now: 50)
        XCTAssertNil(edited.entries["accuratePunctuationEnabled"])
        XCTAssertEqual(edited.entries["dictionary"]?.modifiedAt, 50)
    }

    func testIndependentChangesAndConcurrentConflictsConverge() {
        var android = document("dictionary", "Android\nНикита", 100, "android")
        android.merge(document("accurateModel", "gpt-transcribe", 120, "android"))
        var mac = document("dictionary", "Mac", 100, "mac")
        mac.merge(document("mode", "live", 130, "mac"))
        var left = android; left.merge(mac)
        var right = mac; right.merge(android)
        XCTAssertEqual(left, right)
        left.merge(android); left.merge(mac); XCTAssertEqual(left, right)
        XCTAssertEqual(left.entries["dictionary"]?.value, "Mac")
        XCTAssertEqual(left.entries["mode"]?.value, "live")
        XCTAssertEqual(left.entries["accurateModel"]?.value, "gpt-transcribe")
    }
    func testEmptyDictionarySurvivesStaleReplica() {
        let old = document("dictionary", "OpenDictate", 1, "mac")
        var cleared = old; cleared.record(["dictionary": ""], deviceId: "android", now: 2)
        cleared.merge(old); XCTAssertEqual(cleared.entries["dictionary"]?.value, "")
    }
    func testNewDeviceDefaultsAndClockRollback() {
        var seed = SettingsSyncDocument()
        seed.record(["mode": "accurate"], deviceId: "android", now: 0, seed: true)
        var merged = seed; merged.merge(document("mode", "live", 1000, "mac"))
        XCTAssertEqual(merged.entries["mode"]?.value, "live")
        merged.record(["mode": "accurate"], deviceId: "android", now: 10)
        XCTAssertEqual(merged.entries["mode"]?.modifiedAt, 1001)
        let unchanged = merged; merged.record(["mode": "accurate"], deviceId: "android", now: 2000)
        XCTAssertEqual(merged, unchanged)
        seed.promoteSeeds(deviceId: "android", now: 100)
        XCTAssertGreaterThan(seed.entries["mode"]!.modifiedAt, 0)
    }
    func testSharedWireFormatAndAllowlist() throws {
        let json = #"{"schemaVersion":1,"entries":{"dictionary":{"value":"Никита\nOpenDictate 📝","modifiedAt":1760000000000,"deviceId":"mac"},"future.color":{"value":"dark","modifiedAt":2,"deviceId":"mac"}}}"#
        var parsed = try SettingsSyncDocument.decode(Data(json.utf8))
        XCTAssertEqual(parsed, try SettingsSyncDocument.decode(JSONEncoder().encode(parsed)))
        parsed.record(["accurateModel": "gpt-transcribe", "apiKey": "private-test-key", "history": "private-test-history"], deviceId: "android", now: 1)
        XCTAssertEqual(parsed.entries["future.color"]?.value, "dark")
        XCTAssertFalse(String(decoding: try JSONEncoder().encode(parsed), as: UTF8.self).contains("private-test"))
    }
    func testUnsupportedAndMalformedDocumentsFailClosed() {
        for json in [#"{"schemaVersion":3,"entries":{}}"#,
                     #"{"schemaVersion":1,"entries":{"mode":{"value":false,"modifiedAt":1,"deviceId":"a"}}}"#,
                     #"{"schemaVersion":1,"entries":{"mode":{"value":"live","modifiedAt":-1,"deviceId":"a"}}}"#,
                     String(repeating: "x", count: 1_048_577)] {
            XCTAssertThrowsError(try SettingsSyncDocument.decode(Data(json.utf8)))
        }
    }
    func testConnectionChoiceForDifferencesButNotEmptyCloudOrMatchingValues() {
        let local = document("dictionary", "Local", 500, "mac")
        XCTAssertFalse(local.needsConnectionChoice(with: SettingsSyncDocument()))
        XCTAssertFalse(local.needsConnectionChoice(with: document("future.setting", "unknown", 600, "android")))
        XCTAssertFalse(local.needsConnectionChoice(with: document("dictionary", "Local", 1, "android")))
        XCTAssertTrue(local.needsConnectionChoice(with: document("dictionary", "Cloud", 1, "android")))
        XCTAssertTrue(local.needsConnectionChoice(with: document("dictionary", "", 1, "android")))
        XCTAssertTrue(local.hasSameSettings(as: document("dictionary", "Local", 1, "android")))
    }

    func testExplicitSourceWinsRegardlessOfOfflineClockAndCannotBeUndoneByStaleReplicas() {
        var local = document("dictionary", "Local", 500, "mac")
        local.merge(document("mode", "live", 500, "mac"))
        var cloud = document("dictionary", "", 100, "android")
        cloud.merge(document("mode", "accurate", 900, "android"))
        cloud.merge(document("future.setting", "preserved", 1000, "future"))
        for source in [SettingsSyncDocument.ConnectionSource.cloud, .local] {
            var resolved = local.resolvingConnection(with: cloud, source: source, deviceId: "new", now: 10)
            XCTAssertEqual(resolved.entries["dictionary"]?.value, source == .cloud ? "" : "Local")
            XCTAssertEqual(resolved.entries["mode"]?.value, source == .cloud ? "accurate" : "live")
            XCTAssertEqual(resolved.entries["future.setting"], cloud.entries["future.setting"])
            let chosen = resolved
            resolved.merge(local); resolved.merge(cloud)
            XCTAssertEqual(resolved, chosen)
        }
        // Cloud journals from older versions may lack newer fields; keep local values for them.
        let partial = local.resolvingConnection(with: document("dictionary", "Cloud", 1, "a"),
            source: .cloud, deviceId: "new", now: 2)
        XCTAssertEqual(partial.entries["mode"]?.value, "live")
    }

    func testSelectedModelsReachBothTranscriptionRequests() throws {
        var context = TranscriptionContext()
        context.liveModel = "custom-live-model"; context.accurateModel = "custom-file-model"
        let session = OpenAIRequest.sessionUpdate(context: context)["session"] as! [String: Any]
        let input = ((session["audio"] as! [String: Any])["input"] as! [String: Any])
        XCTAssertEqual((input["transcription"] as! [String: Any])["model"] as? String, "custom-live-model")
        let multipart = OpenAIRequest.multipart(wav: Data(), context: context, boundary: "test")
        XCTAssertTrue(String(decoding: multipart, as: UTF8.self).contains("name=\"model\"\r\n\r\ncustom-file-model"))
    }
}
