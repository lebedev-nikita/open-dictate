OpenDictate for macOS **0.4.2** adds strict punctuation guidance to transcription and fixes Google Drive reconnection. Requires macOS 14+ on Apple Silicon or Intel.

- Live and Accurate now request grammatical punctuation for each spoken language, including required commas, while preserving the original words, order and language switches.
- The same prompt is used on Android and when retranscribing the last recording. Selected language hints and dictionary terms are preserved; the prompt adds no extra API request.
- Fix Google Drive reconnection and settings-source handling.

### Install

Quit OpenDictate, then run `brew update` and `brew upgrade --cask lebedev-nikita/tap/open-dictate`.

Alternatively, download `OpenDictate-macOS-0.4.2-universal.dmg`, open it and drag OpenDictate to Applications. Existing settings and your Keychain API key are retained. Allow Microphone and Accessibility if prompted.

This release is **locally signed and not notarized**. If Gatekeeper blocks launching it, use **System Settings → Privacy & Security → Open Anyway** for OpenDictate, then launch it again. The disk image includes bilingual instructions.

Audio stays in memory and goes directly to OpenAI. There is no backend or analytics. No Input Monitoring permission is requested. API usage is billed separately by OpenAI. Check the `.sha256` attachment to verify the installer.
