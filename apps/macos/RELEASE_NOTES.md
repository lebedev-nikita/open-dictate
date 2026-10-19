# OpenDictate for macOS 0.5.0-rc.2

- Add an optional Post-processing section shared with Android. Choose a model (GPT-6-Luna by default), edit the prompt, and append punctuation or number-formatting instructions with buttons.
- Start with punctuation and capitalization instructions. Post-processing remains off by default; clearing the prompt skips the extra request. Saved settings synchronize through optional Google Drive sync, while the bundled default prompt stays on the device.
- Apply post-processing after Live, Accurate and retranscription, before local replacements. Preserve the original transcript with a warning if processing fails. Voice editing keeps its own instructions.
- Keep the transcription prompt as editable vocabulary or topic context. Its built-in default is now empty; existing custom prompts are preserved. Formatting belongs in Post-processing.
- Migrate the previous macOS Accurate punctuation setting to the shared configuration.

Update both platforms before testing cross-device prompt resets. Older apps pause Drive sync when they receive nullable transcription-prompt values.

This release candidate is ad-hoc signed and not notarized by Apple. See INSTALL.txt in the disk image for Gatekeeper instructions.
