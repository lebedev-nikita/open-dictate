import AppKit
import SwiftUI
import XCTest
import OpenDictateCore
@testable import OpenDictate

final class RecordingIndicatorTests: XCTestCase {
    @MainActor func testProcessingStagesKeepPanelBoundsAndNeverTakeFocus() throws {
        let indicator = RecordingIndicator(), levels = RecordingAudioLevels()
        let keyWindow = NSApplication.shared.keyWindow
        let process = NSWorkspace.shared.frontmostApplication?.processIdentifier
        defer { indicator.hide() }
        for style in RecordingIndicatorStyle.allCases {
            var bounds: NSRect?
            for stage in DictationProcessingStage.allCases {
                indicator.show(phase: .processing, style: style, audioLevels: levels, isRussian: true,
                    stage: stage, showsStageIcon: true, finish: {})
                let panel = try XCTUnwrap(NSApplication.shared.windows.first {
                    $0.title == "OpenDictate recording indicator" && $0.isVisible
                })
                if let bounds { XCTAssertEqual(panel.frame, bounds) } else { bounds = panel.frame }
                XCTAssertFalse(panel.canBecomeKey); XCTAssertFalse(panel.canBecomeMain)
                XCTAssertTrue(panel.ignoresMouseEvents)
                XCTAssertTrue(NSApplication.shared.keyWindow === keyWindow)
                XCTAssertEqual(NSWorkspace.shared.frontmostApplication?.processIdentifier, process)
            }
        }
    }

    @MainActor func testNativeProcessingStageLayouts() throws {
        let host = NSHostingView(rootView: VStack(alignment: .leading, spacing: 20) {
            ForEach([false, true], id: \.self) { russian in
                HStack(spacing: 24) {
                    ForEach(Array(DictationProcessingStage.allCases.enumerated()), id: \.offset) { _, stage in
                        VStack(spacing: 8) {
                            RecordingStatusView(phase: .processing, isRussian: russian, stage: stage, showsStageIcon: true, finish: {})
                            Text(stage.label(russian: russian)).font(.caption)
                        }
                    }
                    VStack(spacing: 8) {
                        RecordingStatusView(phase: .processing, isRussian: russian, stage: .transcription, finish: {})
                        Text(russian ? "Без постобработки" : "Without post-processing").font(.caption)
                    }
                }
            }
        }.padding(24).frame(width: 650, height: 200).background(Color(nsColor: .windowBackgroundColor)))
        host.appearance = NSAppearance(named: .darkAqua)
        host.frame = NSRect(x: 0, y: 0, width: 650, height: 200)
        host.layoutSubtreeIfNeeded()
        if let output = ProcessInfo.processInfo.environment["OPENDICTATE_VIEW_CAPTURES"] {
            let bitmap = try XCTUnwrap(host.bitmapImageRepForCachingDisplay(in: host.bounds))
            host.cacheDisplay(in: host.bounds, to: bitmap)
            try XCTUnwrap(bitmap.representation(using: .png, properties: [:]))
                .write(to: URL(fileURLWithPath: output).appendingPathComponent("processing-stages-macos.png"))
        }
    }

    func testWaveformPanelNeverTakesFocusAndIgnoresProcessingClicks() async throws {
        try await MainActor.run {
            let application = NSApplication.shared
            let originalKeyWindow = application.keyWindow
            let originalProcess = NSWorkspace.shared.frontmostApplication?.processIdentifier
            let indicator = RecordingIndicator()
            defer { indicator.hide() }
            indicator.show(phase: .preparing, style: .waveform, audioLevels: RecordingAudioLevels(),
                           isRussian: false, finish: {})
            let panel = try XCTUnwrap(application.windows.first {
                $0.title == "OpenDictate recording indicator" && $0.isVisible
            } as? NSPanel)
            XCTAssertEqual(panel.contentLayoutRect.size, NSSize(width: 63, height: 29))
            XCTAssertFalse(panel.canBecomeKey)
            XCTAssertFalse(panel.canBecomeMain)
            XCTAssertFalse(panel.ignoresMouseEvents)
            XCTAssertTrue(application.keyWindow === originalKeyWindow)
            XCTAssertEqual(NSWorkspace.shared.frontmostApplication?.processIdentifier, originalProcess)
            indicator.show(phase: .processing, style: .waveform, audioLevels: RecordingAudioLevels(),
                           isRussian: false, finish: {})
            XCTAssertTrue(panel.ignoresMouseEvents)
            XCTAssertEqual(panel.contentLayoutRect.size, NSSize(width: 28, height: 28))
            indicator.show(phase: .recording, style: .waveform, audioLevels: RecordingAudioLevels(),
                           isRussian: false, finish: {})
            XCTAssertEqual(panel.contentLayoutRect.size, NSSize(width: 63, height: 29))
            XCTAssertFalse(panel.ignoresMouseEvents)
            XCTAssertTrue(application.keyWindow === originalKeyWindow)
            XCTAssertEqual(NSWorkspace.shared.frontmostApplication?.processIdentifier, originalProcess)
            indicator.show(phase: .idle, style: .waveform, audioLevels: RecordingAudioLevels(),
                           isRussian: false, finish: {})
            XCTAssertFalse(panel.isVisible)
        }
    }

    @MainActor func testFinishControlCannotTakeFocusAndDefersActionUntilAfterClick() async throws {
        let view = IndicatorFinishView(frame: NSRect(x: 0, y: 0, width: 20, height: 20))
        let originalWindow = NSApplication.shared.keyWindow
        let originalProcess = NSWorkspace.shared.frontmostApplication?.processIdentifier
        let finished = expectation(description: "Finish runs after the mouse event")
        var called = false
        view.finish = { called = true; finished.fulfill() }
        XCTAssertFalse(view.acceptsFirstResponder)
        XCTAssertTrue(view.acceptsFirstMouse(for: nil))
        XCTAssertTrue(view.accessibilityPerformPress())
        XCTAssertFalse(called)
        await fulfillment(of: [finished], timeout: 1)
        XCTAssertTrue(NSApplication.shared.keyWindow === originalWindow)
        XCTAssertEqual(NSWorkspace.shared.frontmostApplication?.processIdentifier, originalProcess)
    }

    func testPreparingAlreadyShowsFinishControlAtLargerSize() async throws {
        try await MainActor.run {
            let image = try render(.preparing)
            XCTAssertEqual(image.pixelsWide, 55)
            XCTAssertEqual(image.pixelsHigh, 28)
            XCTAssertNotNil(redBounds(image))
        }
    }

    func testFinishCircleHasEqualTopBottomAndTrailingInsets() async throws {
        try await MainActor.run {
            for phase in [AppModel.Phase.preparing, .recording] {
                let image = try render(phase)
                XCTAssertEqual(image.pixelsWide, 55)
                XCTAssertEqual(image.pixelsHigh, 28)
                let bounds = try XCTUnwrap(redBounds(image))
                let trailingInset = CGFloat(image.pixelsWide) - bounds.maxX
                XCTAssertEqual(trailingInset, 5, accuracy: 1)
                XCTAssertEqual(bounds.minY, trailingInset, accuracy: 1)
                XCTAssertEqual(CGFloat(image.pixelsHigh) - bounds.maxY, trailingInset, accuracy: 1)
                XCTAssertEqual(bounds.width, 17.5, accuracy: 2)
            }
        }
    }

    func testWaveformHasTenBarsAndNewestInputChangesRightmostHeight() async throws {
        try await MainActor.run {
            let levels = RecordingAudioLevels()
            let quiet = try render(.recording, style: .waveform, levels: levels)
            XCTAssertEqual(quiet.pixelsWide, 63)
            XCTAssertEqual(quiet.pixelsHigh, 29)
            XCTAssertNil(redBounds(quiet))
            let quietBars = whiteBars(quiet)
            XCTAssertEqual(quietBars.count, 10)
            levels.append(1, at: .zero)
            let loud = try render(.recording, style: .waveform, levels: levels)
            let loudBars = whiteBars(loud)
            XCTAssertEqual(loudBars.count, 10)
            XCTAssertEqual(Array(loudBars.dropLast()), Array(quietBars.dropLast()))
            let loudBar = try XCTUnwrap(loudBars.last)
            XCTAssertGreaterThan(loudBar.height, try XCTUnwrap(quietBars.last).height + 12)
            XCTAssertEqual(loudBar.height, 17.5, accuracy: 1)
            XCTAssertEqual(loudBar.minY, 5.41, accuracy: 1)
            XCTAssertEqual(CGFloat(loud.pixelsHigh) - loudBar.maxY, 5.41, accuracy: 1)
            for index in 1..<10 { levels.append(1, at: .milliseconds(index * 100)) }
            let fullLevel = try render(.recording, style: .waveform, levels: levels)
            let fullLevelBars = whiteBars(fullLevel)
            XCTAssertEqual(fullLevelBars.count, 10)
            for bar in fullLevelBars {
                XCTAssertEqual(bar.height, 17.5, accuracy: 1)
                XCTAssertEqual(bar.minY, 5.41, accuracy: 1)
                XCTAssertEqual(CGFloat(fullLevel.pixelsHigh) - bar.maxY, 5.41, accuracy: 1)
            }
            let waveformBounds = try XCTUnwrap(fullLevelBars.reduce(nil as CGRect?) { $0?.union($1) ?? $1 })
            XCTAssertEqual(waveformBounds.width, 38.63, accuracy: 1)
            // Fractional bar edges are antialiased before the capture is rounded to whole pixels.
            XCTAssertEqual(waveformBounds.minX, 11.94, accuracy: 1.5)
            XCTAssertEqual(CGFloat(fullLevel.pixelsWide) - waveformBounds.maxX, 11.94, accuracy: 1.5)
            let size = RecordingStatusView.size(phase: .recording, style: .waveform)
            XCTAssertEqual(size.height / 17.5, (1 + sqrt(5)) / 2, accuracy: 0.001)
            XCTAssertEqual(size.width / waveformBounds.width, (1 + sqrt(5)) / 2, accuracy: 0.05)
        }
    }

    @MainActor func testMicrophoneStartupDoesNotShowAListeningWaveformOrMoveTheFinishControl() throws {
        let preparing = try render(.preparing, style: .waveform)
        let recording = try render(.recording, style: .waveform)
        XCTAssertEqual(preparing.pixelsWide, recording.pixelsWide)
        XCTAssertEqual(preparing.pixelsHigh, recording.pixelsHigh)
        XCTAssertNotEqual(whiteBars(preparing).count, 10, "Starting must be distinct from listening in silence")
        XCTAssertTrue(AppModel.Phase.preparing.showsFinishControl)
    }

    func testWaveformProcessingShrinksToCircleWithRestoredVerticalSpace() async throws {
        try await MainActor.run {
            let image = try render(.processing, style: .waveform)
            XCTAssertEqual(RecordingStatusView.size(phase: .processing, style: .waveform),
                           NSSize(width: 27.5, height: 27.5))
            XCTAssertEqual(image.pixelsWide, 28)
            XCTAssertEqual(image.pixelsHigh, 28)
            for (x, y) in [(0, 0), (27, 0), (0, 27), (27, 27)] {
                XCTAssertLessThan(try XCTUnwrap(image.colorAt(x: x, y: y)).alphaComponent, 0.1)
            }
            XCTAssertGreaterThan(try XCTUnwrap(image.colorAt(x: 14, y: 2)).alphaComponent, 0.9)
            XCTAssertGreaterThan(try XCTUnwrap(image.colorAt(x: 2, y: 14)).alphaComponent, 0.9)
        }
    }

    @MainActor private func render(_ phase: AppModel.Phase, style: RecordingIndicatorStyle = .compact,
                                   levels: RecordingAudioLevels? = nil) throws -> NSBitmapImageRep {
        let size = RecordingStatusView.size(phase: phase, style: style)
        let view = NSHostingView(rootView: RecordingStatusView(phase: phase, style: style, audioLevels: levels,
                                                              isRussian: false, finish: {}))
        view.appearance = NSAppearance(named: .darkAqua)
        view.frame = NSRect(origin: .zero, size: size)
        view.layoutSubtreeIfNeeded()
        let bitmap = try XCTUnwrap(view.bitmapImageRepForCachingDisplay(in: view.bounds))
        view.cacheDisplay(in: view.bounds, to: bitmap)
        let source = try XCTUnwrap(bitmap.cgImage)
        let width = Int(ceil(size.width)), height = Int(ceil(size.height))
        let context = try XCTUnwrap(CGContext(data: nil, width: width, height: height, bitsPerComponent: 8,
                                             bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(),
                                             bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
        context.draw(source, in: CGRect(x: 0, y: 0, width: width, height: height))
        let result = NSBitmapImageRep(cgImage: try XCTUnwrap(context.makeImage()))
        if ProcessInfo.processInfo.environment["OPENDICTATE_RENDER_TESTS"] == "1", let png = result.representation(using: .png, properties: [:]) {
            try png.write(to: URL(fileURLWithPath: "/tmp/opendictate-\(style.rawValue)-\(phase).png"))
        }
        return result
    }

    private func whiteBars(_ image: NSBitmapImageRep) -> [CGRect] {
        var bars = [CGRect]()
        for x in 10..<(image.pixelsWide - 10) {
            var column: CGRect?
            for y in 2..<(image.pixelsHigh - 2) {
                guard let color = image.colorAt(x: x, y: y)?.usingColorSpace(.deviceRGB),
                      min(color.redComponent, color.greenComponent, color.blueComponent) > 0.5 else { continue }
                let pixel = CGRect(x: x, y: y, width: 1, height: 1)
                column = column.map { $0.union(pixel) } ?? pixel
            }
            if let column {
                if let previous = bars.last, previous.maxX == column.minX {
                    bars[bars.count - 1] = previous.union(column)
                } else { bars.append(column) }
            }
        }
        return bars
    }

    private func redBounds(_ image: NSBitmapImageRep) -> CGRect? {
        var bounds: CGRect?
        for y in 0..<image.pixelsHigh {
            for x in 0..<image.pixelsWide {
                guard let color = image.colorAt(x: x, y: y)?.usingColorSpace(.deviceRGB),
                      color.redComponent > 0.7, color.greenComponent < 0.45,
                      color.blueComponent < 0.45, color.alphaComponent > 0.8 else { continue }
                let pixel = CGRect(x: x, y: y, width: 1, height: 1)
                bounds = bounds.map { $0.union(pixel) } ?? pixel
            }
        }
        return bounds
    }
}
