# macOS verification

## 0.8.0 — shorter helper text

Verified on macOS 27.0.1, Apple Silicon, on 2026-11-11.

- All Swift tests pass.
- Captures of the Dictation, History, Keyboard shortcuts, Settings and Word
  replacements pages in English (Aqua) and Russian (Dark Aqua) showed titles
  without subtitles and the shortened helper text, without clipping.
- Universal packaging verified the DMG and signature.

No dictation, provider request or Drive sync was exercised for this change.

## 0.8.0-rc.6 — replacements header

Verified on macOS 27.0.1, Apple Silicon, on 2026-11-11.

- All Swift tests pass.
- `ReplacementSettingsLayoutTests` captures showed the Word replacements page
  without a subtitle in English (Aqua) and Russian (Dark Aqua).
- Universal packaging verified the DMG and signature.

No dictation, provider request or Drive sync was exercised for this change.

## 0.8.0-rc.4 — settings hierarchy and inset fields

Verified on macOS 27.0.1, Apple Silicon, on 2026-11-10.

- All Swift tests pass.
- The isolated smoke bundle was captured on every page in English (Aqua) and
  Russian (Dark Aqua). Section titles share the card edge, permission statuses
  use grey checks, light-mode fields are darker than their card, and History
  search sits on the list card.
- Closing Settings and reopening it left no field focused. The same check on
  0.8.0-rc.2 also showed no focus, so automatic focus was not reproduced; the
  window now clears its first responder on opening.
- Universal packaging verified the DMG and signature.

No dictation, provider request or Drive sync was exercised for this change.

## 0.8.0-rc.2 — settings layout fixes and Dock presence

Verified on macOS 27.0.1, Apple Silicon, on 2026-11-10.

- All Swift tests pass.
- The isolated smoke bundle showed the API key field and its buttons at one
  height, card dividers inset on both sides, and the Dictionary page with 40 terms
  scrolling only inside its editor, in English (Dark Aqua) and Russian (Aqua).
- `lsappinfo` reported the app as Foreground while Settings was open and as
  UIElement after Command-W closed it, in both languages.
- Universal packaging verified the DMG and signature.

No dictation, provider request or Drive sync was exercised for this change.

## 0.8.0-rc.1 — redesigned settings window

Verified on macOS 27.0.1, Apple Silicon, on 2026-11-10.

- All Swift tests pass, including the theme sampling and scroll-position layout
  tests against the new surfaces.
- `SettingsCaptureTests` renders every page in English (Aqua) and Russian
  (Dark Aqua) when `OPENDICTATE_VIEW_CAPTURES` is set.
- The isolated smoke bundle and a build of the previous commit were captured on
  every page in English and Russian, light and dark, with identical local data.
  Labels fit without clipping; the sidebar no longer truncates Keyboard shortcuts.
  History dates follow the interface language. Typing into a replacement field
  showed the accent focus ring on the lighter dark-mode input surface.
- Universal packaging verified the DMG and signature.

No dictation, provider request or Drive sync was exercised for this change.

## 0.7.0-rc.2 — case-sensitive word replacements

Verified on macOS 27.0.1, Apple Silicon, on 2026-11-10.

- All Swift tests pass. Shared fixtures run identically on ICU (macOS) and desktop
  Java: case-sensitive phrases beside case-insensitive ones, case-sensitive
  patterns inside words, and dash and quote patterns.
- Store tests cover case-sensitive rules that differ only in case, duplicates,
  editing and version 3 sync. The Android emulator store test runs the same
  rules on its ICU-backed regex.
- Native renders cover English (Aqua) and Russian (Dark Aqua): both options on one
  line and tinted `.*`/`Aa` tags in the list. Android emulator tests pass for the
  replacement store, sync and settings sheet in English and Russian.
- Universal packaging verified the DMG and signature.

No dictation, provider request or Drive sync was exercised for this change.

## 0.6.0 — regular expression word replacements

Verified on macOS 27.0.1, Apple Silicon, on 2026-11-03.

- All Swift tests pass. Shared fixtures run identically on ICU (macOS), desktop
  Java and Android's ICU-backed regex: groups, `${n}`/`$$` templates, empty
  replacements, earliest/longest precedence across literal and pattern rules,
  live trailing matches, Unicode `\w`/`\b`, skipped uncompilable patterns.
- Store tests cover significant pattern whitespace, invalid/empty-matching
  patterns, missing group references, per-kind duplicates and version 2 sync.
- Native renders cover English (Aqua) and Russian (Dark Aqua). The isolated smoke
  bundle showed the checked pattern editor with monospaced input and pattern
  placeholders; the invalid-pattern message was exercised on the Android emulator.
- Android emulator tests pass for the replacement store, sync and settings sheet in
  English and Russian. Universal packaging verified the DMG and signature.

No dictation, provider request or Drive sync was exercised for this change.

## 0.4.0 — choose settings when connecting Google Drive

Verified on macOS 26.6.2, Apple Silicon, on 2026-10-04.

- All 138 Swift tests pass. New pure tests cover empty/matching clouds,
  explicit cloud/local choices despite offline clocks, cleared dictionaries,
  stale replicas, missing cloud fields and unknown-entry preservation.
- Mock coordinator tests verify no application/upload before choosing,
  cancellation without settings changes, changed-cloud re-prompting,
  network failure/restart persistence and publishing local settings.
- Native production-view renders cover English/Russian in Aqua/Dark Aqua.
  Android emulator tests cover source actions and the download/choice/upload
  lifecycle, plus light phone, dark Russian phone at 1.3 font scale and tablet.
- Android debug unit tests/lint/assembly and release lint/assembly pass.
  Stable/RC release-version checks and universal macOS packaging verify CPU
  slices, ad-hoc signature, DMG integrity and SHA-256 output.
- The isolated local-only smoke bundle inserted synthetic text into a selected
  range of the disposable editor, preserving surrounding text. Secure-field
  insertion was rejected; changing fields rejected final delivery, and cancelling
  a captured target preserved its text. The real microphone captured 24 kHz PCM and discarded
  it in memory. These checks did not access API keys/history or call OpenAI.

Real-account source selection and Android/macOS cloud convergence remain manual
checks. Physical global hotkeys, microphone permission denial and Intel/macOS 14
hardware are not established by these tests. Native source-choice renders verify
the production view layout, not a live OAuth session or attached macOS sheet.


## 0.3.0 — retranscribe the latest recording

Verified on macOS 26.6.2, Apple Silicon, on 2026-10-02.

- All 132 Swift tests pass. Seven new session tests cover retained Live audio,
  current Accurate settings and replacements, Accurate history entries,
  duplicate retry suppression, recognition/recording failures, repeated retries,
  unavailable/secure/excluded fields, focus changes, noncooperative late results,
  cancellation during recording/processing/retry, replacement by a new recording,
  short recordings, voice-edit isolation and shutdown availability.
- Android debug unit tests/lint/assembly and release lint/assembly pass.
  Stable/RC release-version checks pass. Universal packaging verifies both CPU
  slices, the ad-hoc signature, DMG integrity and SHA-256 output.
- The local-only debug bundle recorded through the real microphone and inserted
  a synthetic result into the first disposable AppKit field. Its delayed retry
  action reused that recording and inserted into the second field without a new
  microphone session. Retrying with focus in the fixture's secure field left
  that field unchanged and showed the copy-result fallback message. The test
  bundle and fixture were quit afterward, releasing the retained recording.

Local checks never read the user's API key/history or call OpenAI. Model access,
recognition accuracy, provider latency, physical global hotkeys and Intel/macOS 14
hardware remain manual checks. The production status-menu command's availability
and delivery lifecycle are covered by model tests; the native retry check uses a
debug-only delayed menu action to let the editor regain focus.

## 0.2.0-rc.22 — keep dictation active across window changes

Verified on macOS 26.6.2, Apple Silicon, on 2026-10-01.

- All 119 Swift tests pass. Eight new AppModel session tests reproduce the old
  recording → processing transition and missing delivery, then verify switching
  windows, returning, stopping during preparation, destination freezing,
  unavailable/secure/excluded-field capture, cancellation, permission revocation
  and the retained voice-edit focus guard.
- The local-only debug bundle used the real microphone and HUD. Recording
  continued after changing fields and after activating a second disposable
  editor process. Finishing through the nonactivating HUD inserted synthetic
  text once into the second process's active field; no new insertion reached
  the original process. No API key/history was read or OpenAI request made.
- Android debug tests/lint/assembly and release lint/assembly pass. Stable/RC
  version checks, universal arm64/x86_64 packaging, ad-hoc signature and DMG
  integrity pass. Displayed version is 0.2.0-rc.22, bundle short version 0.2.0,
  build 23. The installer is not notarized.

Physical global hotkeys, permission denial/revocation, secure-field capture,
voice-edit cancellation, Spaces/full-screen windows, actual provider access and
Intel/macOS 14 hardware were not re-exercised natively. Session/delivery tests
cover the relevant guards; they cannot establish TCC or provider access.
TextEdit automation did not reliably change the system foreground application;
the cross-process focus check therefore used two editor fixtures with explicit
activation controls.

## 0.2.0-rc.17 — golden proportions around the waveform

Verified on macOS 26.6.2, Apple Silicon, on 2026-10-01.

- All 98 Swift tests pass. Production-view raster coverage verifies ten bars
  at maximum height, a 38.63 pt waveform span centered in the 62.5 pt capsule,
  and approximately 5.41 pt vertical insets around 17.5 pt bars. Capsule height
  / maximum bar height and capsule width / waveform width both equal the golden
  ratio. The production SwiftUI capture matches the selected third variant's
  capsule dimensions, with slightly wider spacing between bars.
- Native panel tests cover preparation → processing → recording → idle,
  unchanged foreground process/key window, finish mouse handling, and the
  existing circular processing indicator with mouse events ignored.
- Android debug tests/lint/assembly and release lint/assembly pass in this
  worktree; subsequent refinements change only macOS HUD geometry.
- Universal arm64/x86_64 packaging, ad-hoc signature, DMG integrity and release
  version validation pass. Displayed version is 0.2.0-rc.17, numeric bundle
  version 0.2.0, build 18. The release is not notarized.

No microphone capture or provider request was used for these HUD checks.
Physical global hotkeys, permission changes, secure fields, cursor edits, field
insertion, Spaces, full-screen apps and Intel/macOS 14 hardware were not
re-exercised; the earlier smoke-check evidence and compatibility limits below
still apply.

## 0.2.0-rc.16 — original Android application icon on both platforms

Verified on macOS 26.6.2, Apple Silicon, on 2026-10-01.

- The Android launcher vector is byte-for-byte identical to the original asset
  before rc.9. Its paths also match `docs/mark.svg`; adaptive and themed icons
  both use that vector directly.
- The macOS icon exporter reads those paths and their original group transform.
  All ten PNG sizes have the expected dimensions and transparent outer corners;
  pixel checks preserve the white contour and black hollow center. The universal
  application displays the outlined microphone in Finder's native preview.
- All 98 Swift tests, release-version checks, Android debug tests/lint/assembly
  and release lint/assembly pass. No Android icon lint findings remain.
- Universal arm64/x86_64 packaging, ad-hoc signature and DMG integrity pass.
  Displayed version is 0.2.0-rc.16, numeric bundle version 0.2.0, build 17.
  The application includes the microphone's third-party license notice.
  The release is not notarized.

This change affects application artwork and packaging only. Audio, permissions,
hotkeys and Accessibility delivery were not re-exercised; the existing runtime
smoke-check evidence and hardware/provider limitations below still apply.

## 0.2.0-rc.15 — waveform height and circular processing indicator

Verified on macOS 26.6.2, Apple Silicon, on 2026-10-01.

- All 98 Swift tests pass. Production-view raster tests verify ten bars, a
  2.5–17.5 pt level range, symmetric 2.5 pt maximum-level vertical insets,
  and the 27.5 × 27.5 pt circular processing background. Panel tests cover
  preparation → processing → recording → idle, mouse-event exclusion while
  processing, and unchanged foreground process/key window through resizing.
- The isolated, network-free native HUD preview showed the shorter waveform
  capsule and switched to the circular spinner when its finish control was
  clicked. No microphone capture or OpenAI request was used for this preview.
- Android debug tests/lint/assembly and release lint/assembly pass.
- Universal arm64/x86_64 packaging, ad-hoc signature, DMG integrity and release
  version validation pass. Displayed version is 0.2.0-rc.15, numeric bundle
  version 0.2.0, build 16. The release is not notarized.

This change only adjusts HUD geometry. Physical global hotkeys, permission
changes, secure fields, cursor edits, field delivery, provider access, Spaces,
full-screen apps and Intel/macOS 14 hardware were not re-exercised; the
existing smoke-check evidence and compatibility limits below still apply.

## 0.2.0-rc.12 — universal final clipboard insertion

Verified on macOS 26.6.2, Apple Silicon, on 2026-10-01.

- All 91 Swift tests pass. Delivery coverage exercises one final paste, delayed
  readback, ignored paste without retries, UTF-16 selection replacement, whole-field
  voice edits, focus/caret/user-edit guards, cancellation before delivery and
  cancellation while an acknowledged selection update is still pending.
  Clipboard tests verify preserved formats, confidential transcript items and
  protection of a newer user copy.
- A local-only debug bundle inserted synthetic text in Google Chrome's 2ch.org
  comment field. The complete Accurate microphone/start/stop/delivery path also
  passed there. Nothing was posted to the site or sent to OpenAI by these checks.
- The disposable AppKit editor received a final clipboard paste. Secure-field
  capture was rejected; cancelling before paste left the field unchanged, and
  changing to another field rejected final delivery. The fixture now has a standard
  Edit menu so it can handle Command+V like an ordinary native editor.
- Both modes now deliver only after recording stops. No AX text-write fallback or
  per-application exception remains. Paste events still target the original process;
  every delivery validates the original exact element, text and caret.
- Android debug tests/lint/assembly and release lint/assembly pass after merging
  automatic Drive synchronization. Universal arm64/x86_64 packaging, ad-hoc
  signature, DMG integrity and checksum verification pass. Version is 0.2.0-rc.12,
  numeric bundle version 0.2.0, build 13; the release is not notarized.

No provider request was made during native smoke checks. Physical global hotkeys,
permission denial/revocation, Spaces, full-screen apps, actual OpenAI access and
Intel/macOS 14 hardware retain the manual-check limitations described below.

## 0.2.0-rc.11 — automatic Google Drive synchronization

Verified on macOS 26.6.2, Apple Silicon, on 2026-10-01.

- All 90 Swift tests pass. New deterministic scheduling tests cover typing batches,
  pulls during editing, edits during download/upload, queued refresh requests and
  retry after failure. Preference tests ensure remote imports, unchanged assignments
  and unrelated local settings do not emit upload events. Upload decisions cover
  unchanged checks, cloud-only changes, local changes and first connection.
- All 139 Android JVM tests, debug/release lint and assembly pass. Six isolated
  storage tests pass on an API 36 emulator, including local-change revisions and
  import suppression; no real Google account is used by these tests.
- Universal arm64/x86_64 packaging, ad-hoc signature and DMG integrity pass.
  Release version is 0.2.0-rc.11, numeric bundle version 0.2.0, build 12.
  The release is not notarized.
- This revision changes synchronization scheduling and app-opening triggers;
  audio, Accessibility delivery and OAuth registration are unchanged. The real
  Google-account and cross-device limitations recorded below still apply.

## 0.2.0-rc.10 — Google Drive settings and replacement synchronization

Verified on macOS 26.6.2, Apple Silicon, on 2026-10-01.

- 85 Swift tests cover settings convergence and bounded wire data, replacement
  import/edit/deletion, individual/master enabled states, journal migration,
  preservation of active-session rules, invalid-data rejection and recovery
  after reducing oversized settings. Both platforms read a shared JSON fixture.
- Android passes 135 JVM tests, debug/release lint and assembly. Five isolated
  storage/synchronization tests pass on an API 36 emulator; they do not grant
  Google account access or edit the user's settings.
- The original local macOS sync build completed real Desktop OAuth sign-in and
  initial/repeat synchronization with the configured test account. Cross-device
  Google synchronization still needs a signed Android device using that account.
- Universal arm64/x86_64 packaging, ad-hoc signature, DMG integrity and SHA-256
  pass. The displayed release version is 0.2.0-rc.10, numeric bundle version
  0.2.0 and build 11. The release is not notarized.

Provider access, physical hotkeys and the hardware/permissions/Spaces limitations
below remain manual compatibility checks. Replacement changes do not alter audio,
Accessibility delivery, hotkey registration or voice editing behavior.

## 0.2.0-rc.6 — selectable waveform indicator

Verified on macOS 26.6.2, Apple Silicon, on 2026-10-01.

- All 52 Swift tests pass. New coverage verifies saved style choices and legacy/
  unknown-value fallback, bounded chronological levels, silence/invalid samples,
  clearing on reset, observable delivery to the HUD and production-view pixels:
  ten bars in a 50 × 22 pt capsule, with newest input changing the rightmost bar.
- A production NSPanel test verifies preparation/processing/idle behavior,
  unchanged foreground process/key window, nonactivation, exact content size and
  processing mouse-event exclusion.
- In the isolated native debug app, selected Waveform in Settings and inspected
  the new row/copy in English and Russian. The capsule showed the silence bars;
  clicking it replaced the finish action with the native processing spinner.
- The explicit two-second microphone check captured 24 kHz PCM through the real
  recorder and fed the HUD meter, then discarded audio and levels in memory.
  No OpenAI request was made, and no API key/history was read. Offscreen production
  view captures confirmed a synthetic speech envelope and reset-to-silence state.
- Android debug tests/lint/assembly and release lint/assembly pass.
- Stable/RC version checks, universal arm64/x86_64 packaging, ad-hoc signature,
  DMG integrity and SHA-256 pass. Numeric bundle version is 0.2.0, build 7;
  displayed release version is 0.2.0-rc.6. The release is not notarized.

Native preview checks exercise the production component and callback, rather
than actual OpenAI submission. Provider access, physical hotkeys, third-party
insertion and the hardware/permissions/Spaces limitations below remain manual
compatibility checks. The recorder's chunks, RMS calculation and network flow
are unchanged; level history uses only ten floats in memory and clears on exit.

## 0.2.0-rc.4 — indicator and insertion caret

Verified on macOS 26.6.2, Apple Silicon, on 2026-09-30.

- All 41 Swift tests pass. New regression tests exercise cumulative selection
  replacement, UTF-16 caret placement, cancellation, focus/cursor/edit guards,
  and value-only editors that finish a text update after acknowledging AXValue.
  The native NSTextView Accessibility setters verify real text/selection behavior;
  mocked transport tests cover the deferred value/caret-reset sequence.
- Production-view raster tests verify the red finish control during preparation,
  the 44 × 22 pt capsule, 14 pt circle and 7 pt visible trailing inset.
  Offscreen NSHostingView captures were visually inspected for preparation,
  recording and the 22 × 22 pt processing spinner. No focus is taken by rendering.
- Android debug tests/lint/assembly and release lint/assembly pass on the merged
  main-branch code.
- Stable/RC version validation and universal arm64/x86_64 packaging pass;
  bundle build is 5. Ad-hoc signature, DMG integrity and SHA-256 verification pass.
  The release remains locally signed and is not notarized.

The desktop automation did not reliably activate the disposable editor, and
application screenshots were unavailable in this session. The debug bundle was
built, but cross-process insertion, the startup finish-button interaction and
physical hotkeys require a manual check. No API key or history was read by the
smoke checks; no OpenAI request or audio capture was made. Existing secure-field,
permission, third-party clipboard/editor, Spaces and hardware limitations below
still apply. The startup stop path switches the HUD to processing immediately
and waits for recorder setup before submitting once.

## 0.2.0-rc.3 — shortcut customization

Verified on macOS 26.6.2, Apple Silicon, on 2026-09-30.

- All 34 Swift tests pass, including after merging the compact indicator.
  Stable/RC release-version validation also passes. The 11 new shortcut tests cover F1–F20, navigation
  and arbitrary key combinations, synthetic Fn-flag normalization, legacy
  preference migration, independent shortcut persistence, duplicate detection,
  Fn/modifier-tap release and chord guards, autorepeat, and real Carbon
  registration conflict rollback/cleanup.
- In the isolated debug bundle, recorded F5 without modifiers, Shift+Command+F5
  and Command+Q; Command+Q was captured without quitting the application.
  Duplicate assignments kept the previous shortcut and showed a localized error.
  Escape cancelled recording; the key menu assigned Globe/Fn; reset restored
  the defaults. English and Russian settings were inspected in the native window.
- The disposable AppKit editor received synthetic cumulative text and cancellation
  restored its original value and selection. The sequence passed with ordinary
  shortcuts and with the Globe/Fn monitor configured. No API key/history was read,
  OpenAI request made, or audio persisted. The installed app was reopened afterward.
- Android debug tests/lint/assembly and release lint/assembly pass.
- Universal arm64/x86_64 packaging, ad-hoc signature and DMG integrity pass;
  the installer has a SHA-256 attachment. The full release version is
  0.2.0-rc.3; the numeric bundle version is 0.2.0, build 4. This release is not notarized.

Automation delivers keys to an app, so it cannot validate physical global Carbon
hotkeys or synthesize the Globe/Fn key. Fn tap/chord behavior has deterministic
tests, but Apple/external-keyboard event delivery, macOS reserved shortcuts,
permission denial/revocation, Spaces, full-screen apps and Intel/macOS 14 remain
manual compatibility checks. The existing focus/cursor/secure-field guard tests
remain green; provider model access and transcription accuracy are not tested.


## 0.2.0-rc.1

Verified on macOS 26.6.2, Apple Silicon, on 2026-09-30.

- Native captures verify the 88 × 44 pt recording capsule with only a
  microphone and red finish button, and the 44 × 44 pt processing indicator
  with only a spinner. Preparation uses the same spinner layout; idle hides
  the panel.
- A local debug preview uses the production panel and view without recording
  or networking. Clicking its finish button replaced the accessible button
  with a busy indicator. The disposable editor retained its focused text field
  before and after the click.
- 23 Swift tests and the stable/RC release-version validation checks pass.
  Android debug tests/lint/assembly and release lint/assembly pass.
- Universal release packaging verifies arm64/x86_64, the ad-hoc signature and
  DMG integrity. The full RC version appears in the app and installer name;
  the bundle short version remains numeric, with build number 2.

The preview validates the HUD interaction and state transition. Actual OpenAI
submission is not exercised; the finish action uses the existing session stop
path. The compatibility limitations below still apply.

## 0.1.0

Verified on macOS 26.6.2, Apple Silicon, on 2026-09-30. The deployment target is
macOS 14; Intel is cross-compiled, not exercised on Intel hardware.

- 23 Swift tests cover UTF-16 composition and delivery guards, audio/WAV
  boundaries, protocol payloads, HTTP responses, WebSocket ordering, bounded
  queues, cancellation, structured edits and history search batching.
- The bundled debug app captured two seconds of real microphone input as
  24 kHz mono PCM16 and discarded it in memory.
- A disposable AppKit editor received synthetic cumulative text through the
  production Accessibility integration. Cancellation restored the original
  text and selected range. A late update after focus changed was rejected.
- Capture of the fixture's secure text field was rejected.
- Dictation, dictionary, empty history and settings were visually inspected in
  the native window; Russian navigation was also inspected at the minimum
  window size. Synthetic key paste into SecureField worked; selecting text in
  Dictionary enabled the standard responder-chain editing commands.
- Packaging verifies both CPU slices, the ad-hoc bundle signature, disk image
  integrity and writes a SHA-256 checksum.
- Android debug tests/lint/assembly and release lint/assembly pass after the
  monorepo migration; source changes from main are preserved.

Local smoke checks use an isolated preference domain and in-memory history.
They never read the user's API key/history or call OpenAI. Tests use mock
provider responses, so they do not establish API-account access, transcription
accuracy or provider latency. The UI automation sends keyboard events directly
to an app rather than through the global Carbon shortcut; the native insertion
checks therefore also have delayed debug menu actions. Physical global hotkeys,
clipboard fallback in third-party editors, login launch, Spaces, and Intel/
macOS 14 hardware remain manual compatibility checks. All debug check actions
are absent from release builds.
