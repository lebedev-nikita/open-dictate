OpenDictate for macOS **0.5.3** fixes microphone capture across AirPods audio configuration changes. Requires macOS 14+ on Apple Silicon or Intel.

- Open Bluetooth capture graphs when dictation starts instead of reusing an idle graph across profile changes.
- Rebuild capture after hardware format changes, preserving already captured audio and streaming chunk boundaries.
- Recover from format changes during graph connection instead of terminating the app. Ignore stale configuration callbacks after cancellation or a newer recording.
- Report unrecoverable capture changes and retain valid audio for an explicit Accurate retry instead of silently submitting an interrupted recording.

Upgrade with Homebrew or download the universal DMG and replace OpenDictate in Applications. This release is ad-hoc signed and **not notarized**. Verify the attached SHA-256 checksum.
