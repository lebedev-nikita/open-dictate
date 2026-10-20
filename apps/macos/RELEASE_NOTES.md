OpenDictate for macOS **0.5.0-rc.22** restores the complete previous RC feature set, including ChatGPT connection, text model selection and value-format rules. Requires macOS 14+ on Apple Silicon or Intel.

- Formatting rules synchronize through Google Drive while all post-processing switches remain local.
- Changing an enable switch does not enqueue synchronization. Existing rule journals migrate without changing local switches.
- ChatGPT credentials remain device-local in Keychain; audio transcription still uses the separate OpenAI API key.

Download the universal DMG and replace OpenDictate in Applications. This release candidate is locally signed and **not notarized**. Verify the attached SHA-256 checksum.
