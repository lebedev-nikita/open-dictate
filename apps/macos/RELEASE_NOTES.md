OpenDictate for macOS **0.4.11** fixes Google Drive sign-in and reconnection. Requires macOS 14+ on Apple Silicon or Intel.

- Official builds include the Google Drive application configuration. Turning on the native switch opens Google's account chooser without asking for a client ID or secret.
- Turning the switch off signs out only this Mac and preserves application configuration for the next connection. Local and cloud settings are kept, and other devices stay connected.
- Release builds validate the OAuth configuration before packaging and publication.

### Install

Quit OpenDictate, then run `brew update` and `brew upgrade --cask lebedev-nikita/tap/open-dictate`.

Alternatively, download `OpenDictate-macOS-0.4.11-universal.dmg`, open it and drag OpenDictate to Applications. Existing settings and your Keychain API key are retained. Allow Microphone and Accessibility if prompted.

This release is **locally signed and not notarized**. If Gatekeeper blocks launching it, use **System Settings → Privacy & Security → Open Anyway** for OpenDictate, then launch it again. The disk image includes bilingual instructions.

Audio stays in memory and goes directly to OpenAI. There is no backend or analytics. No Input Monitoring permission is requested. API usage is billed separately by OpenAI. Check the `.sha256` attachment to verify the installer.
