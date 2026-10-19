import AppKit
import SwiftUI
import XCTest
import OpenDictateCore
@testable import OpenDictate

final class PostProcessingPreferencesTests: XCTestCase {
    @MainActor func testSettingsRoundTripSyncResetAndInvalidRemoteDoesNotPartiallyApply() throws {
        let suite = "postprocessing-\(UUID().uuidString)", defaults = UserDefaults(suiteName: suite)!
        let otherSuite = suite + "-other", otherDefaults = UserDefaults(suiteName: otherSuite)!
        defer { defaults.removePersistentDomain(forName: suite); otherDefaults.removePersistentDomain(forName: otherSuite) }
        let local = Preferences(defaults: defaults), other = Preferences(defaults: otherDefaults)
        XCTAssertEqual(local.postProcessing, .init())
        let snapshot = local.postProcessing
        let custom = PostProcessingSettings(enabled: true, prompt: "Format numbers\n📝")
        local.postProcessing = custom
        XCTAssertFalse(snapshot.enabled)
        XCTAssertEqual(Preferences(defaults: defaults).postProcessing, custom)
        _ = try other.mergeSync(local.syncDocument())
        XCTAssertEqual(other.postProcessing, custom)
        local.postProcessing.prompt = nil
        _ = try other.mergeSync(local.syncDocument())
        XCTAssertNil(other.postProcessing.prompt)
        XCTAssertEqual(other.postProcessing.effectivePrompt, PostProcessingPreset.punctuation.text)
        XCTAssertFalse(try other.syncDocument().entries["postprocessing"]!.value!.contains(PostProcessingPreset.punctuation.text))
        var invalid = try local.syncDocument()
        invalid.entries["postprocessing"] = .init(value: #"{"enabled":true,"prompt":123}"#,
            modifiedAt: Int64.max - 10, deviceId: "remote")
        invalid.entries["dictionary"] = .init(value: "Must not apply", modifiedAt: Int64.max - 10, deviceId: "remote")
        let dictionary = other.dictionary
        XCTAssertThrowsError(try other.mergeSync(invalid))
        XCTAssertEqual(other.dictionary, dictionary)
    }

    @MainActor func testSettingsWithoutTranscriptionPromptLayouts() throws {
        let suite = "removed-prompt-layout-\(UUID().uuidString)", defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let model = AppModel(localOnly: true, defaults: defaults)
        for (language, dark, enabled, mode) in [("en", true, false, DictationMode.accurate), ("ru", false, false, .accurate), ("ru", false, true, .accurate), ("en", false, true, .live)] {
            model.preferences.interfaceLanguage = language
            model.preferences.theme = dark ? .dark : .light
            model.preferences.mode = mode
            model.preferences.postProcessing.enabled = enabled
            let host = NSHostingView(rootView: SettingsView(model: model, preferences: model.preferences,
                history: model.history, replacements: model.replacements).frame(width: 1000, height: 900))
            host.appearance = NSAppearance(named: dark ? .darkAqua : .aqua)
            host.frame = NSRect(x: 0, y: 0, width: 1000, height: 900)
            let window = NSWindow(contentRect: host.frame, styleMask: [.titled], backing: .buffered, defer: false)
            window.isReleasedWhenClosed = false; window.contentView = host; window.appearance = host.appearance
            window.makeKeyAndOrderFront(nil); host.layoutSubtreeIfNeeded()
            RunLoop.main.run(until: Date().addingTimeInterval(0.2))
            if let output = ProcessInfo.processInfo.environment["OPENDICTATE_VIEW_CAPTURES"] {
                let bitmap = try XCTUnwrap(host.bitmapImageRepForCachingDisplay(in: host.bounds))
                host.cacheDisplay(in: host.bounds, to: bitmap)
                try XCTUnwrap(bitmap.representation(using: .png, properties: [:]))
                    .write(to: URL(fileURLWithPath: output).appendingPathComponent("accurate-post-macos-full-\(mode.rawValue)-\(language)-\(enabled).png"))
            }
            window.close()
        }
    }

    @MainActor func testNativePostProcessingSectionLayouts() throws {
        let suite = "postprocessing-layout-\(UUID().uuidString)", defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = Preferences(defaults: defaults)
        for (language, dark, enabled, width) in [("en", true, true, 568.0), ("ru", false, true, 464.0), ("ru", false, false, 464.0)] {
            preferences.interfaceLanguage = language
            preferences.mode = .accurate
            preferences.postProcessing.enabled = enabled
            let host = NSHostingView(rootView: PostProcessingSettingsView(preferences: preferences).padding(28)
                .frame(width: width, height: enabled ? 650 : 120, alignment: .topLeading)
                .background(Color(nsColor: .windowBackgroundColor)))
            host.appearance = NSAppearance(named: dark ? .darkAqua : .aqua)
            host.frame = NSRect(x: 0, y: 0, width: width, height: enabled ? 650 : 120)
            let window = NSWindow(contentRect: host.frame, styleMask: [.titled], backing: .buffered, defer: false)
            window.isReleasedWhenClosed = false; window.contentView = host; window.appearance = host.appearance
            window.makeKeyAndOrderFront(nil)
            host.layoutSubtreeIfNeeded()
            RunLoop.main.run(until: Date().addingTimeInterval(0.2))
            if let output = ProcessInfo.processInfo.environment["OPENDICTATE_VIEW_CAPTURES"] {
                let bitmap = try XCTUnwrap(host.bitmapImageRepForCachingDisplay(in: host.bounds))
                host.cacheDisplay(in: host.bounds, to: bitmap)
                let directory = URL(fileURLWithPath: output)
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                try XCTUnwrap(bitmap.representation(using: .png, properties: [:]))
                    .write(to: directory.appendingPathComponent("postprocessing-macos-\(language)-\(enabled).png"))
            }
            window.close()
        }
    }
}
