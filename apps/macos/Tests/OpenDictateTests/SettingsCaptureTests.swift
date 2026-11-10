import AppKit
import SwiftUI
import XCTest
@testable import OpenDictate

/// Renders every settings page for visual review. Set OPENDICTATE_VIEW_CAPTURES to an output directory.
final class SettingsCaptureTests: XCTestCase {
    @MainActor func testCaptureEverySettingsPage() throws {
        guard let output = ProcessInfo.processInfo.environment["OPENDICTATE_VIEW_CAPTURES"] else {
            throw XCTSkip("Set OPENDICTATE_VIEW_CAPTURES to capture settings pages.")
        }
        let suite = "settings-capture-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let model = AppModel(localOnly: true, defaults: defaults)
        defer { model.shutdown() }
        model.preferences.dictionary = "OpenDictate\nKotlin\nSwiftUI\nЯндекс"
        model.replacements.save(source: "open dictate", replacement: "OpenDictate")
        model.replacements.save(source: "\\bgpt\\b", replacement: "GPT", regex: true, matchCase: true)
        model.history.add("Давай встретимся завтра в 10:30 у входа в офис.", mode: "accurate",
                          transcriptionMillis: 1840, postProcessingMillis: 920,
                          transcriptionModelID: "gpt-transcribe", postProcessingModelID: "gpt-6-luna")
        model.history.add("Please send the updated draft to the team before Friday.", mode: "live",
                          transcriptionModelID: "gpt-live-transcribe")
        let directory = URL(fileURLWithPath: output)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let height = Double(ProcessInfo.processInfo.environment["OPENDICTATE_CAPTURE_HEIGHT"] ?? "650") ?? 650
        for (language, theme) in [("en", AppTheme.light), ("ru", AppTheme.dark)] {
            model.preferences.interfaceLanguage = language
            model.preferences.theme = theme
            for page in ["dictation", "dictionary", "replacements", "history", "shortcuts", "general"] {
                let host = NSHostingView(rootView: SettingsView(model: model, preferences: model.preferences,
                    history: model.history, replacements: model.replacements, page: page))
                host.appearance = theme.appearance
                host.frame = NSRect(x: 0, y: 0, width: 790, height: height)
                let window = NSWindow(contentRect: host.frame, styleMask: [.titled], backing: .buffered, defer: false)
                window.isReleasedWhenClosed = false; window.contentView = host; window.appearance = host.appearance
                defer { window.close() }
                window.makeKeyAndOrderFront(nil); host.layoutSubtreeIfNeeded()
                RunLoop.main.run(until: Date().addingTimeInterval(0.2))
                let bitmap = try XCTUnwrap(host.bitmapImageRepForCachingDisplay(in: host.bounds))
                host.cacheDisplay(in: host.bounds, to: bitmap)
                let data = try XCTUnwrap(bitmap.representation(using: .png, properties: [:]))
                try data.write(to: directory.appendingPathComponent("\(page)-\(language).png"))
            }
        }
    }
}
