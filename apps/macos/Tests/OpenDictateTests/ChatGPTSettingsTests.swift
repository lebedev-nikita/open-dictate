import AppKit
import SwiftUI
import XCTest
@testable import OpenDictate

final class ChatGPTSettingsTests: XCTestCase {
    @MainActor func testNativeKeyGatedConnectionLayouts() throws {
        let suite = "chatgpt-ui-\(UUID().uuidString)", defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let model = AppModel(localOnly: true, defaults: defaults)
        let connection = model.chatGPT
        for (language, dark, hasKey, width) in [("en", true, false, 568.0), ("ru", false, false, 464.0), ("en", true, true, 568.0), ("ru", false, true, 464.0)] {
            model.preferences.interfaceLanguage = language
            let host = NSHostingView(rootView: VStack(alignment: .leading, spacing: 14) {
                Text(language == "ru" ? "Интеграции" : "Integrations").fontWeight(.medium)
                GoogleDriveSettingsView(sync: model.driveSync, preferences: model.preferences)
                Divider()
                ChatGPTSettingsView(connection: connection, hasKey: hasKey, active: false, russian: language == "ru", connect: {})
            }.padding(28).frame(width: width, height: 350, alignment: .topLeading).background(Color(nsColor: .windowBackgroundColor)))
            host.appearance = NSAppearance(named: dark ? .darkAqua : .aqua)
            host.frame = NSRect(x: 0, y: 0, width: width, height: 350)
            let window = NSWindow(contentRect: host.frame, styleMask: [.titled], backing: .buffered, defer: false)
            window.isReleasedWhenClosed = false; window.contentView = host; window.appearance = host.appearance
            window.makeKeyAndOrderFront(nil); host.layoutSubtreeIfNeeded()
            RunLoop.main.run(until: Date().addingTimeInterval(0.2))
            if let output = ProcessInfo.processInfo.environment["OPENDICTATE_VIEW_CAPTURES"] {
                let bitmap = try XCTUnwrap(host.bitmapImageRepForCachingDisplay(in: host.bounds))
                host.cacheDisplay(in: host.bounds, to: bitmap)
                try XCTUnwrap(bitmap.representation(using: .png, properties: [:]))
                    .write(to: URL(fileURLWithPath: output).appendingPathComponent("chatgpt-macos-\(language)-\(hasKey ? "ready" : "locked").png"))
            }
            window.close()
        }
    }
}
