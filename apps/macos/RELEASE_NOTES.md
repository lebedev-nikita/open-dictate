# OpenDictate for macOS 0.5.0-rc.3

- Add an optional Post-processing section shared with Android. Choose a model (GPT-6-Luna by default), edit the prompt, and append punctuation or number-formatting instructions with buttons.
- Start with punctuation and capitalization instructions. Post-processing remains off by default; clearing the prompt skips the extra request. Saved settings synchronize through optional Google Drive sync, while the bundled default prompt stays on the device.
- Apply post-processing after Live, Accurate and retranscription, before local replacements. Preserve the original transcript with a warning if processing fails. Voice editing keeps its own instructions.
- Remove the transcription prompt editor and stop sending prompts to transcription models. Language and dictionary hints remain separate; punctuation and number formatting use Post-processing.
- Migrate the previous macOS Accurate punctuation setting to the shared configuration.

Legacy transcription-prompt preferences are cleared locally and discarded from Drive replicas, including nullable resets. Post-processing settings are preserved.

This release candidate is ad-hoc signed and not notarized by Apple. See INSTALL.txt in the disk image for Gatekeeper instructions.
