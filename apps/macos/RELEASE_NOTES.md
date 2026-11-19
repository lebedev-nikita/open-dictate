OpenDictate for macOS **0.9.1** adds whole-word matching to regular-expression replacements. Requires macOS 14.2+ on Apple Silicon or Intel.

- A new **Whole word** option keeps an expression from replacing part of a longer word, so `пулл?` → `pull` no longer turns “популярный” into “поpullярный”. It is on for new expressions; expressions saved earlier keep matching inside words until you turn it on. Phrase rules already matched whole words.
- Synced replacement lists with whole-word expressions require OpenDictate 0.9.1 on macOS and 0.16.3 on Android. Older versions stop syncing replacements until updated.

Upgrade with Homebrew or download the universal DMG and replace OpenDictate in Applications. This release is ad-hoc signed and **not notarized**. Verify the attached SHA-256 checksum.
