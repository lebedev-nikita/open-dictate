import AppKit

@MainActor
enum MenuBarIcon {
    private static let size = NSSize(width: 20, height: 20)
    private static let microphoneHeight: CGFloat = 14
    private static let templateImage = makeImage(recording: false)
    private static let recordingImage = makeImage(recording: true)

    static func image(for phase: AppModel.Phase) -> NSImage? {
        phase == .recording ? recordingImage : templateImage
    }

    private static func makeImage(recording: Bool) -> NSImage? {
        guard let microphone = trimmedMicrophone(recording ? "mic.fill" : "mic") else { return nil }
        let image = NSImage(size: size, flipped: false) { bounds in
            if recording {
                NSColor.systemRed.setFill()
                NSBezierPath(ovalIn: bounds).fill()
            }
            // SF Symbols include asymmetric transparent padding. Center the
            // visible silhouette rather than the symbol's full image canvas.
            microphone.draw(in: NSRect(x: bounds.midX - microphone.size.width / 2,
                                      y: bounds.midY - microphone.size.height / 2,
                                      width: microphone.size.width,
                                      height: microphone.size.height),
                            from: .zero, operation: .sourceOver, fraction: 1,
                            respectFlipped: false, hints: [.interpolation: NSImageInterpolation.high])
            return true
        }
        image.isTemplate = !recording
        image.accessibilityDescription = "OpenDictate"
        return image
    }

    private static func trimmedMicrophone(_ name: String) -> NSImage? {
        guard let symbol = NSImage(systemSymbolName: name, accessibilityDescription: nil) else { return nil }
        // Cache a high-resolution, tightly cropped symbol in memory. This also
        // avoids AppKit's different symbol padding at small display scales.
        let scale: CGFloat = 16
        guard let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil,
                                           pixelsWide: Int(symbol.size.width * scale),
                                           pixelsHigh: Int(symbol.size.height * scale),
                                           bitsPerSample: 8, samplesPerPixel: 4,
                                           hasAlpha: true, isPlanar: false,
                                           colorSpaceName: .deviceRGB, bytesPerRow: 0,
                                           bitsPerPixel: 0) else { return nil }
        bitmap.size = symbol.size
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: bitmap)
        symbol.draw(in: NSRect(origin: .zero, size: symbol.size))
        NSColor.white.setFill()
        NSRect(origin: .zero, size: symbol.size).fill(using: .sourceIn)
        NSGraphicsContext.restoreGraphicsState()
        var pixels: NSRect?
        for y in 0..<bitmap.pixelsHigh {
            for x in 0..<bitmap.pixelsWide {
                guard let color = bitmap.colorAt(x: x, y: y), color.alphaComponent > 0.5 else { continue }
                let pixel = NSRect(x: x, y: y, width: 1, height: 1)
                pixels = pixels.map { $0.union(pixel) } ?? pixel
            }
        }
        guard let pixels, let glyph = bitmap.cgImage?.cropping(to: pixels) else { return nil }
        return NSImage(cgImage: glyph, size: NSSize(width: microphoneHeight * pixels.width / pixels.height,
                                                   height: microphoneHeight))
    }
}
