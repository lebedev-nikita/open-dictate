import AppKit
import OpenDictateCore
import SwiftUI

/// Keeps recording controls above the editor without activating or focusing the app.
@MainActor
final class RecordingIndicator {
    private let panel: NSPanel
    private let content: NSHostingView<RecordingStatusView>
    private var displayedPhase: AppModel.Phase?

    init() {
        panel = IndicatorPanel(contentRect: .zero, styleMask: [.borderless, .nonactivatingPanel],
                               backing: .buffered, defer: false)
        content = NSHostingView(rootView: RecordingStatusView(phase: .idle, isRussian: false, finish: {}))
        panel.title = "OpenDictate recording indicator"
        panel.level = .statusBar
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.appearance = NSAppearance(named: .darkAqua)
        panel.hasShadow = true
        panel.hidesOnDeactivate = false
        panel.becomesKeyOnlyIfNeeded = true
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        panel.contentView = content
    }

    func show(phase: AppModel.Phase, style: RecordingIndicatorStyle, audioLevels: RecordingAudioLevels,
              isRussian: Bool, stage: DictationProcessingStage? = nil, showsStageIcon: Bool = false,
              finish: @escaping () -> Void) {
        guard phase != .idle else { hide(); return }
        content.rootView = RecordingStatusView(phase: phase, style: style, audioLevels: audioLevels,
                                              isRussian: isRussian, stage: stage, showsStageIcon: showsStageIcon, finish: finish)
        let size = RecordingStatusView.size(phase: phase, style: style)
        content.sizingOptions = []
        panel.setContentSize(size)
        panel.ignoresMouseEvents = !phase.showsFinishControl
        let screen = NSScreen.screens.first { NSMouseInRect(NSEvent.mouseLocation, $0.frame, false) } ?? NSScreen.main
        if let screen, !panel.isVisible || phase != .processing || displayedPhase != .processing {
            panel.setFrameOrigin(NSPoint(x: screen.visibleFrame.midX - size.width / 2,
                                        y: screen.visibleFrame.minY + 28))
        }
        displayedPhase = phase
        panel.orderFrontRegardless()
    }

    func hide() { panel.orderOut(nil); displayedPhase = nil }
}

private final class IndicatorPanel: NSPanel {
    override var canBecomeKey: Bool { false }
    override var canBecomeMain: Bool { false }
}

@MainActor
struct RecordingStatusView: View {
    let phase: AppModel.Phase
    let style: RecordingIndicatorStyle
    let isRussian: Bool
    let finish: () -> Void
    let stage: DictationProcessingStage?
    let showsStageIcon: Bool
    @ObservedObject private var audioLevels: RecordingAudioLevels

    init(phase: AppModel.Phase, style: RecordingIndicatorStyle = .compact,
         audioLevels: RecordingAudioLevels? = nil, isRussian: Bool,
         stage: DictationProcessingStage? = nil, showsStageIcon: Bool = false, finish: @escaping () -> Void) {
        self.phase = phase; self.style = style; self.isRussian = isRussian; self.finish = finish
        self.audioLevels = audioLevels ?? RecordingAudioLevels()
        self.stage = stage; self.showsStageIcon = showsStageIcon
    }

    static let scale: CGFloat = 1.25
    private static let waveformSize = WaveformGeometry.capsuleSize
    private static let spinnerSize = NSSize(width: 22, height: 22)

    static func size(phase: AppModel.Phase, style: RecordingIndicatorStyle) -> NSSize {
        let size: NSSize
        if !phase.showsFinishControl {
            size = spinnerSize
        } else {
            size = style == .waveform ? waveformSize : NSSize(width: 44, height: 22)
        }
        return NSSize(width: size.width * scale, height: size.height * scale)
    }

    var body: some View {
        Group {
            if style == .waveform {
                waveform
            } else if phase.showsFinishControl {
                HStack(spacing: 0) {
                    Image(systemName: "mic.fill")
                        .font(.system(size: 9, weight: .medium)).frame(width: 22, height: 22)
                        .accessibilityHidden(true)
                    ZStack {
                        Circle().fill(Color.red).frame(width: 14, height: 14)
                        RoundedRectangle(cornerRadius: 1).fill(Color.white).frame(width: 4.5, height: 4.5)
                    }
                    .frame(width: 16, height: 16)
                    .overlay { finishControl }
                    .frame(width: 22, height: 22)
                }
                .fixedSize()
            } else {
                processing
            }
        }
        .background(style == .waveform ? Color.black : Color(nsColor: .windowBackgroundColor), in: Capsule())
        .overlay {
            if style == .waveform { Capsule().strokeBorder(Color.white.opacity(0.14), lineWidth: 0.5).allowsHitTesting(false) }
        }
        .scaleEffect(Self.scale)
        .frame(width: Self.size(phase: phase, style: style).width, height: Self.size(phase: phase, style: style).height)
        .preferredColorScheme(.dark)
    }

    private var finishControl: some View {
        IndicatorFinishControl(isRussian: isRussian, finish: finish)
    }

    private var waveform: some View {
        Group {
            if phase.showsFinishControl {
                WaveformBars(samples: audioLevels.history.samples)
                    .frame(width: Self.waveformSize.width, height: Self.waveformSize.height)
                    .overlay { finishControl }
            } else {
                processing
            }
        }
    }

    private var processing: some View {
        Group {
            if showsStageIcon, let stage {
                TimelineView(.animation(minimumInterval: 1.0 / 30)) { timeline in
                    ZStack {
                        Circle().stroke(Color.primary.opacity(0.2), lineWidth: 1.5)
                        Circle().trim(from: 0, to: 0.72)
                            .stroke(Color.primary, style: StrokeStyle(lineWidth: 1.5, lineCap: .round))
                            .rotationEffect(.degrees(timeline.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 1.2) * 300))
                        Image(systemName: stage.symbol).font(.system(size: 10, weight: .medium))
                    }.padding(2)
                }
            } else {
                ProgressView().progressViewStyle(.circular).controlSize(.mini)
            }
        }
        .frame(width: Self.spinnerSize.width, height: Self.spinnerSize.height)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(stage?.label(russian: isRussian) ?? (isRussian ? "Обработка" : "Processing"))
    }
}

enum WaveformGeometry {
    static let goldenRatio: CGFloat = (1 + sqrt(5)) / 2
    static let barWidth: CGFloat = 1.5
    static let minimumBarHeight: CGFloat = 2
    static let maximumBarHeight: CGFloat = 14
    static let capsuleSize = NSSize(width: 50, height: maximumBarHeight * goldenRatio)
    static let barSpacing = (capsuleSize.width / goldenRatio - CGFloat(AudioLevelHistory.count) * barWidth)
        / CGFloat(AudioLevelHistory.count - 1)
}

struct WaveformBars: View {
    let samples: [Float]
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        HStack(spacing: WaveformGeometry.barSpacing) {
            ForEach(samples.indices, id: \.self) { index in
                Capsule().fill(Color.white)
                    .frame(width: WaveformGeometry.barWidth,
                           height: WaveformGeometry.minimumBarHeight
                               + (WaveformGeometry.maximumBarHeight - WaveformGeometry.minimumBarHeight)
                               * CGFloat(sqrt(samples[index])))
            }
        }
        .animation(reduceMotion ? nil : .linear(duration: 0.04), value: samples)
        .accessibilityHidden(true)
    }
}

/// AppKit mouse handling keeps SwiftUI's focus system out of the external editor.
/// Finish after mouse-up, before replacing the clickable view with the spinner.
struct IndicatorFinishControl: NSViewRepresentable {
    let isRussian: Bool
    let finish: () -> Void
    func makeNSView(context: Context) -> IndicatorFinishView { IndicatorFinishView() }
    func updateNSView(_ view: IndicatorFinishView, context: Context) {
        view.finish = finish
        view.setAccessibilityElement(true)
        view.setAccessibilityRole(.button)
        view.setAccessibilityLabel(isRussian ? "Завершить диктовку" : "Finish dictation")
        view.setAccessibilityIdentifier("finishDictation")
        view.toolTip = isRussian ? "Завершить и отправить" : "Finish and submit"
    }
}

final class IndicatorFinishView: NSView {
    var finish: (() -> Void)?
    override var acceptsFirstResponder: Bool { false }
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }
    override func mouseDown(with event: NSEvent) {} // Do not ask AppKit/SwiftUI to focus this control.
    override func mouseUp(with event: NSEvent) {
        guard bounds.contains(convert(event.locationInWindow, from: nil)) else { return }
        let action = finish
        DispatchQueue.main.async { action?() }
    }
    override func accessibilityPerformPress() -> Bool {
        let action = finish
        DispatchQueue.main.async { action?() }
        return true
    }
}
