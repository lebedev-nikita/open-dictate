OpenDictate for macOS **0.4.9** simplifies the Google Drive synchronization indicator. Requires macOS 14+ on Apple Silicon or Intel.

- While synchronizing, the **Sync now** button shows a spinner without a duplicate status caption beneath it. The progress indicator remains accessible to VoiceOver.

### Install

Quit OpenDictate, then run `brew update` and `brew upgrade --cask lebedev-nikita/tap/open-dictate`.

Alternatively, download `OpenDictate-macOS-0.4.9-universal.dmg`, open it and drag OpenDictate to Applications. Existing settings and your Keychain API key are retained. Allow Microphone and Accessibility if prompted.

This release is **locally signed and not notarized**. If Gatekeeper blocks launching it, use **System Settings → Privacy & Security → Open Anyway** for OpenDictate, then launch it again. The disk image includes bilingual instructions.

Audio stays in memory and goes directly to OpenAI. There is no backend or analytics. No Input Monitoring permission is requested. API usage is billed separately by OpenAI. Check the `.sha256` attachment to verify the installer.
