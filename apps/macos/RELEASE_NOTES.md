OpenDictate for macOS **0.4.10** simplifies Google Drive connection and account switching. Requires macOS 14+ on Apple Silicon or Intel.

- Google Drive uses a native switch with its provider icon and a compact connection row. Setup hints appear on hover; sign-in guidance and errors appear when needed.
- Turning the switch off cancels sign-in or synchronization and signs out only this Mac. Local and cloud settings are kept, and other devices stay connected. The next connection asks you to choose a Google account; re-enter the Desktop OAuth client secret after signing out.
- Settings header controls align consistently, with a simpler native appearance menu.
- Word replacement lists are sorted by replacement text.

### Install

Quit OpenDictate, then run `brew update` and `brew upgrade --cask lebedev-nikita/tap/open-dictate`.

Alternatively, download `OpenDictate-macOS-0.4.10-universal.dmg`, open it and drag OpenDictate to Applications. Existing settings and your Keychain API key are retained. Allow Microphone and Accessibility if prompted.

This release is **locally signed and not notarized**. If Gatekeeper blocks launching it, use **System Settings → Privacy & Security → Open Anyway** for OpenDictate, then launch it again. The disk image includes bilingual instructions.

Audio stays in memory and goes directly to OpenAI. There is no backend or analytics. No Input Monitoring permission is requested. API usage is billed separately by OpenAI. Check the `.sha256` attachment to verify the installer.
