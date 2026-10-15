import AppKit
import SwiftUI
import XCTest
@testable import OpenDictate

final class SettingsViewLayoutTests: XCTestCase {
    @MainActor func testScrollViewStartsAtTopWhenSyncIsDisabled() throws {
        let suite = "settings-layout-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let model = AppModel(localOnly: true, defaults: defaults)
        defer { model.shutdown() }

        for enabled in [false, true] {
            model.preferences.driveSyncEnabled = enabled
            let host = NSHostingView(rootView: SettingsView(model: model, preferences: model.preferences,
                history: model.history, replacements: model.replacements))
            host.appearance = NSAppearance(named: .darkAqua)
            host.frame = NSRect(x: 0, y: 0, width: 900, height: 650)
            host.layoutSubtreeIfNeeded()
            let scrollView = try XCTUnwrap(descendants(of: host).compactMap { $0 as? NSScrollView }.first)
            let scrollFrame = scrollView.convert(scrollView.bounds, to: host)
            let topInset = host.isFlipped ? scrollFrame.minY : host.bounds.maxY - scrollFrame.maxY
            if enabled {
                XCTAssertGreaterThan(topInset, 16, "Visible sync controls need their own header")
                XCTAssertLessThanOrEqual(topInset, 42, "Sync status and button must share one row")
            } else {
                XCTAssertEqual(topInset, 0, accuracy: 0.5, "Hidden sync controls must not reserve header space")
            }
            if let output = ProcessInfo.processInfo.environment["OPENDICTATE_VIEW_CAPTURES"] {
                let bitmap = try XCTUnwrap(host.bitmapImageRepForCachingDisplay(in: host.bounds))
                host.cacheDisplay(in: host.bounds, to: bitmap)
                let data = try XCTUnwrap(bitmap.representation(using: .png, properties: [:]))
                let directory = URL(fileURLWithPath: output)
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                try data.write(to: directory.appendingPathComponent("settings-sync-\(enabled).png"))
            }
        }
    }

    @MainActor private func descendants(of view: NSView) -> [NSView] {
        view.subviews.flatMap { [$0] + descendants(of: $0) }
    }
}
