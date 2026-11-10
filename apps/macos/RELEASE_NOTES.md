OpenDictate for macOS **0.7.0-rc.2** adds case-sensitive word replacements. Requires macOS 14+ on Apple Silicon or Intel.

- Turn on “Match case” for a phrase or regular expression to match its exact letter case.
- Replace characters inside words while keeping capitals with two case-sensitive regular expressions, such as `ё` → `е` and `Ё` → `Е`.
- The rule list tags regular expressions with `.*` and case-sensitive rules with `Aa`.
- Case-sensitive rules sync with Android 0.16.0. Older app versions stop syncing replacements until updated once such a rule exists.

Upgrade with Homebrew or download the universal DMG and replace OpenDictate in Applications. This release is ad-hoc signed and **not notarized**. Verify the attached SHA-256 checksum.
