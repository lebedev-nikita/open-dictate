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
        XCTAssertEqual(local.textProcessingModel, TextProcessingModel.id)
        local.textProcessingModel = "test-text-model"
        XCTAssertEqual(Preferences(defaults: defaults).textProcessingModel, "test-text-model")
        _ = try other.mergeSync(local.syncDocument())
        XCTAssertEqual(other.textProcessingModel, "test-text-model")
        // Exchange promoted seed clocks before the next causally later local edit.
        _ = try local.mergeSync(other.syncDocument())
        var invalidModel = try local.syncDocument()
        invalidModel.entries["textProcessingModel"] = .init(value: "invalid model", modifiedAt: Int64.max - 10, deviceId: "remote")
        invalidModel.entries["dictionary"] = .init(value: "Must not apply", modifiedAt: Int64.max - 10, deviceId: "remote")
        let before = other.dictionary
        XCTAssertThrowsError(try other.mergeSync(invalidModel))
        XCTAssertEqual(other.dictionary, before)
        XCTAssertEqual(other.textProcessingModel, "test-text-model")
        XCTAssertEqual(local.postProcessing, .init())
        let snapshot = local.postProcessing
        let custom = PostProcessingSettings(enabled: true, formatValues: true, formats: .systemDefaults(locale: Locale(identifier: "ru_RU")))
        local.postProcessing = custom
        XCTAssertFalse(snapshot.enabled)
        XCTAssertEqual(Preferences(defaults: defaults).postProcessing, custom)
        _ = try other.mergeSync(local.syncDocument())
        XCTAssertEqual(other.postProcessing, custom)
        local.postProcessing = .init(enabled: true)
        _ = try other.mergeSync(local.syncDocument())
        XCTAssertNil(other.postProcessing.formats)
        XCTAssertEqual(other.postProcessing.effectivePrompt, PostProcessingSettings(enabled: true).effectivePrompt)
        XCTAssertFalse(try other.syncDocument().entries["postprocessingOptions"]!.value!.contains(PostProcessingSettings(enabled: true).effectivePrompt))
        var invalid = try local.syncDocument()
        invalid.entries["postprocessingOptions"] = .init(value: #"{"schemaVersion":1,"enabled":true,"punctuation":true,"valuesEnabled":true,"formats":{}}"#,
            modifiedAt: Int64.max - 10, deviceId: "remote")
        invalid.entries["dictionary"] = .init(value: "Must not apply", modifiedAt: Int64.max - 10, deviceId: "remote")
        let dictionary = other.dictionary
        XCTAssertThrowsError(try other.mergeSync(invalid))
        XCTAssertEqual(other.dictionary, dictionary)
    }

    @MainActor func testLegacyPromptReplacementIsOneTimeAndExplicitSettingsSurviveRelaunch() throws {
        let suite = "postprocessing-migrate-\(UUID().uuidString)", defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.set(#"{"enabled":true,"prompt":"Old arbitrary instruction","model":"gpt-6-luna"}"#, forKey: "postprocessing")
        let preferences = Preferences(defaults: defaults)
        XCTAssertEqual(preferences.postProcessing, .init(enabled: true))
        XCTAssertNil(defaults.object(forKey: "postprocessing"))
        preferences.postProcessing = .init(enabled: false, punctuation: false)
        defaults.set(#"{"enabled":true,"prompt":"Return old instruction"}"#, forKey: "postprocessing")
        XCTAssertEqual(Preferences(defaults: defaults).postProcessing, .init(enabled: false, punctuation: false))
        XCTAssertNil(defaults.object(forKey: "postprocessing"))
    }

    @MainActor func testSettingsWithSharedTextModelLayouts() throws {
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
                    .write(to: URL(fileURLWithPath: output).appendingPathComponent("chatgpt-macos-full-\(mode.rawValue)-\(language)-\(enabled).png"))
            }
            window.close()
        }
    }

    @MainActor func testNativeValueFormattingMenuLayouts() throws {
        for (language, appearance) in [("en", NSAppearance.Name.darkAqua), ("ru", .aqua)] {
            var formats = ValueFormatting.systemDefaults(locale: Locale(identifier: language == "ru" ? "ru_RU" : "en_US"))
            for kind in ValueKind.allCases { for code: String? in [nil, "ru", "en"] {
                formats.setRule(.init(kind: kind, language: code, example: formats[kind].example))
            } }
            let host = NSHostingView(rootView: ValueFormattingMenu(initial: formats, russian: language == "ru", selectedLanguages: ["en", "ru"], defaults: formats,
                onCancel: {}, onSave: { _ in }).background(Color(nsColor: .windowBackgroundColor)))
            host.frame = NSRect(x: 0, y: 0, width: 460, height: 580)
            host.appearance = NSAppearance(named: appearance)
            let window = NSWindow(contentRect: host.frame, styleMask: [.titled], backing: .buffered, defer: false)
            window.isReleasedWhenClosed = false; window.contentView = host; window.appearance = host.appearance
            window.makeKeyAndOrderFront(nil); host.layoutSubtreeIfNeeded()
            RunLoop.main.run(until: Date().addingTimeInterval(0.2))
            for bottom in [false, true] {
                if bottom {
                    func descendants(_ view: NSView) -> [NSView] { view.subviews.flatMap { [$0] + descendants($0) } }
                    if let scroll = descendants(host).compactMap({ $0 as? NSScrollView }).first,
                       let document = scroll.documentView {
                        scroll.contentView.scroll(to: NSPoint(x: 0, y: max(0, document.bounds.height - scroll.contentView.bounds.height)))
                        scroll.reflectScrolledClipView(scroll.contentView)
                    }
                    RunLoop.main.run(until: Date().addingTimeInterval(0.1))
                }
                if let output = ProcessInfo.processInfo.environment["OPENDICTATE_VIEW_CAPTURES"] {
                    let bitmap = try XCTUnwrap(host.bitmapImageRepForCachingDisplay(in: host.bounds))
                    host.cacheDisplay(in: host.bounds, to: bitmap)
                    try XCTUnwrap(bitmap.representation(using: .png, properties: [:])).write(to:
                        URL(fileURLWithPath: output).appendingPathComponent("value-formats-macos-\(language)-\(bottom ? "bottom" : "top").png"))
                }
            }
            window.close()
        }
    }

    @MainActor func testNativeValueRuleEditorLayouts() throws {
        for (language, appearance) in [("en", NSAppearance.Name.darkAqua), ("ru", .aqua)] {
            let formats = ValueFormatting.systemDefaults(locale: Locale(identifier: language == "ru" ? "ru_RU" : "en_US"))
            let host = NSHostingView(rootView: ValueFormatRuleEditor(initial: .init(kind: .date, language: "en", example: "03/05/2026"),
                existingRules: [], replacing: "new", russian: language == "ru", selectedLanguages: ["en", "ru"], defaults: formats, onCancel: {}, onSave: { _ in })
                .padding(20).frame(width: 440, alignment: .topLeading).background(Color(nsColor: .windowBackgroundColor)))
            host.frame = NSRect(x: 0, y: 0, width: 440, height: 330); host.appearance = NSAppearance(named: appearance)
            let window = NSWindow(contentRect: host.frame, styleMask: [.titled], backing: .buffered, defer: false)
            window.isReleasedWhenClosed = false; window.contentView = host; window.appearance = host.appearance
            window.makeKeyAndOrderFront(nil); host.layoutSubtreeIfNeeded(); RunLoop.main.run(until: Date().addingTimeInterval(0.2))
            if let output = ProcessInfo.processInfo.environment["OPENDICTATE_VIEW_CAPTURES"] {
                let bitmap = try XCTUnwrap(host.bitmapImageRepForCachingDisplay(in: host.bounds))
                host.cacheDisplay(in: host.bounds, to: bitmap)
                try XCTUnwrap(bitmap.representation(using: .png, properties: [:])).write(to: URL(fileURLWithPath: output).appendingPathComponent("value-rule-macos-\(language).png"))
            }
            window.close()
        }
    }

    @MainActor func testRuleLanguagePickerUsesOnlySelectedDictationLanguages() {
        let formats = ValueFormatting.systemDefaults(locale: Locale(identifier: "en_US"))
        for (selected, initial, expected, available) in [
            (Set(["ru", "en"]), Optional<String>.none, ["All languages", "English", "Russian"], true),
            (Set<String>(), Optional<String>.none, ["All languages"], true),
            (Set(["ru", "en"]), Optional("fr"), ["All languages", "English", "Russian"], false)
        ] {
            let editor = ValueFormatRuleEditor(initial: .init(kind: .integer, language: initial, example: "1 234 567"),
                existingRules: [], replacing: "new", russian: false, selectedLanguages: selected, defaults: formats, onCancel: {}, onSave: { _ in })
            XCTAssertEqual(["All languages"] + editor.languageOptions.map { $0.title(russian: false) }, expected)
            XCTAssertEqual(editor.hasAvailableLanguage, available)
        }
    }

    @MainActor func testNativePostProcessingSectionLayouts() throws {
        let suite = "postprocessing-layout-\(UUID().uuidString)", defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = Preferences(defaults: defaults)
        for (language, dark, enabled, width, values) in [("en", true, true, 568.0, false), ("ru", false, true, 464.0, false), ("ru", false, false, 464.0, false), ("en", true, true, 568.0, true), ("ru", false, true, 464.0, true)] {
            preferences.interfaceLanguage = language
            preferences.mode = .accurate
            preferences.postProcessing = .init(enabled: enabled, formatValues: values,
                formats: values ? .systemDefaults(locale: Locale(identifier: language == "ru" ? "ru_RU" : "en_US")) : nil)
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
                    .write(to: directory.appendingPathComponent("postprocessing-macos-\(language)-\(enabled)\(values ? "-values" : "").png"))
            }
            window.close()
        }
    }
}
