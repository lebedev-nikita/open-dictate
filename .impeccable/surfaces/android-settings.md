---
version: 1
slug: "android-settings"
primary_target: "apps/android/app/src/main/java/com/opendictate/app/ui/SettingsScreen.kt"
related_targets: ["apps/android/app/src/main/java/com/opendictate/app/ui/SettingsTheme.kt", "apps/android/app/src/main/java/com/opendictate/app/ui/DictationButtonSettings.kt", "apps/android/app/src/main/java/com/opendictate/app/ui/ReplacementSheet.kt", "apps/android/app/src/main/java/com/opendictate/app/ui/GesturesScreen.kt", "apps/android/app/src/main/java/com/opendictate/app/ui/OpenDictateApp.kt"]
---

SCOPE: Android settings redesign. Mode: Operate. The existing dictation overlay, launcher mark, and website identity are outside this surface.

AUDIENCE: Android users configuring direct, system-wide dictation, checking readiness, and testing insertion before returning to their current app. Settings must work under the user's Android light/dark preference, keyboard, and font scale.

## Direction contract

THESIS: Make readiness and everyday dictation choices easy to find; replace the scattered setup form with readable task groups.

OWN-WORLD: Static neutral Material 3 light/dark themes following Android, system sans-serif roles, 16 dp rounded groups, quiet icons and dividers; monospace only for model IDs.

STORY: Check readiness, finish missing permissions, choose recognition behavior, adjust keyboard tools, then test dictation.

FIRST VIEWPORT: Compact brand/history/theme/language header, 16 dp top spacing, full-width readiness disclosure, then Dictation and its two-option mode control. Single column, 680 dp maximum, 20 dp gutters; disclosure opens setup actions in place. Omit the redundant Settings heading, supporting line and persistent synchronization status/actions. Pulling down at the top still requests synchronization when available; automatic synchronization remains active.

FORM: User-constrained modern, minimalist, functional settings. The current Appearance sheet follows the user-selected first bottom-sheet concept within the established native identity.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

## Shipped hierarchy and interactions

- Readiness disclosure: API key, microphone, and Accessibility actions; summary reflects missing steps or connecting/listening/processing state. It starts collapsed and can reopen for setup changes.
- Dictation: Accurate/Live selector, recognition model, languages, trailing punctuation, word replacements, and dictionary. Selected mode has a check and contrast fill; IDs use monospace.
- Dictation button: Appearance action with pixel-size and localized side summary, separate Gestures guide, transformation toggle and conditional editing model, selected-text dictionary toggle, and app exclusions. Transformation controls disable during active dictation.
- Advanced: response timeout and model refresh, with missing-key, loading, retry, and newly available model feedback.
- Test field, privacy note, and version finish the scroll.

Appearance, gestures, languages, word replacements, and dictionary use native modal bottom sheets. Languages offer automatic detection and full-row checkbox choices. Dictionary edits save automatically; Done closes its keyboard-aware sheet. Model IDs open native dropdown menus; timeout opens the existing dialog. History, API key, permissions, and exclusions retain their existing destinations.

Groups use 16 dp corners and tonal separation. Action rows are at least 76 dp, toggle rows 80 dp, and touch controls at least 48 dp. Full-row toggles expose one switch target. Native typography, growing rows, scrollable content, heading semantics, and status/navigation/IME padding support font scaling and keyboard use. The test field and dictionary editor bring their full bounds into view.

## Shared controls and auxiliary screens

The “Dictation button” group opens Appearance from one action row summarizing the visible pixel diameter and localized physical Left/Right side. Gestures remains its own action. The user-selected first bottom-sheet concept is implemented by `DictationButtonAppearanceSheet` in `DictationButtonSettings.kt`: a native `ModalBottomSheet` on SettingsSurface with a headlineSmall title, close action and vertically scrollable content. Its 24 dp horizontal/bottom padding and 20 dp spacing retain the existing native theme.

The 112 dp-high inset preview updates the white microphone circle and physical alignment immediately. Size uses a native continuous 24–96 dp slider rounded to whole dp, with a pixel-diameter readout. Position uses an outlined two-option selectable group with radio semantics, a checkmark and primaryContainer tone on the selected side. Each choice is at least 48 dp high. On narrow or large-text layouts, the label sits above full-width choices; otherwise it sits beside a 192 dp group. The distinct dictation-mode selector retains `SettingsChoiceRow` and its primary/onPrimary filled selection.

Changes save immediately and the sheet says so. Reset restores the complete default appearance, 52 dp and Right; it disables only when both already match. Done, close and native dismissal close the sheet without reverting saved changes. Saveable sheet visibility survives Activity recreation; native insets and scrolling keep controls reachable. Slider, preview and position expose localized accessible names and state. No new tokens or shipping raster assets are introduced.

The fresh finish review found the implementation and six native captures coherent, with no material UI/source issue. The documentation-only verdict scored the sole persistence fix resolved, with disposition **ship** at that scope. Current captures are `.impeccable/review/button-appearance-settings-phone-en.png`, `.impeccable/review/button-appearance-sheet-phone-en.png`, `.impeccable/review/button-appearance-settings-phone-dark-large-ru.png`, `.impeccable/review/button-appearance-sheet-phone-dark-large-ru.png`, `.impeccable/review/button-appearance-settings-tablet-dark-ru.png` and `.impeccable/review/button-appearance-sheet-tablet-dark-ru.png`. They cover English phone, Russian dark phone at 1.3 font scale, and Russian dark tablet emulation at 1600 × 2560 px, density 240, font scale 1.0. These ignored review captures are evidence, not shipping assets; an HTML detector does not apply to native UI.

Validation reports 162 passing JVM tests, Debug/Release lint and assembly, three new panel instrumentation tests and eleven existing settings/native-delivery tests. The panel lifecycle test also passed on the Russian dark large-text phone and tablet configurations. No real OpenAI calls were made; emulator evidence does not establish physical-device behavior.

History and app exclusions share the centered 680 dp maximum column and default-weight titleLarge headers with the gesture guide. History metadata uses bodySmall sans-serif; record text uses bodyLarge. Empty history omits search controls; saved-history search, privacy copy, errors and entries scroll together with IME insets. Error panels and deletion confirmation use semantic Material error roles, and AI-search failures use localized copy. The header language action is unfilled and muted like adjacent actions.

## Settled outcome

Light canvas is `#F3F4F6`; dark canvas is `#101114`. A top-right theme button opens System/Light/Dark choices with the selected option checked. The local preference survives restarts and applies across settings, history, exclusions and dialogs. System is the default and follows Android automatically; system bars follow the effective appearance. No wallpaper-derived colors are present. `SettingsTheme.kt` and `SettingsScreen.kt` are the implementation sources, with native roles recorded under `extensions.androidSettings` in the sidecar. The established settings redesign was reviewed as **ship**, with no material findings. The current Appearance sheet’s documentation verification is recorded above and resolved. No new shipping raster assets or visual tokens are introduced.

## Local word replacements extension

Mode remains Operate; the existing neutral Material 3 identity and settings hierarchy are preserved. Word replacements opens its own native bottom sheet from Dictation, separate from recognition hints in Dictionary. The keyboard-aware lazy list uses 24 dp horizontal padding and 12 dp vertical spacing. English/Russian source and replacement fields are outlined, full width, and keep their complete bounds in view. A global switch sits above the editor; saved rows pair a checkbox with source/replacement text and accessible edit/delete actions. Editing scrolls to the fields and changes Add to Save with Cancel; Done closes the sheet.

Blank inputs disable Add/Save. Validation guidance covers unique source phrases, field limits, and the rule limit; storage-error copy explains that saved data was kept and editing is unavailable. The empty state provides a source → replacement example. Native theme colors carry supporting and error copy, with wrapping and scrolling for long rules, large text, and keyboard insets. Changes take effect on the next dictation in either mode. Rules are local, are not sent to OpenAI, and leave voice-edit commands unchanged. Synchronization is deferred until Google Drive support exists; this extension adds no sync control.

Finish reviewer disposition: **ship**, with no material findings. Implementation sources are `ReplacementSheet.kt` and its entry in `SettingsScreen.kt`. Native captures `.impeccable/review/android-phone.png`, `.impeccable/review/android-phone-dark-large-text.png`, and `.impeccable/review/android-tablet.png` were reviewed without clipping. Native implementation and captures ground the review; an HTML detector does not apply. No shipping raster assets or new visual tokens were introduced.

## Synchronization provider list

Synchronization now has its own localized section heading and a single Google Drive provider row. The existing rounded group contains only the color vector logo, provider name and trailing native switch. The whole 80 dp minimum row is one accessible switch target. Enabling starts Google authorization; cancellation keeps the switch off and available. Disabling disconnects immediately, including during background synchronization. Descriptions, persistent status copy and manual action buttons are removed; errors appear as transient toasts, and existing automatic synchronization remains active.

Native emulator captures `sync-phone.png`, `sync-phone-dark-large-text.png` (1.3 font scale), and `sync-tablet-dark.png` in `.impeccable/review/` show the row without clipping. Google authorization launch and cancellation were exercised without signing in. Review disposition: **ship**. No raster assets or new theme tokens were added.

## Google Drive source-choice extension

Mode remains Operate in the established neutral Material 3 world. After reading cloud settings, a native `AlertDialog` appears only when known synced dictionary, replacements, dictation mode or model choices differ. Cloud is initially selected. Each full-row radio choice is one semantic target within a selectable group, with explicit replacement consequences; Local also explains propagation to other connected devices. Explanatory content scrolls vertically, native text wraps, and the single confirm action changes between Use cloud settings and Use local settings. Cancel or dismissal disconnects and preserves both settings sources.

The durable pending-connection state gates automatic merging across errors and restarts. Confirming re-downloads cloud and checks both local and cloud settings against the presented choice; changes require a renewed choice. Explicit source authority overrides offline clocks. Empty or matching cloud settings bypass the prompt; normal automatic synchronization resumes after success. The provider row retains its existing layout and switch interaction.

Implementation authority: `GoogleDriveSyncSettings.kt`, English/Russian strings in `apps/android/app/src/main/res/values*/strings.xml`, and `GoogleDriveSync.kt`. Final native captures are `.impeccable/review/drive-source-android-phone.png` (English light), `.impeccable/review/drive-source-android-phone-dark-large-ru.png` (Russian dark, 1.3 font scale), and `.impeccable/review/drive-source-android-tablet-dark-ru.png` (Russian dark tablet emulation). Instrumentation covers source-choice actions and the network gate. These are native emulator and tablet-emulation evidence, without an HTML detector or a claim of real-device Google sign-in coverage.

Finish reviewer disposition: **ship** for this narrow addition; all five finish-review sections passed, with no material fixes. Existing tokens, native theme, provider hierarchy and asset provenance remain the authority. Review captures are evidence, not shipping raster assets.

## Floating-button Gestures guide

Gestures opens from the Dictation button group as a native scrollable `ModalBottomSheet`. The approved clean-list layout retains SettingsSurface, a headlineSmall title, muted close action and 24 dp horizontal/bottom padding. Three unfilled rows are separated by full-width 0.5 dp outlineVariant dividers. There is no inset surface, Done action or Menu actions ledger.

Each row has an 88 dp minimum, 12 dp vertical padding and a 20 dp gap beside the existing 87 × 54 dp illustration zone. Actions use titleMedium with heading semantics; short gesture hints use bodyMedium in SettingsMuted, separated by 4 dp. A bodySmall note explains closing and reopening the keyboard to restore the button, 20 dp below the rows. English and Russian copy follows this action-first hierarchy without inline bold annotations.

The existing 75 percent tutorial scale, growing rows and localized screen-reader descriptions remain. Saved button position determines both swipe illustrations and directional hints. The runtime overlay and its menu actions retain their behavior.

Visibility is owned by a saveable flag in SettingsScreen. Close, system Back and native dismissal return to the existing settings scroll; Activity recreation restores the sheet. Implementation authority is GesturesScreen.kt, SettingsScreen.kt and the English/Russian strings. No new tokens or shipping raster assets are introduced.
