OpenDictate for macOS **0.4.7** makes manual Google Drive settings synchronization available from every settings page. Requires macOS 14+ on Apple Silicon or Intel.

- Use **Sync now** at the top of the settings window or press **Command-R** to synchronize connected Google Drive settings.
- A native progress indicator shows when synchronization is running. The status reports waiting, the last successful sync, or a connection/sign-in problem.
- Repeated clicks and shortcuts during synchronization do not queue duplicate requests. Automatic synchronization continues as before.

### Install

Quit OpenDictate, then run `brew update` and `brew upgrade --cask lebedev-nikita/tap/open-dictate`.

Alternatively, download `OpenDictate-macOS-0.4.7-universal.dmg`, open it and drag OpenDictate to Applications. Existing settings and your Keychain API key are retained. Allow Microphone and Accessibility if prompted.

This release is **locally signed and not notarized**. If Gatekeeper blocks launching it, use **System Settings → Privacy & Security → Open Anyway** for OpenDictate, then launch it again. The disk image includes bilingual instructions.

Audio stays in memory and goes directly to OpenAI. There is no backend or analytics. No Input Monitoring permission is requested. API usage is billed separately by OpenAI. Check the `.sha256` attachment to verify the installer.
