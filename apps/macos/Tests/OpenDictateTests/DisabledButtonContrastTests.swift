import AppKit
import SwiftUI
import Vision
import XCTest
@testable import OpenDictate

/// Disabled buttons must stay readable yet look disabled. macOS draws a disabled label at about 1.9:1
/// against its bezel in light mode and 2.2:1 in dark mode; the app keeps it at least at 3:1, the WCAG
/// minimum for interface components, and well below the contrast of an enabled label.
final class DisabledButtonContrastTests: XCTestCase {
    private static let appearances: [NSAppearance.Name] = [.aqua, .darkAqua]

    /// A solid label swatch measures the exact label color the style resolves, free of glyph antialiasing.
    @MainActor func testDisabledLabelColorMeetsTextContrastOnEveryButtonKind() throws {
        let swatch = Rectangle().frame(width: 28, height: 14)
        let kinds: [(String, AnyView)] = [
            ("regular", AnyView(Button {} label: { swatch })),
            ("large", AnyView(Button {} label: { swatch }.controlSize(.large))),
            ("small", AnyView(Button {} label: { swatch }.controlSize(.small))),
            ("destructive", AnyView(Button(role: .destructive) {} label: { swatch }.controlSize(.large))),
            ("default action", AnyView(Button {} label: { swatch }.controlSize(.large).keyboardShortcut(.defaultAction))),
            ("borderless", AnyView(Button {} label: { swatch }.buttonStyle(.legibleBorderless))),
        ]
        for appearance in Self.appearances {
            for (name, button) in kinds {
                let disabled = try swatchContrast(button.disabled(true), appearance: appearance)
                XCTAssertGreaterThanOrEqual(disabled, 3.3, "\(name) disabled label in \(appearance.rawValue)")
                if name != "borderless" {
                    // A disabled label still reads as disabled next to an enabled one.
                    let enabled = try swatchContrast(button, appearance: appearance)
                    XCTAssertGreaterThan(enabled, disabled * 2, "\(name) enabled label in \(appearance.rawValue)")
                }
            }
        }
    }

    /// The disabled buttons the app shows without an API key, found by their visible titles.
    /// Native disabled titles are too faint to recognize, so a missing title also fails.
    @MainActor func testDisabledButtonsInSettingsAndMeetingWindowsStayReadable() throws {
        let suite = "disabled-button-contrast-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let model = AppModel(localOnly: true, defaults: defaults)
        defer { model.shutdown() }
        model.preferences.interfaceLanguage = "en"
        XCTAssertFalse(model.hasKey, "Without a key, Save, Remove API key and Start Recording are disabled")
        let controller = MeetingController(store: MeetingStore(inMemory: true), preferences: model.preferences,
                                           replacements: model.replacements)
        func settings(_ page: String) -> AnyView {
            AnyView(SettingsView(model: model, preferences: model.preferences, history: model.history,
                                 replacements: model.replacements, page: page))
        }
        let screens: [(AnyView, NSSize, [String])] = [
            (settings("dictation"), NSSize(width: 790, height: 650), ["Save"]),
            (settings("general"), NSSize(width: 790, height: 1500), ["Remove API key…"]),
            (settings("shortcuts"), NSSize(width: 790, height: 650), ["Restore default shortcuts"]),
            (AnyView(MeetingView(controller: controller, preferences: model.preferences, model: model,
                                 shownMeeting: .constant(nil), openSettings: { _, _ in })),
             NSSize(width: 520, height: 620), ["Start Recording"]),
        ]
        for appearance in Self.appearances {
            model.preferences.theme = appearance == .darkAqua ? .dark : .light
            for (view, size, titles) in screens {
                let host = NSHostingView(rootView: view)
                try withWindow(host, size: size, appearance: appearance) { bitmap in
                    let lines = try recognizedLines(in: bitmap)
                    for title in titles {
                        let box = try XCTUnwrap(lines.first { $0.text == title }?.box,
                                                "\(title) is unreadable in \(appearance.rawValue)")
                        // The strongest glyph pixel against the bezel: native disabled titles measure about 1.9:1.
                        let contrast = glyphContrast(bitmap, in: box.insetBy(dx: -2, dy: -2))
                        XCTAssertGreaterThanOrEqual(contrast, 3, "\(title) in \(appearance.rawValue)")
                    }
                }
            }
        }
    }

    /// The shortcut recorder is an AppKit button and draws its own disabled title.
    @MainActor func testDisabledShortcutRecorderStaysReadable() throws {
        for appearance in Self.appearances {
            var contrasts: [Bool: Double] = [:]
            for enabled in [true, false] {
                let recorder = ShortcutRecorder(shortcut: .init(keyCode: 49, flags: .option), title: "Dictation",
                    emptyLabel: "Not set", prompt: "Press and release…", enabled: enabled, onRecording: { _ in }, onSave: { _ in })
                let host = NSHostingView(rootView: recorder.frame(width: 160, height: 28).padding(20).background(Surface.card))
                try withWindow(host, size: NSSize(width: 200, height: 68), appearance: appearance) { bitmap in
                    let button = CGRect(x: 20, y: 20, width: 160, height: 28)
                    contrasts[enabled] = glyphContrast(bitmap, in: button.insetBy(dx: 12, dy: 6))
                }
            }
            let enabled = try XCTUnwrap(contrasts[true]), disabled = try XCTUnwrap(contrasts[false])
            XCTAssertGreaterThanOrEqual(disabled, 3, "disabled recorder in \(appearance.rawValue)")
            XCTAssertGreaterThan(enabled, disabled * 2, "enabled recorder in \(appearance.rawValue)")
        }
    }

    /// The style wraps every button, so Return must still reach an enabled default button and never a disabled one.
    @MainActor func testReturnTriggersOnlyAnEnabledDefaultButton() throws {
        for enabled in [true, false] {
            var presses = 0
            let host = NSHostingView(rootView: Button("Start Recording") { presses += 1 }
                .keyboardShortcut(.defaultAction).disabled(!enabled).buttonStyle(.legible).padding(20))
            try withWindow(host, size: NSSize(width: 200, height: 80), appearance: .aqua) { _ in
                let window = try XCTUnwrap(host.window)
                let event = try XCTUnwrap(NSEvent.keyEvent(with: .keyDown, location: .zero, modifierFlags: [], timestamp: 0,
                    windowNumber: window.windowNumber, context: nil, characters: "\r", charactersIgnoringModifiers: "\r",
                    isARepeat: false, keyCode: 36))
                _ = window.performKeyEquivalent(with: event)
                RunLoop.main.run(until: Date().addingTimeInterval(0.1))
            }
            XCTAssertEqual(presses, enabled ? 1 : 0, "Return with the button \(enabled ? "enabled" : "disabled")")
        }
    }

    // MARK: - Measuring

    @MainActor private func swatchContrast(_ button: some View, appearance: NSAppearance.Name) throws -> Double {
        let host = NSHostingView(rootView: button.buttonStyle(.legible).padding(20).background(Surface.card))
        var contrast = 0.0
        try withWindow(host, size: host.fittingSize, appearance: appearance) { bitmap in
            // The swatch fills the center; the bezel (or card) shows right beside it.
            let center = CGPoint(x: host.bounds.midX, y: host.bounds.midY)
            let label = pixel(bitmap, at: center)
            let bezel = pixel(bitmap, at: CGPoint(x: center.x - 14 - 3, y: center.y))
            contrast = Self.contrast(label, bezel)
        }
        return contrast
    }

    @MainActor private func withWindow(_ view: NSView, size: NSSize, appearance: NSAppearance.Name,
                                       _ body: (NSBitmapImageRep) throws -> Void) throws {
        view.appearance = NSAppearance(named: appearance)
        view.frame = NSRect(origin: .zero, size: size)
        let window = NSWindow(contentRect: view.frame, styleMask: [.titled], backing: .buffered, defer: false)
        window.isReleasedWhenClosed = false; window.appearance = view.appearance; window.contentView = view
        defer { window.close() }
        window.makeKeyAndOrderFront(nil); view.layoutSubtreeIfNeeded()
        RunLoop.main.run(until: Date().addingTimeInterval(0.15))
        // An sRGB bitmap read as raw samples: WCAG luminance is defined on sRGB, and the default caching
        // bitmap uses the display profile, which NSColor conversion misreads as generic RGB.
        let bitmap = try XCTUnwrap(NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: Int(size.width) * 2,
            pixelsHigh: Int(size.height) * 2, bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
            colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 32)?.retagging(with: .sRGB))
        bitmap.size = view.bounds.size
        view.cacheDisplay(in: view.bounds, to: bitmap)
        try body(bitmap)
    }

    /// The highest contrast any pixel reaches against the region's dominant color, the bezel.
    /// Regions and points are in view points from the top-left corner.
    private func glyphContrast(_ bitmap: NSBitmapImageRep, in rect: CGRect) -> Double {
        let scale = CGFloat(bitmap.pixelsWide) / bitmap.size.width
        let xs = max(0, Int(rect.minX * scale))..<min(bitmap.pixelsWide, Int(rect.maxX * scale))
        let ys = max(0, Int(rect.minY * scale))..<min(bitmap.pixelsHigh, Int(rect.maxY * scale))
        var pixels: [SIMD3<Double>] = []
        var counts: [SIMD3<Double>: Int] = [:]
        for y in ys { for x in xs {
            let value = rgb(bitmap, x: x, y: y)
            pixels.append(value); counts[value, default: 0] += 1
        } }
        guard let bezel = counts.max(by: { $0.value < $1.value })?.key else { return 0 }
        return pixels.map { Self.contrast($0, bezel) }.max() ?? 0
    }

    private func pixel(_ bitmap: NSBitmapImageRep, at point: CGPoint) -> SIMD3<Double> {
        let scale = CGFloat(bitmap.pixelsWide) / bitmap.size.width
        return rgb(bitmap, x: Int(point.x * scale), y: Int(point.y * scale))
    }

    private func rgb(_ bitmap: NSBitmapImageRep, x: Int, y: Int) -> SIMD3<Double> {
        var sample = [Int](repeating: 0, count: 4)
        bitmap.getPixel(&sample, atX: x, y: y)
        return SIMD3(Double(sample[0]), Double(sample[1]), Double(sample[2])) / 255
    }

    /// WCAG 2 contrast ratio between two sRGB colors.
    static func contrast(_ a: SIMD3<Double>, _ b: SIMD3<Double>) -> Double {
        func luminance(_ c: SIMD3<Double>) -> Double {
            func linear(_ v: Double) -> Double { v <= 0.04045 ? v / 12.92 : pow((v + 0.055) / 1.055, 2.4) }
            return 0.2126 * linear(c.x) + 0.7152 * linear(c.y) + 0.0722 * linear(c.z)
        }
        let (l1, l2) = (luminance(a), luminance(b))
        return (max(l1, l2) + 0.05) / (min(l1, l2) + 0.05)
    }

    func testContrastFormulaMatchesWCAGReferences() {
        XCTAssertEqual(Self.contrast(.zero, .one), 21, accuracy: 0.01)
        XCTAssertEqual(Self.contrast(SIMD3(repeating: 118.0 / 255), .one), 4.54, accuracy: 0.01)
    }

    // MARK: - Locating titles

    /// Text lines recognized in a bitmap, with boxes in top-left view points.
    private func recognizedLines(in bitmap: NSBitmapImageRep) throws -> [(text: String, box: CGRect)] {
        let image = try XCTUnwrap(bitmap.cgImage)
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .fast; request.usesLanguageCorrection = false
        try VNImageRequestHandler(cgImage: image).perform([request])
        let size = bitmap.size
        return (request.results ?? []).compactMap { observation in
            guard let text = observation.topCandidates(1).first?.string else { return nil }
            let box = observation.boundingBox
            return (text.replacingOccurrences(of: "...", with: "…"),
                    CGRect(x: box.minX * size.width, y: (1 - box.maxY) * size.height,
                           width: box.width * size.width, height: box.height * size.height))
        }
    }
}
