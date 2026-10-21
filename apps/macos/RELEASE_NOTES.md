OpenDictate for macOS **0.5.0-rc.25** adds dedicated dictation shortcuts and consolidates Keychain authorization. Requires macOS 14+ on Apple Silicon or Intel.

- Open **Keyboard shortcuts** in Settings to configure dictation, dictation with post-processing, and voice editing independently. The new processing shortcut starts unassigned; existing bindings are preserved.
- Ordinary dictation always skips post-processing. The dedicated processing action uses Accurate once; either dictation shortcut finishes the current recording. Escape cancels.
- Configure post-processing rules in either mode without a global enable switch. Retranscription retains the original recording’s processing choice and applies current rules.
- Store API-key, Google Drive and ChatGPT credentials as independent sections of one device-only Keychain item. Future updates can authorize this shared item once. The first migration may still ask for access to older entries; failed migration preserves them.
- Retains RC24’s installed-resource crash fix and packaged resource verification.

Download the universal DMG and replace OpenDictate in Applications. This release candidate is ad-hoc signed and **not notarized**. Verify the attached SHA-256 checksum.
