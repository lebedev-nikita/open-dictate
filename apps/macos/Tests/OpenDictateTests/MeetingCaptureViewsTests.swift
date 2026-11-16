import AppKit
import SwiftUI
import XCTest
import OpenDictateCore
@testable import OpenDictate

/// Renders every meeting surface for visual review. Set OPENDICTATE_VIEW_CAPTURES to an output directory.
final class MeetingCaptureViewsTests: XCTestCase {
    @MainActor private func sample(russian: Bool, state: Meeting.State = .finished, pending: Bool = false) -> Meeting {
        var meeting = Meeting(startedAt: Date(timeIntervalSince1970: 1_794_838_500), sourceName: "Zoom", sourceBundleID: "us.zoom.xos",
                              state: state)
        meeting.durationMillis = 47 * 60_000
        let lines: [(String, Int, String)] = russian ? [
            (Meeting.meID, 4_000, "Давай начнём с плана релиза."),
            (Meeting.otherID, 11_000, "Согласна, сначала каналы. Роли берём из микрофона и звука звонка, без отдельной модели."),
            (Meeting.meID, 19_000, "Тогда в четверг собираем кандидата и проверяем эхо без наушников."),
            (Meeting.otherID, 27_000, "Хорошо. Я пришлю замеры после звонка."),
        ] : [
            (Meeting.meID, 4_000, "Let's start with the release plan."),
            (Meeting.otherID, 11_000, "Agreed, channels first. Roles come from the microphone and the call audio, without a separate model."),
            (Meeting.meID, 19_000, "Then on Thursday we build the candidate and check echo without headphones."),
            (Meeting.otherID, 27_000, "Sounds good. I'll send the numbers after the call."),
        ]
        for (speaker, start, text) in lines {
            meeting.insert(MeetingUtterance(speaker: speaker, startMillis: start, durationMillis: 3_000, text: text, status: .recognized))
        }
        if pending {
            meeting.insert(MeetingUtterance(speaker: Meeting.otherID, startMillis: 33_000, durationMillis: 2_000, status: .failed))
            meeting.insert(MeetingUtterance(speaker: Meeting.meID, startMillis: 36_000, durationMillis: 2_000))
        }
        return meeting
    }

    @MainActor private func capture<V: View>(_ view: V, size: NSSize, appearance: NSAppearance?, to url: URL,
                                             settle: () -> Void = {}) throws {
        let host = NSHostingView(rootView: view)
        host.appearance = appearance
        host.frame = NSRect(origin: .zero, size: size)
        let window = NSWindow(contentRect: host.frame, styleMask: [.titled], backing: .buffered, defer: false)
        window.isReleasedWhenClosed = false; window.contentView = host; window.appearance = appearance
        defer { window.close() }
        window.makeKeyAndOrderFront(nil); host.layoutSubtreeIfNeeded()
        RunLoop.main.run(until: Date().addingTimeInterval(0.2))
        settle()
        RunLoop.main.run(until: Date().addingTimeInterval(0.2))
        let bitmap = try XCTUnwrap(host.bitmapImageRepForCachingDisplay(in: host.bounds))
        host.cacheDisplay(in: host.bounds, to: bitmap)
        try XCTUnwrap(bitmap.representation(using: .png, properties: [:])).write(to: url)
    }

    @MainActor func testCaptureMeetingSurfaces() throws {
        guard let output = ProcessInfo.processInfo.environment["OPENDICTATE_VIEW_CAPTURES"] else {
            throw XCTSkip("Set OPENDICTATE_VIEW_CAPTURES to capture meeting surfaces.")
        }
        let directory = URL(fileURLWithPath: output)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let suite = "meeting-capture-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let model = AppModel(localOnly: true, defaults: defaults)
        defer { model.shutdown() }
        let preferences = model.preferences
        let store = MeetingStore(inMemory: true)
        let controller = MeetingController(store: store, preferences: preferences, replacements: model.replacements)
        let navigation = SettingsNavigation(page: "meetings")
        for (language, theme) in [("en", AppTheme.light), ("ru", AppTheme.dark), ("en", AppTheme.dark), ("ru", AppTheme.light)] {
            preferences.interfaceLanguage = language; preferences.theme = theme
            let russian = language == "ru"
            let suffix = "\(language)-\(theme == .dark ? "dark" : "light")"
            let window = NSSize(width: 520, height: 620)
            func meetingView(_ shown: UUID? = nil) -> some View {
                MeetingView(controller: controller, preferences: preferences, model: model,
                            shownMeeting: .constant(shown), openSettings: { _, _ in })
            }
            // First use: notice, missing key.
            preferences.meetingNoticeAccepted = false
            controller.preview(nil, phase: .idle)
            try capture(meetingView(), size: window, appearance: theme.appearance,
                        to: directory.appendingPathComponent("meeting-setup-first-\(suffix).png")) {
                model.previewSetup(hasKey: false, microphone: true)
            }
            preferences.meetingNoticeAccepted = true
            preferences.meetingSource = .application(bundleID: "us.zoom.xos", name: "Zoom")
            try capture(meetingView(), size: window, appearance: theme.appearance,
                        to: directory.appendingPathComponent("meeting-setup-ready-\(suffix).png")) {
                model.previewSetup(hasKey: true, microphone: true)
            }
            // Recording with waiting and failed utterances, call audio missing.
            let live = sample(russian: russian, state: .recording, pending: true)
            controller.preview(live, phase: .recording, elapsedMillis: 754_000, callAudioSilent: true, levels: (0.5, 0.05))
            try capture(meetingView(), size: window, appearance: theme.appearance,
                        to: directory.appendingPathComponent("meeting-recording-\(suffix).png"))
            controller.preview(live, phase: .paused, elapsedMillis: 3_754_000, pauseReason: .backlog,
                               issue: DictationError.connection.message(russian: russian))
            try capture(meetingView(), size: window, appearance: theme.appearance,
                        to: directory.appendingPathComponent("meeting-paused-\(suffix).png"))
            controller.preview(live, phase: .finishing, elapsedMillis: 2_820_000, microphone: .off, callAudio: .off)
            try capture(meetingView(), size: window, appearance: theme.appearance,
                        to: directory.appendingPathComponent("meeting-finishing-\(suffix).png"))
            controller.preview(Meeting(startedAt: Date(), sourceName: nil, sourceBundleID: nil), phase: .recording,
                               elapsedMillis: 3_000, callAudio: .starting)
            try capture(meetingView(), size: NSSize(width: 440, height: 420), appearance: theme.appearance,
                        to: directory.appendingPathComponent("meeting-starting-min-\(suffix).png"))
            // Saved.
            controller.preview(nil, phase: .idle)
            var finished = sample(russian: russian)
            finished.rename(speaker: Meeting.otherID, to: russian ? "Анна" : "Anna")
            store.save(finished)
            try capture(meetingView(finished.id), size: window, appearance: theme.appearance,
                        to: directory.appendingPathComponent("meeting-finished-\(suffix).png"))
            // Settings → Meetings list and detail.
            var older = sample(russian: russian); older = Meeting(startedAt: older.startedAt.addingTimeInterval(-86_400 * 3),
                                                                  sourceName: "Google Chrome", sourceBundleID: "com.google.Chrome", state: .interrupted)
            older.durationMillis = 12 * 60_000
            store.save(older)
            preferences.meetingExportFolder = NSHomeDirectory() + "/Documents/Meetings"
            navigation.page = "meetings"; navigation.meeting = nil
            let settings = { SettingsView(model: model, preferences: preferences, history: model.history,
                                          replacements: model.replacements, meetings: controller, navigation: navigation) }
            try capture(settings(), size: NSSize(width: 790, height: 650), appearance: theme.appearance,
                        to: directory.appendingPathComponent("meetings-list-\(suffix).png"))
            navigation.meeting = finished.id
            try capture(settings(), size: NSSize(width: 790, height: 900), appearance: theme.appearance,
                        to: directory.appendingPathComponent("meetings-detail-\(suffix).png"))
            store.deleteAll(); preferences.meetingExportFolder = nil
            navigation.meeting = nil
            try capture(settings(), size: NSSize(width: 740, height: 590), appearance: theme.appearance,
                        to: directory.appendingPathComponent("meetings-empty-min-\(suffix).png"))
            navigation.page = "shortcuts"
            try capture(settings(), size: NSSize(width: 740, height: 590), appearance: theme.appearance,
                        to: directory.appendingPathComponent("shortcuts-min-\(suffix).png"))
        }
    }

    /// Menu bar icons for every dictation and meeting state, enlarged on light and dark bars.
    @MainActor func testCaptureMenuBarIcons() throws {
        guard let output = ProcessInfo.processInfo.environment["OPENDICTATE_VIEW_CAPTURES"] else {
            throw XCTSkip("Set OPENDICTATE_VIEW_CAPTURES to capture menu bar icons.")
        }
        let states: [(AppModel.Phase, MenuBarIcon.Meeting)] = [(.idle, .none), (.recording, .none), (.idle, .recording),
                                                                (.idle, .paused), (.recording, .recording), (.processing, .recording)]
        for name in [NSAppearance.Name.aqua, .darkAqua] {
            let appearance = try XCTUnwrap(NSAppearance(named: name))
            let scale = 8, width = 20 * states.count + 10 * (states.count + 1), height = 30
            let bitmap = try XCTUnwrap(NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: width * scale, pixelsHigh: height * scale,
                bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0))
            bitmap.size = NSSize(width: width, height: height)
            appearance.performAsCurrentDrawingAppearance {
                NSGraphicsContext.saveGraphicsState()
                NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: bitmap)
                (name == .darkAqua ? NSColor(white: 0.15, alpha: 1) : NSColor(white: 0.93, alpha: 1)).setFill()
                NSRect(x: 0, y: 0, width: width, height: height).fill()
                for (index, state) in states.enumerated() {
                    guard let image = MenuBarIcon.image(for: state.0, meeting: state.1) else { continue }
                    let rect = NSRect(x: 10 + index * 30, y: 5, width: 20, height: 20)
                    if image.isTemplate {
                        // The menu bar tints template images with its label color.
                        let tinted = NSImage(size: image.size, flipped: false) { bounds in
                            image.draw(in: bounds); NSColor.labelColor.setFill(); bounds.fill(using: .sourceIn); return true
                        }
                        tinted.draw(in: rect)
                    } else { image.draw(in: rect) }
                }
                NSGraphicsContext.restoreGraphicsState()
            }
            try XCTUnwrap(bitmap.representation(using: .png, properties: [:])).write(to:
                URL(fileURLWithPath: output).appendingPathComponent("menubar-\(name == .darkAqua ? "dark" : "light").png"))
        }
    }
}
