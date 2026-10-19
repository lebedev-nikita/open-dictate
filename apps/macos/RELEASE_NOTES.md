# OpenDictate for macOS 0.5.0-rc.7

- Add an optional Post-processing section shared with Android. Always use GPT-6-Luna, edit the prompt, and append punctuation or value-formatting instructions with examples with plus buttons on the right. Place prompt actions below the editor.
- Expand Format values with integer, decimal, date, time, duration, percentage, money, measurement, range and fraction examples in Russian and English. Preserve ambiguous values and identifiers; keep existing saved prompts unchanged.
- Start with punctuation and capitalization instructions. Post-processing remains off by default; clearing the prompt skips the extra request. Saved settings synchronize through optional Google Drive sync, while the bundled default prompt stays on the device.
- Show and apply post-processing only for Accurate dictation and Accurate retranscription, before local replacements. Live retains the saved settings without running post-processing. Preserve the original transcript with a warning if processing fails. Voice editing keeps its own instructions.
- Remove the transcription prompt editor and stop sending prompts to transcription models. Language and dictionary hints remain separate; punctuation and value formatting use Post-processing.
- Migrate the previous macOS Accurate punctuation setting to the shared configuration.

Legacy transcription-prompt preferences are cleared locally and discarded from Drive replicas, including nullable resets. Post-processing prompts and enabled states are preserved. Old text-model selections are ignored; post-processing, voice editing and AI history search always use GPT-6-Luna.

This release candidate is ad-hoc signed and not notarized by Apple. See INSTALL.txt in the disk image for Gatekeeper instructions.
