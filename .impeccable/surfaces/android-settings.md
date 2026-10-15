---
version: 1
slug: "android-settings"
primary_target: "apps/android/app/src/main/java/com/opendictate/app/ui/SettingsScreen.kt"
related_targets: ["apps/android/app/src/main/java/com/opendictate/app/ui/SettingsTheme.kt", "apps/android/app/src/main/java/com/opendictate/app/ui/ReplacementSheet.kt", "apps/android/app/src/main/java/com/opendictate/app/ui/GesturesScreen.kt", "apps/android/app/src/main/java/com/opendictate/app/ui/OpenDictateApp.kt"]
---

SCOPE: Android settings redesign. Mode: Operate. The existing dictation overlay, launcher mark, and website identity are outside this surface.

AUDIENCE: Android users configuring direct, system-wide dictation, checking readiness, and testing insertion before returning to their current app. Settings must work under the user's Android light/dark preference, keyboard, and font scale.

## Direction contract

THESIS: Make readiness and everyday dictation choices easy to find; replace the scattered setup form with readable task groups.

OWN-WORLD: Static neutral Material 3 light/dark themes following Android, system sans-serif roles, 16 dp rounded groups, quiet icons and dividers; monospace only for model IDs.

STORY: Check readiness, finish missing permissions, choose recognition behavior, adjust keyboard tools, then test dictation.

FIRST VIEWPORT: Compact brand/history/theme/language header, large Settings heading and supporting line, full-width readiness disclosure, then Dictation and its two-option mode control. Single column, 680 dp maximum, 20 dp gutters; disclosure opens setup actions in place.

FORM: User-constrained modern, minimalist, functional settings. No concept seed or candidate ranking was used.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

## Shipped hierarchy and interactions

- Readiness disclosure: API key, microphone, and Accessibility actions; summary reflects missing steps or connecting/listening/processing state. It starts collapsed and can reopen for setup changes.
- Dictation: Accurate/Live selector, recognition model, languages, trailing punctuation, word replacements, and dictionary. Selected mode has a check and contrast fill; IDs use monospace.
- Keyboard: Gestures guide, transformation toggle and conditional editing model, selected-text dictionary toggle, and app exclusions. Transformation controls disable during active dictation.
- Advanced: response timeout and model refresh, with missing-key, loading, retry, and newly available model feedback.
- Test field, privacy note, and version finish the scroll.

Languages, word replacements, and dictionary use native modal bottom sheets. Languages offer automatic detection and full-row checkbox choices. Dictionary edits save automatically; Done closes its keyboard-aware sheet. Model IDs open native dropdown menus; timeout opens the existing dialog. History, API key, permissions, and exclusions retain their existing destinations.

Groups use 16 dp corners and tonal separation. Action rows are at least 76 dp, toggle rows 80 dp, and touch controls at least 48 dp. Full-row toggles expose one switch target. Native typography, growing rows, scrollable content, heading semantics, and status/navigation/IME padding support font scaling and keyboard use. The test field and dictionary editor bring their full bounds into view.

## Settled outcome

Light canvas is `#F3F4F6`; dark canvas is `#101114`. A top-right theme button opens System/Light/Dark choices with the selected option checked. The local preference survives restarts and applies across settings, history, exclusions and dialogs. System is the default and follows Android automatically; system bars follow the effective appearance. No wallpaper-derived colors are present. `SettingsTheme.kt` and `SettingsScreen.kt` are the implementation sources, with native roles recorded under `extensions.androidSettings` in the sidecar. The final reviewer disposition is **ship**, with no material findings. No new raster assets were introduced and no design decisions remain unresolved.

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

Gestures opens from Keyboard as a full-screen Read surface within Operate settings, preserving the neutral Material 3 palette and system typography. The user selected modern concept 1, `.impeccable/review/modern-gesture-concepts/01-fine-echo.png`, as illustration provenance within the app's native identity; the latest size/type refinement governs the current guide, and exact pixel matching is not required. Three picture-and-heading rows have no visible captions or introduction. English headings are “Hold to open the menu”, “Swipe left to cancel dictation” and “Swipe right to hide the button”; Russian headings are “Удержание: меню”, “Влево: отменить диктовку” and “Вправо: скрыть кнопку”. English/Russian screen-reader descriptions explain idle/active applicability, cancellation and keyboard close/reopen recovery.

Typography matches the standard settings surface: the header uses titleLarge at its default weight; gesture labels use bodyLarge with bold gesture names and regular outcomes, marked in each locale’s string resources; menu-item titles use bodyLarge at default weight; the menu heading matches SectionTitle with titleSmall, Medium weight and SettingsMuted; explanations match RowCopy with bodySmall and SettingsMuted. The native group retains 16 dp corners, 12 dp horizontal/6 dp vertical row padding, and 0.5 dp outlineVariant borders/dividers at 0.65 opacity. Rows have a 72 dp minimum; a 16 dp text gap aligns labels beside fixed 87 × 54 dp illustration zones.

The private tutorial scale is 0.75. Complete blocks stay centered: hold uses a 54 dp box with 25.5/22.5 dp ripple radii, 0.75 dp theme-outline strokes and 0.25/0.5 opacity. Swipe blocks are 81 dp wide: 33 × 33 dp chevrons, a 9 dp gap and the 39 dp white tutorial button with an 18 dp actual microphone/stop drawable and 3.75 dp elevation. Chevrons lead left swipe and trail right swipe, so button centers shift while complete blocks share an axis. Echoes retain rounded caps/joins, SettingsMuted and outward opacity 1/0.55/0.25; their scaled strokes are 1.3125 dp, arms 9 dp and offsets 10.5 dp. The runtime overlay remains 52 dp. Canvas vectors and existing drawables introduce no shipping raster assets; these measurements remain guide-specific.

“Menu actions” is a native ledger with 16 dp vertical padding and inset dividers. Explanations cover Accurate retranscription, pasting at the cursor, editing selected text or all text through a voice command with Text transformation enabled, and adding selected text to Dictionary. Dictionary requires selected text in a non-password field independently of the selected-text context-menu toggle. `GesturesScreen.kt`, `OpenDictateApp.kt`, the Keyboard entry in `SettingsScreen.kt` and localized strings are the implementation authority; `OpenDictateAccessibilityService.kt` establishes action availability.

The scrollable guide retains a centered 680 dp maximum column, 24 dp gutters, growing text, heading semantics and status/navigation insets. Toolbar/system Back preserve the settings scroll; the saveable route survives recreation. No new theme tokens or shipping raster assets are introduced.

Final compact-guide finish-review disposition: **ship**; all five sections passed with no material fixes. Review checked current source, all seven native captures and reconciled records for the standard settings typography and 75 percent tutorial illustration scale. Seven current guide capture paths in `.impeccable/review/` are `gestures-compact-phone-ru.png`, `gestures-compact-phone-en.png`, `gestures-compact-dark-large-ru.png`, `gestures-compact-dark-large-en.png`, `gestures-compact-menu-dark-large-ru.png`, `gestures-compact-menu-dark-large-en.png` and `gestures-compact-tablet-dark-ru.png`. The Russian/English phone paths are also the primary captures, with no extra aliases. All seven captures have been validated after the Russian dark-large recapture settled. Evidence covers both languages, dark large text and tablet emulation. Captures are native review evidence, not shipping assets; an HTML detector does not apply. Required local Gradle checks passed with 151 unit tests, Debug/Release lint and assembly; one navigation/recreation/content/alignment instrumentation test passed, with zero failures.
