# OpenDictate for macOS 0.5.0-rc.18

- Add shared value-formatting language examples for all 64 supported speech languages. Include only the session’s selected languages; automatic detection uses general rules without language-specific examples.
- Preserve omitted zero groups when formatting spoken large numbers, including money. Reject changed values for supported Russian/English scaled integer phrases and retain the source on failure.
- Fix ChatGPT post-processing and transformation when the final completed event omits its output snapshot. Retain finished stream items in memory and require terminal success before returning them.
- Give enabled value formatting explicit priority when combined with punctuation, so number words can become digits.
- Replace the editable post-processing prompt with punctuation and value-format settings. Edit system-region examples for ten value types in a native popover; sync saved examples across platforms. Retire earlier custom prompts while preserving enabled state.
- Fix ChatGPT sign-in for valid ID tokens whose single audience is encoded as an array without azp. Keep signature, issuer, nonce, expiry and authorized-party checks.
- Group Google Drive and ChatGPT in Integrations with short synchronization and text helper labels. Remove the ChatGPT account details, account menu and usage link from Settings; keep the transcription API-key prerequisite and recovery controls.
- Connect an eligible ChatGPT plan for GPT-6 Luna text transformation and post-processing through official browser OAuth. Require a saved transcription API key first, explain the disabled button, protect tokens in Keychain and never silently fall back to API billing for text.
- Show compact transcription and post-processing request durations in history for Accurate and retranscription, including network upload and the full response. Live shows only a Live label. Keep old records without fabricated timings.
- Add a shared Text model selector beside recognition models, initially offering only GPT-6 Luna. Use its saved session-start selection for text transformation and post-processing; synchronize it through Google Drive.
- Configure integers, decimals, dates, times, durations, percentages, money, measurements, ranges and fractions with presets or custom examples. Preserve ambiguous values, precision and identifiers.
- Start with punctuation and capitalization enabled inside the optional Post-processing section, which remains off by default. Disabling all rules skips the extra request. Saved structured settings synchronize through optional Google Drive sync; generated instructions stay on the device.
- Show and apply post-processing only for Accurate dictation and Accurate retranscription, before local replacements. Live retains the saved settings without running post-processing. Preserve the original transcript with a warning if processing fails. Voice editing keeps its own instructions.
- Remove the transcription prompt editor and stop sending prompts to transcription models. Language and dictionary hints remain separate; punctuation and value formatting use Post-processing.
- Migrate the previous macOS Accurate punctuation setting to the shared configuration.

Legacy transcription-prompt preferences are cleared locally and discarded from Drive replicas, including nullable resets. Earlier post-processing prompts are replaced by menu defaults while preserving their enabled state. Old text-model selections are ignored; post-processing and voice editing use the shared Text model setting; AI history search uses GPT-6 Luna.

This release candidate is ad-hoc signed and not notarized by Apple. See INSTALL.txt in the disk image for Gatekeeper instructions.

Language-example smoke: the final unseen-value check passed for 62/64 languages and both automatic-detection cases. Azerbaijani and Gujarati results remained nondeterministic. Language examples guide Luna; they do not provide a general numerical validator for every language.
