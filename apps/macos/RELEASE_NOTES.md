OpenDictate for macOS **0.9.3** lets phrase replacements match inside words. Requires macOS 14.2+ on Apple Silicon or Intel.

- The **Whole word** option now applies to ordinary phrases as well as regular expressions. Turn it off to replace a phrase inside longer words, so “читшит” → “cheatsheet” also fixes “читшиты”. It is on for new rules and for phrases saved earlier; expressions saved without it keep matching inside words.
- Rules that match inside words are tagged `…ab…` in the list.
- Synced replacement lists with a phrase matching inside words require OpenDictate 0.9.3 on macOS and 0.16.4 on Android. Older versions stop syncing replacements until updated.

Upgrade with Homebrew or download the universal DMG and replace OpenDictate in Applications. This release is ad-hoc signed and **not notarized**. Verify the attached SHA-256 checksum.
