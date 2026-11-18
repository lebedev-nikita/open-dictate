import AppKit
import XCTest
import TestWindowHiding

final class TestWindowHidingTests: XCTestCase {
    @MainActor func testWindowsOrderedFrontByTestsStayOffScreen() throws {
        try XCTSkipIf(ProcessInfo.processInfo.environment["OPENDICTATE_SHOW_TEST_WINDOWS"] != nil)
        XCTAssertTrue(ODTestWindowsHidden())
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 200, height: 100), styleMask: [.titled],
                              backing: .buffered, defer: false)
        let panel = NSPanel(contentRect: NSRect(x: 0, y: 0, width: 200, height: 100), styleMask: [.nonactivatingPanel],
                            backing: .buffered, defer: false)
        window.isReleasedWhenClosed = false; panel.isReleasedWhenClosed = false
        defer { window.close(); panel.close() }
        window.makeKeyAndOrderFront(nil); panel.orderFrontRegardless()
        XCTAssertTrue(window.isVisible)
        XCTAssertEqual(window.alphaValue, 0)
        XCTAssertEqual(panel.alphaValue, 0)
    }
}
