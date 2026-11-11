OpenDictate for macOS **0.8.0** adds case-sensitive word replacements and redesigns the settings window. Requires macOS 14+ on Apple Silicon or Intel.

- Turn on “Match case” for a phrase or regular expression to match its exact letter case. For example, two case-sensitive patterns replace `ё` with `е` and `Ё` with `Е`.
- The rule list tags regular expressions with `.*` and case-sensitive rules with `Aa`.
- Case-sensitive rules sync with Android 0.16.0. Older app versions stop syncing replacements until updated once such a rule exists.
- Each settings page is titled by its name, and related settings are grouped on cards. Helper text is shorter.
- Text fields are inset on their cards with a focus ring. No field is focused when Settings opens.
- Granted permissions and the saved key show a quiet grey check.
- The sidebar shows dictionary and replacement counts and extends under the title bar.
- History keeps “Save history” at the top and “Delete all…” beside search. Dates follow the interface language.
- The dictionary editor fills its page with a single scroll; “Clean up” sits beside the title.
- OpenDictate appears in the Dock while Settings is open and returns to the menu bar when it closes.

Upgrade with Homebrew or download the universal DMG and replace OpenDictate in Applications. This release is ad-hoc signed and **not notarized**. Verify the attached SHA-256 checksum.
