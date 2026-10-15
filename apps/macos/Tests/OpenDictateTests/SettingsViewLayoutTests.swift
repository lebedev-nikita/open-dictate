import AppKit
import SwiftUI
import XCTest
@testable import OpenDictate

final class SettingsViewLayoutTests: XCTestCase {
    @MainActor func testScrollViewStartsAtTopRegardlessOfSyncVisibility() throws {
        let suite = "settings-layout-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let model = AppModel(localOnly: true, defaults: defaults)
        defer { model.shutdown() }

        for (width, language, appearance) in [(900.0, "en", NSAppearance.Name.darkAqua),
                                              (740.0, "ru", NSAppearance.Name.aqua)] {
            for enabled in [false, true] {
                model.preferences.driveSyncEnabled = enabled
                model.preferences.interfaceLanguage = language
                model.preferences.theme = appearance == .darkAqua ? .dark : .light
                let host = NSHostingView(rootView: SettingsView(model: model, preferences: model.preferences,
                    history: model.history, replacements: model.replacements))
                host.appearance = NSAppearance(named: appearance)
                host.frame = NSRect(x: 0, y: 0, width: width, height: 650)
                host.layoutSubtreeIfNeeded()
                let scrollView = try XCTUnwrap(descendants(of: host).compactMap { $0 as? NSScrollView }.first)
                let scrollFrame = scrollView.convert(scrollView.bounds, to: host)
                let topInset = host.isFlipped ? scrollFrame.minY : host.bounds.maxY - scrollFrame.maxY
                XCTAssertEqual(topInset, 0, accuracy: 0.5, "Sync controls in the sidebar must not reserve header space")
                if let output = ProcessInfo.processInfo.environment["OPENDICTATE_VIEW_CAPTURES"] {
                    let bitmap = try XCTUnwrap(host.bitmapImageRepForCachingDisplay(in: host.bounds))
                    host.cacheDisplay(in: host.bounds, to: bitmap)
                    let data = try XCTUnwrap(bitmap.representation(using: .png, properties: [:]))
                    let directory = URL(fileURLWithPath: output)
                    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                    try data.write(to: directory.appendingPathComponent("settings-sync-\(enabled)-\(language).png"))
                }
            }
        }
    }

    @MainActor private func descendants(of view: NSView) -> [NSView] {
        view.subviews.flatMap { [$0] + descendants(of: $0) }
    }
}
