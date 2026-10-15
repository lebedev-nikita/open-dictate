OpenDictate for macOS **0.4.13** keeps synchronization controls in the settings sidebar. Requires macOS 14+ on Apple Silicon or Intel.

- Google Drive sync status and the refresh button now sit above the app version in the sidebar. Command-R still synchronizes settings immediately.
- The settings content and scrollbar use the full height of the right panel, without a separate synchronization header.
- Successful syncs show a compact relative time. Hover over the status for the full date and details; progress and retry remain available.

### Install

Quit OpenDictate, then run `brew update` and `brew upgrade --cask lebedev-nikita/tap/open-dictate`.

Alternatively, download `OpenDictate-macOS-0.4.13-universal.dmg`, open it and drag OpenDictate to Applications. Existing settings and your Keychain API key are retained. Allow Microphone and Accessibility if prompted.

This release is **locally signed and not notarized**. If Gatekeeper blocks launching it, use **System Settings → Privacy & Security → Open Anyway** for OpenDictate, then launch it again. The disk image includes bilingual instructions.

Audio stays in memory and goes directly to OpenAI. There is no backend or analytics. No Input Monitoring permission is requested. API usage is billed separately by OpenAI. Check the `.sha256` attachment to verify the installer.
