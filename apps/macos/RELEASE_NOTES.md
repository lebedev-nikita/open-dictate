OpenDictate for macOS **0.5.0-rc.28** starts Live microphone capture before network setup. Requires macOS 14+ on Apple Silicon or Intel.

- Live dictation starts the microphone before creating the OpenAI client and WebSocket. Speech captured during connection setup waits in a bounded in-memory queue and is sent in order after session configuration.
- Finishing before the connection is ready preserves the captured beginning. Cancelling stops capture without submitting audio for transcription.

Download the universal DMG and replace OpenDictate in Applications. This release candidate is ad-hoc signed and **not notarized**. Verify the attached SHA-256 checksum.
