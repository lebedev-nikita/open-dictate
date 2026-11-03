OpenDictate for macOS **0.6.0** adds regular expressions to word replacements. Requires macOS 14+ on Apple Silicon or Intel.

- Mark a replacement as a regular expression to match a case-insensitive pattern instead of a whole phrase. Use `\b` for word boundaries.
- Insert captured groups with `$1` or `${12}`, write `$$` for a dollar sign, or leave the replacement empty to delete the match.
- Patterns that do not compile, match empty text or reference missing groups are rejected when saved.
- Regular expression rules sync with Android 0.15.0. Older app versions stop syncing replacements until updated once such a rule exists.

Upgrade with Homebrew or download the universal DMG and replace OpenDictate in Applications. This release is ad-hoc signed and **not notarized**. Verify the attached SHA-256 checksum.
