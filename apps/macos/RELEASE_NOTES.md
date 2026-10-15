OpenDictate for macOS **0.4.6** keeps errors from interrupting your current app. Requires macOS 14+ on Apple Silicon or Intel.

- Errors appear as native macOS notifications instead of opening OpenDictate's settings window. Allow notifications when macOS asks; their appearance follows your system notification settings.
- Starting dictation or voice editing without an editable text field no longer shows an error or opens a window.
- Notification messages follow the selected interface language and never include raw provider or system error details.

### Install

Quit OpenDictate, then run `brew update` and `brew upgrade --cask lebedev-nikita/tap/open-dictate`.

Alternatively, download `OpenDictate-macOS-0.4.6-universal.dmg`, open it and drag OpenDictate to Applications. Existing settings and your Keychain API key are retained. Allow Microphone and Accessibility if prompted.

This release is **locally signed and not notarized**. If Gatekeeper blocks launching it, use **System Settings → Privacy & Security → Open Anyway** for OpenDictate, then launch it again. The disk image includes bilingual instructions.

Audio stays in memory and goes directly to OpenAI. There is no backend or analytics. No Input Monitoring permission is requested. API usage is billed separately by OpenAI. Check the `.sha256` attachment to verify the installer.
