#if DEBUG
import AppKit
import OpenDictateCore

/// Explicit development-only checks. No credentials, provider calls or audio files.
@MainActor
final class LocalSmokeChecks {
    private var target: TextTarget?
    private var replacements: WordReplacementEngine?
    private let model: AppModel
    private let recorder = AudioRecorder()
    init(model: AppModel) { self.model = model }

    func toggle() async {
        if let target {
            let accepted = await target.paste(replacements?.apply("Проверка 🙂") ?? "Проверка 🙂")
            model.present(CheckMessage(accepted ? "LOCAL CHECK: final clipboard insertion verified" : "LOCAL CHECK: focus guard rejected final"))
            self.target = nil; replacements = nil; model.hotKeys.setCancelEnabled(false)
        } else {
            do {
                let target = try TextTarget.capture(exclusions: [])
                let replacements = model.replacements.engine()
                self.target = target; self.replacements = replacements
                model.hotKeys.setCancelEnabled(true)
                model.present(CheckMessage("LOCAL CHECK: original captured; press shortcut again to paste or Escape"))
            } catch { model.present(error) }
        }
    }
    func cancel() async {
        target = nil; replacements = nil; model.hotKeys.setCancelEnabled(false)
        model.present(CheckMessage("LOCAL CHECK: cancelled before paste; field unchanged"))
    }
    func verifyClipboardInsertion() async {
        do {
            let target = try TextTarget.capture(exclusions: [])
            let original = target.snapshot
            let replacements = model.replacements.engine()
            guard target.isCurrent else { throw DictationError.noField }
            try await Task.sleep(nanoseconds: 100_000_000)
            guard target.isCurrent, target.snapshot == original else { throw CheckMessage("LOCAL CHECK: original target changed") }
            guard await target.paste(replacements.apply("Проверка 🙂")) else { throw CheckMessage("LOCAL CHECK: clipboard delivery rejected") }
            model.present(CheckMessage("LOCAL CHECK: final clipboard insertion verified in \(target.applicationName)"))
        } catch { model.present(error) }
    }
    func record() {
        Task { [model] in
            let recorder = self.recorder
            do {
                model.audioLevels.reset()
                try await recorder.start(keepAudio: true, onReady: {}, onChunk: { _ in }, onLevel: { [weak model] level in
                    Task { @MainActor in model?.audioLevels.append(level) }
                }, onError: { _ in })
                try await Task.sleep(nanoseconds: 2_000_000_000)
                let (data, bytes) = await recorder.stop()
                model.present(CheckMessage(bytes > 48_000 && data.count == bytes
                    ? "LOCAL CHECK: microphone captured 24 kHz PCM; discarded in memory"
                    : "LOCAL CHECK: microphone check failed"))
            } catch { _ = await recorder.stop(); model.present(error) }
            model.audioLevels.reset()
        }
    }
    private struct CheckMessage: LocalizedError {
        let errorDescription: String?
        init(_ message: String) { errorDescription = message }
    }
}
#endif
