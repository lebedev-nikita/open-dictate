import SwiftUI
import XCTest
@testable import OpenDictate

final class AppThemeTests: XCTestCase {
    @MainActor func testSwitchingToSystemUpdatesTheEntireSettingsWindow() async throws {
        let application = NSApplication.shared
        let originalAppearance = application.appearance
        defer { application.appearance = originalAppearance }
        let suite = "com.opendictate.tests.theme.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let model = AppModel(localOnly: true, defaults: defaults)
        defer { model.shutdown() }
        let host = NSHostingView(rootView: SettingsView(model: model, preferences: model.preferences,
            history: model.history, replacements: model.replacements))
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 790, height: 650),
                              styleMask: [.titled, .closable], backing: .buffered, defer: false)
        window.isReleasedWhenClosed = false
        window.contentView = host
        defer { window.close() }

        for (system, forced, expected) in [(NSAppearance.Name.aqua, AppTheme.dark, false),
                                           (.darkAqua, .light, true)] {
            application.appearance = NSAppearance(named: system)
            model.preferences.theme = forced
            try await Task.sleep(for: .milliseconds(150))
            XCTAssertEqual(window.effectiveAppearance.bestMatch(from: [.aqua, .darkAqua]),
                           forced.appearance?.name)
            try assertSettingsAppearance(host, dark: !expected)
            model.preferences.theme = .system
            try await Task.sleep(for: .milliseconds(150))
            XCTAssertNil(window.appearance, "System mode must remove the window override")
            XCTAssertEqual(window.effectiveAppearance.bestMatch(from: [.aqua, .darkAqua]), system)
            try assertSettingsAppearance(host, dark: expected)

            // System mode must keep following appearance changes after the switch.
            application.appearance = NSAppearance(named: expected ? .aqua : .darkAqua)
            try await Task.sleep(for: .milliseconds(150))
            try assertSettingsAppearance(host, dark: !expected)
        }
    }

    @MainActor private func assertSettingsAppearance(_ host: NSView, dark: Bool,
                                                    file: StaticString = #filePath, line: UInt = #line) throws {
        host.layoutSubtreeIfNeeded()
        let bitmap = try XCTUnwrap(host.bitmapImageRepForCachingDisplay(in: host.bounds))
        host.cacheDisplay(in: host.bounds, to: bitmap)
        // Empty areas of the sidebar and body catch stale SwiftUI surfaces even
        // when AppKit has already updated the title bar and divider.
        for x in [10, 270] {
            let color = try XCTUnwrap(bitmap.colorAt(x: x * bitmap.pixelsWide / 790,
                                                    y: 100 * bitmap.pixelsHigh / 650)?.usingColorSpace(.deviceRGB))
            if dark { XCTAssertLessThan(color.redComponent, 0.4, file: file, line: line) }
            else { XCTAssertGreaterThan(color.redComponent, 0.7, file: file, line: line) }
        }
    }

    func testSystemThemeLeavesAppearanceToMacOS() {
        XCTAssertNil(AppTheme.system.appearance)
        XCTAssertEqual(AppTheme.light.appearance?.name, .aqua)
        XCTAssertEqual(AppTheme.dark.appearance?.name, .darkAqua)
    }

    func testExistingPreferencesKeepDarkAppearanceAndUnknownValuesRecover() async {
        await MainActor.run {
            let suite = "com.opendictate.tests.theme.\(UUID().uuidString)"
            let defaults = UserDefaults(suiteName: suite)!
            defer { defaults.removePersistentDomain(forName: suite) }
            XCTAssertEqual(Preferences(defaults: defaults).theme, .dark)
            defaults.set("unknown", forKey: "theme")
            XCTAssertEqual(Preferences(defaults: defaults).theme, .dark)
        }
    }

    func testThemeChoiceSurvivesPreferencesReload() async {
        await MainActor.run {
            let suite = "com.opendictate.tests.theme.\(UUID().uuidString)"
            let defaults = UserDefaults(suiteName: suite)!
            defer { defaults.removePersistentDomain(forName: suite) }
            let preferences = Preferences(defaults: defaults)
            for theme in AppTheme.allCases {
                preferences.theme = theme
                XCTAssertEqual(Preferences(defaults: defaults).theme, theme)
            }
        }
    }
}
