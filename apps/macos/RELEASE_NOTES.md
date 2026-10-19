# OpenDictate for macOS 0.5.0-rc.12

- Fix ChatGPT sign-in for valid ID tokens whose single audience is encoded as an array without azp. Keep signature, issuer, nonce, expiry and authorized-party checks.
- Match the Google Drive connection style with a ChatGPT icon, native switch and compact supporting controls. Keep the saved transcription API-key prerequisite.
- Connect an eligible ChatGPT plan for GPT-6 Luna text transformation and post-processing through official browser OAuth. Require a saved transcription API key first, explain the disabled button, protect tokens in Keychain and never silently fall back to API billing for text.
- Show compact transcription and post-processing request durations in history for Accurate and retranscription, including network upload and the full response. Live shows only a Live label. Keep old records without fabricated timings.
- Add a shared Text model selector beside recognition models, initially offering only GPT-6 Luna. Use its saved session-start selection for text transformation and post-processing; synchronize it through Google Drive.
- Add an optional Post-processing section shared with Android. Edit the prompt, and append punctuation or value-formatting instructions using plus buttons on the right. Place prompt actions below the editor.
- Expand Format values with integer, decimal, date, time, duration, percentage, money, measurement, range and fraction examples in Russian and English. Preserve ambiguous values and identifiers; keep existing saved prompts unchanged.
- Start with punctuation and capitalization instructions. Post-processing remains off by default; clearing the prompt skips the extra request. Saved settings synchronize through optional Google Drive sync, while the bundled default prompt stays on the device.
- Show and apply post-processing only for Accurate dictation and Accurate retranscription, before local replacements. Live retains the saved settings without running post-processing. Preserve the original transcript with a warning if processing fails. Voice editing keeps its own instructions.
- Remove the transcription prompt editor and stop sending prompts to transcription models. Language and dictionary hints remain separate; punctuation and value formatting use Post-processing.
- Migrate the previous macOS Accurate punctuation setting to the shared configuration.

Legacy transcription-prompt preferences are cleared locally and discarded from Drive replicas, including nullable resets. Post-processing prompts and enabled states are preserved. Old text-model selections are ignored; post-processing and voice editing use the shared Text model setting; AI history search uses GPT-6 Luna.

This release candidate is ad-hoc signed and not notarized by Apple. See INSTALL.txt in the disk image for Gatekeeper instructions.
