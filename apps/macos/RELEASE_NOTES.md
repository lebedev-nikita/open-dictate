OpenDictate for macOS **0.5.0** adds optional transcript post-processing, value formatting and ChatGPT plan support for text processing. Requires macOS 14+ on Apple Silicon or Intel.

- Use the dedicated Dictation with post-processing action to correct punctuation and optionally format spoken values. Ordinary Live and Accurate dictation keep their existing behavior.
- Convert unambiguous spoken numbers to digits, including Russian numbers, with regional defaults or custom language-specific rules. Enable only Integers under Value types to leave dates, percentages and other types unchanged. Formatting is model-driven; exact grouping is not guaranteed.
- Synchronize formatting rules and regional examples through Google Drive. Enable switches and keyboard shortcuts stay local to each device.
- Connect an eligible ChatGPT plan for GPT-6 Luna text processing. Audio transcription and AI history search still require an API key; failed ChatGPT requests never silently fall back to paid API text processing.
- History shows the models and separate transcription/post-processing durations. The recording indicator shows processing stages.
- Faster microphone startup waits for actual captured audio before reporting readiness. The waveform advances at ten updates per second, independently of audio callbacks.
- Includes consolidated Keychain credentials, installed-resource lookup fixes, compact shortcut settings and corrected System theme transitions.

Install or upgrade with `brew install --cask lebedev-nikita/tap/open-dictate` or `brew upgrade --cask open-dictate`, or download the universal DMG and replace OpenDictate in Applications. This release is ad-hoc signed and **not notarized**. Verify the attached SHA-256 checksum.
