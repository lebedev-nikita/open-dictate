import AppKit
import XCTest
@testable import OpenDictate

final class MenuBarIconTests: XCTestCase {
    func testOnlyRecordingUsesAColoredCircleWithoutResizingTheMenuItem() async throws {
        try await MainActor.run {
            let recording = try XCTUnwrap(MenuBarIcon.image(for: .recording))
            XCTAssertFalse(recording.isTemplate)
            XCTAssertEqual(recording.size, NSSize(width: 20, height: 20))
            for phase in [AppModel.Phase.idle, .preparing, .processing] {
                let image = try XCTUnwrap(MenuBarIcon.image(for: phase))
                XCTAssertTrue(image.isTemplate)
                XCTAssertEqual(image.size, recording.size)
                XCTAssertEqual(image.accessibilityDescription, "OpenDictate")
            }
        }
    }

    func testRecordingCentersAWhiteMicrophoneInsideARedCircleInBothAppearances() async throws {
        try await MainActor.run {
            for name in [NSAppearance.Name.aqua, .darkAqua] {
                let appearance = try XCTUnwrap(NSAppearance(named: name))
                for scale in [1, 2, 8] {
                    let image = try XCTUnwrap(MenuBarIcon.image(for: .recording))
                    let bitmap = try XCTUnwrap(NSBitmapImageRep(bitmapDataPlanes: nil,
                                                              pixelsWide: 20 * scale, pixelsHigh: 20 * scale,
                                                              bitsPerSample: 8, samplesPerPixel: 4,
                                                              hasAlpha: true, isPlanar: false,
                                                              colorSpaceName: .deviceRGB,
                                                              bytesPerRow: 0, bitsPerPixel: 0))
                    bitmap.size = image.size
                    appearance.performAsCurrentDrawingAppearance {
                        NSGraphicsContext.saveGraphicsState()
                        NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: bitmap)
                        image.draw(in: NSRect(origin: .zero, size: image.size))
                        NSGraphicsContext.restoreGraphicsState()
                    }
                    var redBounds: CGRect?
                    var whiteBounds: CGRect?
                    for y in 0..<bitmap.pixelsHigh {
                        for x in 0..<bitmap.pixelsWide {
                            guard let color = bitmap.colorAt(x: x, y: y)?.usingColorSpace(.deviceRGB),
                                  color.alphaComponent > 0.8 else { continue }
                            let pixel = CGRect(x: x, y: y, width: 1, height: 1)
                            if color.redComponent > 0.7, color.greenComponent < 0.5, color.blueComponent < 0.5 {
                                redBounds = redBounds.map { $0.union(pixel) } ?? pixel
                            } else if color.redComponent > 0.9, color.greenComponent > 0.6, color.blueComponent > 0.6 {
                                whiteBounds = whiteBounds.map { $0.union(pixel) } ?? pixel
                            }
                        }
                    }
                    let circle = try XCTUnwrap(redBounds)
                    let microphone = try XCTUnwrap(whiteBounds)
                    XCTAssertEqual(circle.width, CGFloat(20 * scale), accuracy: 1)
                    XCTAssertEqual(circle.width, circle.height, accuracy: 1)
                    XCTAssertEqual(microphone.height, CGFloat(14 * scale), accuracy: 1)
                    XCTAssertTrue(circle.contains(microphone))
                    XCTAssertEqual(microphone.minX - circle.minX, circle.maxX - microphone.maxX,
                                   accuracy: 1, "Left and right insets must match at \(scale)× in \(name)")
                    XCTAssertEqual(microphone.minY - circle.minY, circle.maxY - microphone.maxY,
                                   accuracy: 1, "Top and bottom insets must match at \(scale)× in \(name)")
                    let head = try XCTUnwrap(bitmap.colorAt(x: Int(microphone.midX),
                                                          y: Int(microphone.minY + microphone.height * 0.25))?.usingColorSpace(.deviceRGB))
                    XCTAssertGreaterThan(head.greenComponent, 0.9, "The microphone head must be filled white")
                    XCTAssertGreaterThan(head.blueComponent, 0.9, "The microphone head must be filled white")
                    XCTAssertLessThan(try XCTUnwrap(bitmap.colorAt(x: 0, y: 0)).alphaComponent, 0.1,
                                      "The recording background must be circular")
                }
            }
        }
    }
}
