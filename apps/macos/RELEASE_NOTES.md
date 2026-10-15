OpenDictate for macOS **0.4.8** fixes the delay when manually synchronizing Google Drive settings. Requires macOS 14+ on Apple Silicon or Intel.

- **Sync now** and **Command-R** start synchronization immediately, bypassing the 15-second edit delay and retry backoff.
- Automatic synchronization still waits for a pause in editing. Changes made during a request remain queued for the next sync.

### Install

Quit OpenDictate, then run `brew update` and `brew upgrade --cask lebedev-nikita/tap/open-dictate`.

Alternatively, download `OpenDictate-macOS-0.4.8-universal.dmg`, open it and drag OpenDictate to Applications. Existing settings and your Keychain API key are retained. Allow Microphone and Accessibility if prompted.

This release is **locally signed and not notarized**. If Gatekeeper blocks launching it, use **System Settings → Privacy & Security → Open Anyway** for OpenDictate, then launch it again. The disk image includes bilingual instructions.

Audio stays in memory and goes directly to OpenAI. There is no backend or analytics. No Input Monitoring permission is requested. API usage is billed separately by OpenAI. Check the `.sha256` attachment to verify the installer.
