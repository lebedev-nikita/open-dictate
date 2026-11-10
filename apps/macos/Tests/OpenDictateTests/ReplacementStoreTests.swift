import AppKit
import SwiftUI
import XCTest
@testable import OpenDictate

final class ReplacementStoreTests: XCTestCase {
    func testListSortsByReplacementIgnoringCaseAndReordersAfterEditing() async {
        await MainActor.run {
            let suite = "com.opendictate.tests.replacements.\(UUID().uuidString)"
            let defaults = UserDefaults(suiteName: suite)!
            defer { defaults.removePersistentDomain(forName: suite) }
            let store = ReplacementStore(defaults: defaults)
            XCTAssertTrue(store.save(source: "alpha", replacement: "Zebra"))
            XCTAssertTrue(store.save(source: "beta", replacement: "Apple pie"))
            XCTAssertTrue(store.save(source: "gamma", replacement: "banana"))
            XCTAssertEqual(store.rules.map(\.replacement), ["Apple pie", "banana", "Zebra"])
            let rule = store.rules.first { $0.source == "alpha" }!
            XCTAssertTrue(store.save(id: rule.id, source: rule.source, replacement: "apricot"))
            XCTAssertEqual(store.rules.map(\.replacement), ["Apple pie", "apricot", "banana"])
            XCTAssertEqual(ReplacementStore(defaults: defaults).rules.map(\.replacement), ["Apple pie", "apricot", "banana"])
        }
    }

    func testPatternRulesKeepWhitespaceRejectBadPatternsAndSyncAsVersionTwo() async throws {
        try await MainActor.run {
            let suite = "com.opendictate.tests.replacements.\(UUID().uuidString)"
            let defaults = UserDefaults(suiteName: suite)!
            defer { defaults.removePersistentDomain(forName: suite) }
            let store = ReplacementStore(defaults: defaults)
            XCTAssertFalse(store.save(source: "(", replacement: "x", regex: true))
            XCTAssertEqual(store.status, .invalidPattern)
            XCTAssertFalse(store.save(source: "(a)", replacement: "$2", regex: true))
            XCTAssertTrue(store.save(source: " {2,}", replacement: " ", regex: true))
            XCTAssertTrue(store.save(source: "\\s*\\bum\\b", replacement: "", regex: true))
            XCTAssertTrue(store.save(source: "C.T", replacement: "literal"))
            XCTAssertTrue(store.save(source: "c.t", replacement: "pattern", regex: true))
            XCTAssertFalse(store.save(source: "c.t", replacement: "duplicate", regex: true))
            XCTAssertEqual(store.status, .invalid)
            XCTAssertEqual(ReplacementStore(defaults: defaults).engine().apply("So um  c.t   cut"), "So literal pattern")
            let json = try XCTUnwrap(store.syncValues["wordReplacements"])
            XCTAssertTrue(json.contains(#""schemaVersion":2"#))
            XCTAssertNoThrow(try store.validateSync(json: json))
        }
    }

    func testCaseSensitiveRulesCoexistKeepTheOptionOnEditAndSyncAsVersionThree() async throws {
        try await MainActor.run {
            let suite = "com.opendictate.tests.replacements.\(UUID().uuidString)"
            let defaults = UserDefaults(suiteName: suite)!
            defer { defaults.removePersistentDomain(forName: suite) }
            let store = ReplacementStore(defaults: defaults)
            XCTAssertTrue(store.save(source: "ё", replacement: "е", regex: true, matchCase: true))
            XCTAssertTrue(store.save(source: "Ё", replacement: "Е", regex: true, matchCase: true))
            XCTAssertFalse(store.save(source: "ё", replacement: "duplicate", regex: true))
            XCTAssertTrue(store.save(source: "ИП", replacement: "upper", matchCase: true))
            XCTAssertTrue(store.save(source: "ип", replacement: "lower", matchCase: true))
            XCTAssertFalse(store.save(source: "Ип", replacement: "duplicate"))
            XCTAssertEqual(store.status, .invalid)
            XCTAssertEqual(ReplacementStore(defaults: defaults).engine().apply("Ёжик всё, ИП ип Ип"), "Ежик все, upper lower Ип")
            let rule = try XCTUnwrap(store.rules.first { $0.source == "ИП" })
            XCTAssertTrue(store.save(id: rule.id, source: "ИП", replacement: "edited", matchCase: rule.matchCase))
            XCTAssertEqual(store.rules.first { $0.id == rule.id }?.matchCase, true)
            let json = try XCTUnwrap(store.syncValues["wordReplacements"])
            XCTAssertTrue(json.contains(#""schemaVersion":3"#))
            XCTAssertNoThrow(try store.validateSync(json: json))
        }
    }

    func testEditsPersistAndSessionRulesAreImmutable() async {
        await MainActor.run {
            let suite = "com.opendictate.tests.replacements.\(UUID().uuidString)"
            let defaults = UserDefaults(suiteName: suite)!
            defer { defaults.removePersistentDomain(forName: suite) }
            let store = ReplacementStore(defaults: defaults)
            XCTAssertTrue(store.save(source: " cat ", replacement: " dog "))
            let rule = store.rules[0], active = store.engine()
            XCTAssertFalse(store.save(source: "CAT", replacement: "duplicate"))
            XCTAssertTrue(store.save(id: rule.id, source: "cat", replacement: "bird"))
            XCTAssertEqual(active.apply("cat"), "dog")
            XCTAssertEqual(ReplacementStore(defaults: defaults).engine().apply("cat"), "bird")
            store.enabled = false
            XCTAssertEqual(ReplacementStore(defaults: defaults).engine().apply("cat"), "cat")
            store.enabled = true; store.update(store.rules[0], enabled: false)
            XCTAssertEqual(ReplacementStore(defaults: defaults).engine().apply("cat"), "cat")
            store.delete(rule)
            XCTAssertTrue(ReplacementStore(defaults: defaults).rules.isEmpty)
        }
    }
    func testCorruptDocumentCannotBeOverwritten() async {
        await MainActor.run {
            let suite = "com.opendictate.tests.replacements.\(UUID().uuidString)"
            let defaults = UserDefaults(suiteName: suite)!
            defer { defaults.removePersistentDomain(forName: suite) }
            let data = Data("not-json".utf8)
            defaults.set(data, forKey: "wordReplacementDocument")
            let store = ReplacementStore(defaults: defaults)
            XCTAssertEqual(store.status, .storageError)
            XCTAssertFalse(store.save(source: "cat", replacement: "dog"))
            XCTAssertEqual(defaults.data(forKey: "wordReplacementDocument"), data)
        }
    }
}

final class ReplacementSettingsLayoutTests: XCTestCase {
    @MainActor func testNativeReplacementSettingsLayouts() throws {
        let suite = "replacement-layout-\(UUID().uuidString)", defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = Preferences(defaults: defaults)
        let store = preferences.replacements
        XCTAssertTrue(store.save(source: "open dictate", replacement: "OpenDictate"))
        XCTAssertTrue(store.save(source: "(\\d+) ?руб\\w*", replacement: "$1 ₽", regex: true))
        XCTAssertTrue(store.save(source: "\\s+([,.!?])", replacement: "$1", regex: true))
        for (language, dark, width) in [("en", false, 568.0), ("ru", true, 464.0)] {
            preferences.interfaceLanguage = language
            let host = NSHostingView(rootView: ReplacementSettingsView(store: store, preferences: preferences).padding(28)
                .frame(width: width, height: 760, alignment: .topLeading)
                .background(Color(nsColor: .windowBackgroundColor)))
            host.appearance = NSAppearance(named: dark ? .darkAqua : .aqua)
            host.frame = NSRect(x: 0, y: 0, width: width, height: 760)
            let window = NSWindow(contentRect: host.frame, styleMask: [.titled], backing: .buffered, defer: false)
            window.isReleasedWhenClosed = false; window.contentView = host; window.appearance = host.appearance
            window.makeKeyAndOrderFront(nil)
            host.layoutSubtreeIfNeeded()
            RunLoop.main.run(until: Date().addingTimeInterval(0.2))
            XCTAssertGreaterThan(host.fittingSize.width, 0)
            if let output = ProcessInfo.processInfo.environment["OPENDICTATE_VIEW_CAPTURES"] {
                let bitmap = try XCTUnwrap(host.bitmapImageRepForCachingDisplay(in: host.bounds))
                host.cacheDisplay(in: host.bounds, to: bitmap)
                let directory = URL(fileURLWithPath: output)
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                try XCTUnwrap(bitmap.representation(using: .png, properties: [:]))
                    .write(to: directory.appendingPathComponent("replacements-macos-\(language).png"))
            }
            window.close()
        }
    }
}
