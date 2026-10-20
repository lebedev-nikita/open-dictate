import AppKit
import SwiftUI
import XCTest
import OpenDictateCore
@testable import OpenDictate

final class HistoryTimingPreferencesTests: XCTestCase {
    @MainActor func testLegacyHistoryFileAndDurationsSurvivePersistence() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("history.json")
        try Data(#"[{"id":"3E485AF8-BE63-4C72-8CDA-EAA66C3AE195","date":0,"text":"Legacy synthetic text","mode":"accurate"}]"#.utf8).write(to: url)
        let store = HistoryStore(fileURL: url)
        XCTAssertNil(store.error)
        let old = try XCTUnwrap(store.entries.first)
        XCTAssertNil(old.transcriptionMillis); XCTAssertNil(old.postProcessingMillis)
        store.add("Measured synthetic text", mode: "accurate", transcriptionMillis: 1250, postProcessingMillis: 450,
                  transcriptionModelID: "test-transcribe", postProcessingModelID: TextProcessingModel.id,
                  postProcessingServiceTier: "default")
        store.add("Live synthetic text", mode: "live", transcriptionMillis: 9000)
        let reopened = HistoryStore(fileURL: url)
        XCTAssertNil(reopened.error)
        XCTAssertEqual(reopened.entries, store.entries)
        XCTAssertEqual(reopened.entries.last, old)
        XCTAssertEqual(reopened.entries[1].transcriptionMillis, 1250)
        XCTAssertEqual(reopened.entries[1].postProcessingMillis, 450)
        XCTAssertEqual(reopened.entries[1].transcriptionModelID, "test-transcribe")
        XCTAssertEqual(reopened.entries[1].postProcessingModelID, TextProcessingModel.id)
        XCTAssertNil(reopened.entries.last?.transcriptionModelID)
        XCTAssertNil(reopened.entries[0].transcriptionMillis)
    }

    @MainActor func testNativeHistoryMetadataLayouts() throws {
        let date = Date(timeIntervalSince1970: 1_760_000_000)
        let entries = [
            HistoryEntry(date: date, text: "Synthetic Accurate transcript with both request timings.", mode: "accurate", transcriptionMillis: 1250, postProcessingMillis: 450,
                transcriptionModelID: "gpt-transcribe", postProcessingModelID: TextProcessingModel.id, postProcessingServiceTier: "default"),
            HistoryEntry(date: date, text: "Synthetic Live transcript.", mode: "live", transcriptionModelID: "gpt-live-transcribe"),
            HistoryEntry(date: date, text: "Synthetic transcript without post-processing.", mode: "accurate", transcriptionMillis: 800),
            HistoryEntry(date: date, text: "Synthetic legacy transcript without recorded timings.", mode: "accurate")
        ]
        for (language, dark, width) in [("en", true, 568.0), ("ru", false, 464.0)] {
            let host = NSHostingView(rootView: VStack(alignment: .leading, spacing: 18) {
                ForEach(entries) { entry in HistoryEntryView(entry: entry, russian: language == "ru", onCopy: {}, onDelete: {}) }
            }.padding(28).frame(width: width, height: 650, alignment: .topLeading)
                .environment(\.locale, Locale(identifier: language))
                .background(Color(nsColor: .windowBackgroundColor)))
            host.appearance = NSAppearance(named: dark ? .darkAqua : .aqua)
            host.frame = NSRect(x: 0, y: 0, width: width, height: 650)
            let window = NSWindow(contentRect: host.frame, styleMask: [.titled], backing: .buffered, defer: false)
            window.isReleasedWhenClosed = false; window.contentView = host; window.appearance = host.appearance
            window.makeKeyAndOrderFront(nil); host.layoutSubtreeIfNeeded()
            RunLoop.main.run(until: Date().addingTimeInterval(0.2))
            if let output = ProcessInfo.processInfo.environment["OPENDICTATE_VIEW_CAPTURES"] {
                let bitmap = try XCTUnwrap(host.bitmapImageRepForCachingDisplay(in: host.bounds))
                host.cacheDisplay(in: host.bounds, to: bitmap)
                try XCTUnwrap(bitmap.representation(using: .png, properties: [:]))
                    .write(to: URL(fileURLWithPath: output).appendingPathComponent("history-timing-macos-\(language).png"))
            }
            window.close()
        }
    }
}
