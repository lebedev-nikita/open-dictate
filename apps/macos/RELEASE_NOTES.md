OpenDictate for macOS **0.5.0-rc.21** adds synchronized AI formatting instructions. Requires macOS 14+ on Apple Silicon or Intel.

- In Accurate mode, save your own rules for punctuation, capitalization and paragraph breaks. Empty rules use default punctuation correction; words and numbers remain protected.
- Formatting rules sync through Google Drive, including when AI post-processing is off. The post-processing switch stays local to each Mac. Android relays the shared rules without applying an extra AI pass.
- The menu bar shows a native spinner while awaiting transcription without AI post-processing. The menu remains clickable and keeps its size.

### Install

This is a release candidate. Quit OpenDictate, download `OpenDictate-macOS-0.5.0-rc.21-universal.dmg`, open it and drag OpenDictate to Applications. Existing settings and your Keychain API key are retained. Allow Microphone and Accessibility if prompted. The stable Homebrew version is unchanged.

This release is **locally signed and not notarized**. If Gatekeeper blocks launching it, use **System Settings → Privacy & Security → Open Anyway** for OpenDictate, then launch it again. The disk image includes bilingual instructions.

Audio stays in memory and goes directly to OpenAI. There is no backend or analytics. No Input Monitoring permission is requested. API usage is billed separately by OpenAI. Check the `.sha256` attachment to verify the installer.
