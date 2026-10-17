import AppKit
import SwiftUI
import XCTest
import OpenDictateCore
@testable import OpenDictate

final class TranscriptionPromptTests: XCTestCase {
    @MainActor func testUntouchedDefaultIsNotExportedAndResetPersistsAcrossRestartAndCloudMerge() throws {
        let suite = "prompt-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = Preferences(defaults: defaults)
        XCTAssertNil(try preferences.syncDocument().entries["transcriptionPrompt"])
        preferences.dictionary = "Local"
        XCTAssertNil(try preferences.syncDocument().entries["transcriptionPrompt"])
        let custom = "Сохраняй слова 📝\n\"Exactly\"."
        preferences.customTranscriptionPrompt = custom
        let snapshot = preferences.context
        XCTAssertEqual(snapshot.prompt, custom)
        let stale = try preferences.syncDocument()
        preferences.customTranscriptionPrompt = nil
        XCTAssertEqual(snapshot.prompt, custom)
        let reset = try preferences.syncDocument()
        XCTAssertNotNil(reset.entries["transcriptionPrompt"])
        XCTAssertNil(reset.entries["transcriptionPrompt"]?.value)
        let restarted = Preferences(defaults: defaults)
        _ = try restarted.mergeSync(stale)
        XCTAssertNil(restarted.customTranscriptionPrompt)
        XCTAssertEqual(restarted.context.prompt, TranscriptionPrompt.defaultText(languages: Array(restarted.speechLanguages)))
        let otherDefaults = UserDefaults(suiteName: suite + "-other")!
        defer { otherDefaults.removePersistentDomain(forName: suite + "-other") }
        let other = Preferences(defaults: otherDefaults)
        _ = try other.mergeSync(stale)
        XCTAssertEqual(other.customTranscriptionPrompt, custom)
        _ = try other.mergeSync(reset)
        XCTAssertNil(other.customTranscriptionPrompt)
        other.customTranscriptionPrompt = ""
        XCTAssertEqual(other.context.prompt, "")
        XCTAssertEqual(try other.syncDocument().entries["transcriptionPrompt"]?.value, "")
    }

    @MainActor func testInvalidCloudPromptDoesNotChangeLocalSettings() throws {
        let suite = "prompt-invalid-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = Preferences(defaults: defaults)
        preferences.customTranscriptionPrompt = "Keep this"
        var remote = SettingsSyncDocument()
        remote.record(["transcriptionPrompt": String(repeating: "я", count: TranscriptionPrompt.maximumBytes)], deviceId: "remote", now: Int64.max - 10)
        XCTAssertThrowsError(try preferences.mergeSync(remote))
        XCTAssertEqual(preferences.customTranscriptionPrompt, "Keep this")
    }

    @MainActor func testNativePromptEditorLayouts() throws {
        let suite = "prompt-editor-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = Preferences(defaults: defaults)
        for (language, theme) in [("en", AppTheme.dark), ("ru", .light)] {
            preferences.interfaceLanguage = language
            preferences.theme = theme
            preferences.speechLanguages = ["ru", "en"]
            let host = NSHostingView(rootView: TranscriptionPromptEditor(preferences: preferences)
                .background(Color(nsColor: .windowBackgroundColor)))
            host.appearance = NSAppearance(named: theme == .dark ? .darkAqua : .aqua)
            host.frame = NSRect(x: 0, y: 0, width: 620, height: 600)
            let window = NSWindow(contentRect: host.frame, styleMask: [.titled], backing: .buffered, defer: false)
            window.isReleasedWhenClosed = false
            window.contentView = host
            window.appearance = host.appearance
            window.makeKeyAndOrderFront(nil)
            defer { window.close() }
            host.layoutSubtreeIfNeeded()
            RunLoop.main.run(until: Date().addingTimeInterval(0.15))
            if let output = ProcessInfo.processInfo.environment["OPENDICTATE_VIEW_CAPTURES"] {
                let bitmap = try XCTUnwrap(host.bitmapImageRepForCachingDisplay(in: host.bounds))
                host.cacheDisplay(in: host.bounds, to: bitmap)
                let directory = URL(fileURLWithPath: output)
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                try XCTUnwrap(bitmap.representation(using: .png, properties: [:]))
                    .write(to: directory.appendingPathComponent("prompt-macos-\(language).png"))
            }
        }
    }
}
