# OpenDictate for macOS

A native menu bar application built with SwiftUI, AppKit, AVAudioEngine, Core Audio
process taps, Accessibility, Keychain and URLSession. No third-party dependencies or backend.

## Installation

```bash
brew install --cask lebedev-nikita/tap/open-dictate
```

See the [setup guide](../../README.md#install-on-macos) for permissions,
upgrades, migration from a manually installed DMG and manual installation.

## Development

Requires macOS 14.2+ and Xcode 16+ (Swift 5.9 package, compatible with Swift 6).
Meeting recording uses Core Audio process taps, available from macOS 14.2.

```bash
swift test --package-path apps/macos
apps/macos/scripts/package.sh
open dist/macos/OpenDictate.app
```

Windows that tests order front are fully transparent, so test runs do not flash
on screen; set `OPENDICTATE_SHOW_TEST_WINDOWS=1` to watch them.

Open `Package.swift` in Xcode for source editing and debugging. Run the bundled
app for permission testing: a bare Swift executable has a different TCC identity.
The universal build targets `arm64` and `x86_64`, with a macOS 14.2 deployment target.

## Runtime

- `AppModel` owns a single session, immutable settings, cancellation, permissions
  and result delivery. Session UUIDs discard late callbacks.
- `MeetingController` owns a meeting next to dictation, never inside `AppModel`'s
  session: two capture channels, the shared meeting clock, the recognition queue,
  pause/resume, finishing and cancellation. `MeetingStore` persists transcripts.
  Segmentation, channel merging, echo removal, the stored model and Markdown live in
  `OpenDictateCore` (`MeetingSegmentation.swift`, `Meeting.swift`, `MeetingEcho.swift`).
- `TextTarget` captures the focused Accessibility element, process, value and
  UTF-16 selection. Every insertion rechecks focus, text and caret. Both modes
  capture the active eligible field when ordinary dictation stops, then paste
  one final result into its latest selection. Voice
  editing retains its captured source selection. Cancel before delivery leaves the field untouched.
- `HotKeys` uses exclusive Carbon registration for ordinary key combinations.
  Globe/Fn combinations and modifier-only taps use AppKit event monitors with
  the existing Accessibility permission, without requesting Input Monitoring.
  Only key codes and modifier flags are inspected; typed characters are not read
  or stored. Holding a shortcut does not repeatedly toggle dictation. Escape is
  registered only during recording/processing (or when explicitly assigned).
- `AudioRecorder` receives input through `AVAudioSinkNode`. Its native callback
  copies into a bounded, preallocated single-producer/single-consumer buffer
  without allocations, locks or dispatch calls. A serial worker drains it every
  5 ms and converts to mono PCM16 at 24 kHz; network chunks remain 40 ms.
  Readiness follows the first converted samples. Stopping drains captured input
  and the converter's tail; overflow fails visibly. Idle preparation never starts
  microphone capture; Bluetooth graphs are opened only when dictation starts.
  Hardware format changes rebuild capture while preserving accepted audio and
  streaming chunk boundaries. Unrecoverable changes fail visibly and retain the
  valid prefix for an explicit Accurate retry. Live audio stays off disk; Accurate builds a WAV in memory.
  Both stop below the provider's 25 MB upload limit. Meeting channels pass no limit:
  they keep no recording and cut utterances themselves.
- `ProcessTapCaptureEngine` is a second capture engine for `AudioRecorder`: a private
  Core Audio process tap read through a private aggregate device that contains only
  the tap. It never opens an output device's microphone, so AirPods keep their
  playback profile. Its IOProc writes into the same lock-free ring as the
  microphone, so conversion, chunking and levels are shared.
- `LiveTranscriptionSession` opens immediately, bounds early audio to 300 chunks,
  flushes after `session.updated`, serializes sends and explicitly commits.
  Overflow fails visibly instead of silently dropping speech.
- `OpenAIClient` uses ephemeral HTTPS sessions. Voice edits and post-processing
  use structured Responses output with `store: false`. History search runs on-device.
- `RecordingIndicator` owns a nonactivating panel that cannot become key/main.
  Settings choose Microphone (the existing 55 × 27.5 pt finish control) or Waveform
  (a black 62.5 × 28.32 pt capsule with ten white level bars, ranging from
  2.5 pt in silence to 17.5 pt at full level). At full level, the waveform
  spans 38.63 × 17.5 pt; capsule width / waveform width and capsule height /
  waveform height both equal the golden ratio, with 5.41 pt vertical insets.
  New input appears on
  the right and older levels move left at ten updates per second, showing about
  one second of microphone levels independently of audio buffer size. Only ten
  normalized RMS levels live in memory and are cleared
  on every session exit. The HUD alone observes those updates, without rebuilding
  menu state. Click the red button or waveform capsule to stop/submit without
  moving focus. Preparation already offers the finish action; processing shows
  a native spinner in a 27.5 × 27.5 pt circle for either style, restoring
  the original vertical spacing around the spinner. Sessions with post-processing
  and voice edits show the current transcription, post-processing or editing icon
  inside a progress ring. Stage changes preserve the processing HUD's size,
  position and nonactivating behavior. Ordinary dictation without post-processing
  keeps the native spinner.
- The HUD never takes keyboard focus. Ordinary dictation continues while typing,
  moving the caret or switching fields/windows/apps. Stopping captures the active eligible field for delivery.
  Voice editing still requires the original text and selection to remain unchanged.
  Voice editing stops and detaches when its original source changes. Revoking
  Accessibility or sleeping cancels a recording. Switching fields after stopping
  detaches delivery. Final results remain available to copy.

Supported editors expose an Accessibility string value and selected text range.
On activation and before capture, OpenDictate enables supported
`AXManualAccessibility` / `AXEnhancedUserInterface` flags so Electron editors
such as T3 Code expose their focused field even without VoiceOver. This
preparation reads only those flags and skips excluded apps; it does not activate
windows or read unrelated UI contents.
All applications receive one final clipboard paste, including Chrome, native editors
and T3 Code. Live still streams recognition immediately, but delivery to the field
happens when recording finishes, just like Accurate. Accessibility is used to
validate the process and exact field captured at stop, capture its latest value
and UTF-16 selection for ordinary dictation, and to select
the whole field for voice editing when no text was selected. Voice editing validates
the unchanged source text and selection. Once clipboard delivery begins, any further
text or selection change rejects the paste. No AX text writes or
application-specific insertion exceptions remain. Paste is posted once to the captured
process and verified with a bounded 800 ms readback window; unacknowledged pastes
are never retried. The previous clipboard items and types are restored if unchanged.
Temporary transcript items are marked confidential for compatible clipboard utilities.
Editors without usable Accessibility metadata need manual Copy last transcript.
Password fields are deliberately excluded.

### Meetings

**Record meeting** in the menu bar (or its own shortcut, unassigned by default)
records the microphone as **Me** and call audio as **Other speaker**. The same
item finishes the meeting; **Show meeting** reopens its window. **Copy last
meeting** puts the newest transcript on the clipboard as Markdown. The first
recording shows a one-time notice that other participants' speech is sent to
OpenAI and that their consent is the user's responsibility.

The meeting window chooses the call audio: one running application (apps playing
audio are listed first) or all system audio. The choice is remembered. An app tap
includes the app's helper processes (same bundle prefix or descendants of its main
process) and follows processes that start later, such as a browser tab joining
audio. Closing the window does not stop recording. It shows the live transcript,
elapsed time, channel levels, Pause/Resume, Finish and a confirmed **Cancel
meeting**, which deletes the meeting. Starting from the shortcut leaves keyboard
focus in the call app. There is no floating indicator for meetings.

macOS asks for **System Audio Recording** (not Screen Recording) the first time a
tap starts; until it is allowed, the tap stays silent and macOS can hold the tap's
start. The microphone channel never waits for it. After 15 seconds without call
audio, the window offers System Settings. The grant follows the stable signing
identifier, so it survives ad-hoc rebuilds like Accessibility.

Each channel is cut into utterances at pauses. Measured on Russian and English
read speech, 700 ms pauses gave the lowest divergence from whole-file recognition
(2.3% RU, 1.8% EN word differences; 300 ms: 5.1% and 2.8%), with a median utterance
of about 7 seconds. Utterances keep 300 ms before and after speech, need 300 ms of
speech, accept a 300 ms pause after 20 seconds and are cut at the quietest frame
before 30 seconds. The noise floor is the quietest 100 ms over 10 seconds, capped at
−45 dBFS; speech is 12 dB above it and at least −50 dBFS. Silence is never sent.

Every utterance is stamped by its speech start on one meeting clock (pauses
excluded) and sent alone to `/v1/audio/transcriptions` with the Accurate model,
speech languages, dictionary, timeout and word replacements captured at start. Up
to three requests run at once; a 7-second utterance took about one second, so text
appears a few seconds after a phrase ends.
Network, timeout, rate-limit, 401/403 and server failures retry the same utterance
(1, 2, 4, 8, 16, then every 30 seconds); waiting utterances are shown as waiting for
recognition. Other client errors mark the utterance **not recognized**, keeping its
timestamp. Empty results, such as noise, are dropped.

Memory stays bounded for any length: an utterance's audio is released once
recognized, an utterance is at most 30 seconds, and untranscribed audio is capped at
15 minutes (43 MB). Reaching the cap during an outage pauses capture with a visible
notice and notification instead of dropping speech; recording can resume once
recognition catches up. Finishing stops capture and waits for queued utterances;
**Finish now** marks the rest as not recognized. Sleep pauses the meeting; resume it
after waking. Quitting asks first; quitting anyway keeps the text already recognized.

Without headphones the microphone also hears the call. Microphone text waits for
the call text around it (±1.5 s) and loses word runs that repeat it; a sentence that
is at least half echo is removed. In a simulation with the far end at −6, −12 and
−24 dB, no echoed words remained under Me and every recognized own sentence was
kept, but strong echo during double talk masked short replies before recognition
(9 of 9 own sentences survived at −24 dB, 7 of 9 at −12 dB, 5 of 9 at −6 dB). Words
you repeat after the other side within 1.5 s may be removed. Headphones avoid echo.

Dictation, post-processed dictation and voice editing keep working during a meeting
with their own microphone recorder; their speech is addressed to a text field and is
left out of the meeting transcript. The menu bar microphone gains a red badge while
a meeting records and a monochrome dot while it is paused; dictation recording keeps
its red circle, also during a meeting.

Meetings are stored as one JSON file each in
`~/Library/Application Support/OpenDictate/Meetings/` (folder 0700, files 0600),
excluded from Drive and history. A meeting being recorded is saved as it goes; one
left unfinished by a crash becomes **interrupted** on the next launch. Settings →
Meetings lists date, source and duration; each meeting offers search, speaker
renaming for the whole transcript, Copy as Markdown, Save as `.md` and Delete, and
the list offers Delete all. An optional auto-export folder receives every finished
meeting as Markdown and never replaces an existing file. Markdown is the only
export format:

```markdown
# Meeting 2026-11-16, Zoom, 47 min

**[00:00:04] Me:** Let's start with the release plan.

**[00:00:11] Other speaker:** Agreed, channels first.
```

Blank lines keep one line per utterance in Markdown renderers. Not recognized
utterances appear as `*(not recognized)*`. Russian uses «Встреча», «Я» and «Собеседник».

### Speech languages

Settings → Dictation → Speech languages opens a searchable list of 64 languages
with native checkboxes. Select any combination without closing the list. Search
by English, Russian or native name, or an ISO language code such as `de`.
Automatic detection clears the selection and sends no language hints. Changes
save immediately and apply to the next Live or Accurate session; selection is
disabled during dictation. Existing single-language and Russian + English
preferences migrate automatically. Language hints remain local preferences.

### ChatGPT plan usage

Save the transcription API key first, then use **Continue with ChatGPT** beside
the text model. Eligible plans can fund Luna text transformation and
post-processing; transcription still uses the API key. Requests use the public
Responses API directly, with no silent API billing fallback. Sign-in and token
rotation use device-only Keychain storage, independent of Drive. See
[setup and protocol references](../../docs/chatgpt-plan.md).

### Post-processing

Post-processing rules and regional examples synchronize through Google Drive. All
rule-enable switches remain device-local, including individual value types. macOS
has no global post-processing switch: the invoked dictation action chooses it.

The shared **Text model** selector sits beside recognition models and offers
**GPT-6 Luna**. Voice editing and post-processing use the standard service tier.
Saved or synchronized Fast preferences no longer enable Fast; existing history
keeps the tier used for each request.

Settings → Dictation → **Post-processing** configures rules for the dedicated
**Dictation with post-processing** action, available regardless of the main mode.
Punctuation is the initial rule. The shared Text model selection controls both
post-processing and voice editing. Ordinary dictation always skips post-processing,
including when older preferences had its global switch enabled.

Enable **Format values** for automatic language/regional styles; **Customize…**
opens the native popover. It lists only custom rules, grouped by value type; **All languages** comes
first, followed by languages alphabetically. **Add rule** asks for a type,
language or All languages, and the desired result for a fixed source value.
The language picker offers only selected dictation languages; automatic detection
offers only All languages. Existing rules survive language deselection. Editing
such a rule requires choosing an available language before saving.
Dates show a named month so the source is unambiguous. Source values are bundled
with the app; configuring a rule never calls a model. Ten value types can be
individually enabled under **Value types**.

A language-specific rule overrides a general rule; general rules override
automatic language defaults. Unknown or mixed-language text uses the general
style. Explicit language rules also work with automatic detection. **Save rule**
returns to the popover draft; **Done** saves it and **Cancel** discards it.
**System defaults** clears custom rules and captures current regional preferences.
The final prompt stays hidden.

Rules and captured regional preferences synchronize through Google Drive in
version-2 post-processing settings. Version-1 fixed examples remain readable and
appear as All languages rules, preserving existing styles and enabled types.
Earlier arbitrary prompts retain the existing punctuation-only migration.
Both apps share the [instruction rules and fixed source values](Sources/OpenDictateCore/Resources/postprocessing-rules.json).

Value formatting adds only the [language examples](Sources/OpenDictateCore/Resources/postprocessing-languages.json)
for the speech languages captured at session start. Automatic detection adds no
language-specific examples. These reference phrases preserve omitted place-value
groups; your saved notation examples still control the output style. Both platforms
use the same bundled data, independent of OS spellout support or interface language.

The dedicated action uses Accurate once and applies post-processing before local
replacements. Ordinary Live/Accurate dictation and voice editing skip it.
If every rule is off, the dedicated action uses punctuation correction. Text and generated instructions go
directly to OpenAI Responses with `store: false`. Failure preserves the original
transcript; cancellation and focus guards still apply. Punctuation-only processing
rejects changes to words and numbers. Model-based value formatting preserves
ambiguous values and identifiers by instruction. A bounded Russian/English parser
rejects changed supported scaled integers; it is not a general validator for other
languages or numeric expressions.

The menu bar offers separate **Start dictation** and **Dictate with post-processing**
actions. Each has an independent binding in Settings → Keyboard shortcuts. The new
post-processing binding starts unassigned, preserving existing assignments on
upgrade. Either dictation shortcut finishes the current recording; Escape cancels.
The dedicated action does not change the saved main mode or synchronized rules.
Retranscription preserves the original recording's post-processing choice and
uses the current rules.

### Retranscribe the last recording

Choose **Retranscribe last recording** in the menu bar to recognize the latest
ordinary Live or Accurate recording again through Accurate, without recording
new audio. The command uses the current Accurate model, speech languages,
dictionary, timeout and word replacements. It preserves the original
post-processing choice while applying current rules. The selected
dictation mode stays unchanged. Results are inserted into the eligible field
focused when you invoke the command; switching fields during processing prevents
insertion. With no eligible field (including secure or excluded fields), the
result stays available through Copy/Paste last transcript and optional history.
Escape cancels processing and leaves the recording available for another try.

Only one recording, bounded by the existing eight-minute limit, is retained in
memory. It remains available after recognition succeeds or fails, until the next
ordinary dictation starts or the app quits. Cancelling a new recording discards
it; voice-edit instructions are never retained for retry and do not replace the
last ordinary recording. No audio is written to disk or synced. The command is
disabled while busy or when there is no recording to retry.

### Word replacements

Settings → Word replacements manages local “recognized phrase → replacement”
rules. Add, edit, delete, enable individual rules or turn all replacements off.
Matching ignores case unless the rule has “Match case” enabled, respects Unicode
word boundaries, prefers longer phrases and never cascades into another rule. Replacement spelling is preserved exactly.
Live applies completed words immediately; an unfinished trailing token waits for
a boundary or the final result. Accurate applies rules before delivery.
Each session captures its rules at startup, and history/copy use the same result.
Voice editing instructions and results are not modified. Up to 500 rules, with
256 UTF-16 units per phrase and 2,048 per replacement.

Enable “Regular expression” to save an ICU pattern instead of a phrase. Patterns
have no implicit word boundaries (use `\b`), keep their whitespace and must not
match empty text. Replacements may use `$1`, `${12}` and `$$`, or be
empty to delete the match. The earliest match wins, then the longer one, then
phrase rules. Android compiles the same patterns with its own regex engine; a
synced pattern it cannot compile is skipped there. Pattern rules sync as schema
version 2 and case-sensitive rules as version 3, which older app versions reject.
The list tags patterns with `.*` and case-sensitive rules with `Aa`.

Rules are applied locally and remain separate from dictionary hints sent to OpenAI.
Connect Google Drive in Settings to sync the rule list, individual rule states and
the master switch with Android.

### Appearance

The theme button beside the version at the bottom of the sidebar offers System, Light and Dark.
The choice is saved locally and applies across all settings pages. Dark preserves
the previous default; System follows macOS. The recording indicator remains dark.

Settings pages share `SettingsStyle.swift`: one spacing scale (4–32 pt), a
page title naming the page, and related rows grouped on raised cards with
secondary section titles. Inputs are inset on their card: darker than the card in
Light, lighter in Dark, with an accent focus ring. No field takes focus when
Settings opens. Settled statuses such as Allowed use a grey check; color is
reserved for states that need action. The sidebar shows dictionary and replacement
counts as secondary numbers and extends under the transparent title bar. The
Dictionary page has a single scroll: its editor fills the window, with **Clean up**
beside the title.

### Keyboard shortcuts

Command-W closes the current settings window. Command-Shift-W closes all regular
application windows while preserving the recording indicator. Reopen Settings
from the menu bar or with Command-comma while the app is active. OpenDictate
appears in the Dock and app switcher only while Settings is open, and returns to
the menu bar when the window closes.

Settings → Keyboard shortcuts groups global dictation and voice-editing bindings,
separate bindings for dictation with post-processing and meeting recording, and the
fixed Escape cancellation key in one compact list. The meeting binding starts
unassigned and toggles recording; Escape never cancels a meeting. Restore defaults follows the list; the
information button beside Voice editing explains its selection behavior.
Bindings are local to this Mac and do not sync through Drive.

Click either shortcut and press the desired combination. Letters,
symbols, navigation, numpad and F1–F20 keys work with any combination of Control,
Option, Shift and Command, or without modifiers. All three actions are
configured independently; their defaults remain Option+Space and Option+Shift+Space.
Existing preferences migrate automatically. Escape clears the shortcut; either or both can remain unassigned. Dictation and voice editing are still available from the menu;
use the adjacent key menu to turn off a shortcut or assign Escape, an F-key,
or Globe/Fn directly. Changes are unavailable during an active dictation.
During an active dictation, unmodified Escape always cancels even if assigned.
Conflicting registrations leave the previous shortcut working and show an error.

Press and release modifiers to assign a modifier-only shortcut, including
Globe/Fn. Modifier-only shortcuts fire on release; using the modifier with another
key does not start dictation. Globe/Fn with an ordinary key is also supported.
These monitored shortcuts do not suppress system/other-app actions. In macOS
Keyboard settings, set “Press 🌐 key to” to “Do Nothing” to avoid input-source,
emoji or system dictation actions. Accessibility must be allowed. Some external
keyboards handle Fn in firmware and never send its events to macOS.

For F1–F12, enable “Use F1, F2, etc. keys as standard function keys” under Keyboard
→ Keyboard Shortcuts → Function Keys, or hold Fn when pressing the key. The recorder
normalizes the function flag on F/navigation keys so they remain ordinary keys.
Keys reserved by macOS or intercepted by keyboard firmware cannot be guaranteed.
See [Apple's function-key guide](https://support.apple.com/102439) and
[AppKit event monitoring](https://developer.apple.com/library/archive/documentation/Cocoa/Conceptual/EventOverview/MonitoringEvents/MonitoringEvents.html).

Credentials live in one device-only, non-synchronizing Keychain item, with separate
payloads for the API key, Google Drive and ChatGPT. Legacy entries migrate on first
use: data is saved in the shared item before the old entry is retired. This first
transition may still prompt for each old entry. Later updates access only the shared
item, so Keychain can authorize it once rather than each provider separately.
macOS still controls authorization and keychain-unlock prompts. Preferences
use the app's UserDefaults domain. The last 500 completed transcripts are saved
in `~/Library/Application Support/OpenDictate/history.json` with owner-only
permissions. History places selectable text before compact processing details.
Each new entry captures its actual transcription model and, when post-processing
runs, its text model and service tier. Accurate includes separate request durations,
including upload and the full response; Live shows its model without timings.
Old entries and skipped stages have no invented models or durations. This metadata
remains local and is excluded from Drive.
Disable history at the top of the History page to stop saving future results;
**Delete all…** beside the search field removes existing history. Meeting transcripts
are stored separately (see Meetings). Audio is never persisted on macOS.

Errors use native macOS notifications without opening Settings or taking focus.
Allow notifications when macOS first asks; banners follow System Settings →
Notifications → OpenDictate. Denied notifications never trigger a fallback window.
Missing editable fields are silent. Other error details remain available in the
menu and in Settings when you open them yourself.

## Verification

Unit tests exercise UTF-16 selections, cumulative updates, cursor guards,
WAV/chunk boundaries, protocol payloads, ordered early-audio flush and commit,
bounded queues, cancellation and local search. Meeting tests cover segmentation,
time-ordered merging, echo removal on recognized simulation texts, Markdown, storage,
retries, the backlog pause, pauses, cancellation and a call channel held by the
permission prompt, with scripted recorders. Mock transport
tests do not require a key or call OpenAI. `MeetingCaptureViewsTests` renders every
meeting surface in both languages when `OPENDICTATE_VIEW_CAPTURES` is set.

For native smoke checks, run `scripts/build-smoke.sh` and
`scripts/build-editor-fixture.sh`, then launch the printed app paths. The smoke
bundle enables local-only checks automatically; other debug bundles accept
`--local-smoke-test`.
This debug-only mode uses the real shortcut, microphone session and Accessibility insertion with
synthetic text and provides a two-second microphone check in its menu. The recording/insertion check starts after five seconds and submits after three seconds so a disposable editor can retain focus. It makes
no OpenAI requests, never reads your API key or history, and writes no audio.
It uses a separate preference domain. These checks are absent from release builds.

For a network-free indicator preview, also pass `--hud-preview`. It shows the
production panel alone; clicking its finish button switches the preview to the
spinner. Debug menu actions can show either state in the selected indicator style.
Previewing never records audio. The explicit two-second microphone check also
feeds the production level meter so the waveform can be checked without OpenAI;
its captured audio is discarded in memory. The debug application menu also
offers “Local check: start recording in 3 seconds” for switching windows during
a production recording session; stop with the HUD to deliver synthetic text
into the active field.
After a local recording, “Local check: retranscribe last recording in 3 seconds”
exercises the production retry lifecycle and delivery with synthetic text, without
reading credentials or calling OpenAI. The delay lets the fixture regain focus.

To measure capture, run the debug binary directly:
`.build/debug/OpenDictate --capture-benchmark 10` runs cold, warm and cancel
cycles through the production recorder; `--capture-soak 60` records once while
you switch inputs or Bluetooth profiles. Output is JSON lines and a
p50/p90/max summary of engine start, first callback, first PCM, first chunk,
chunk gaps, coverage and configuration changes. It reports only timings, counts
and the input transport, never audio or device names.

Use a disposable editor document to check final clipboard insertion, cancellation before
delivery, cursor movement, switching fields/apps, secure fields and clipboard restoration.
Test permissions granted/denied, startup, menu actions, both languages, Spaces,
full-screen windows and logout/login. A real OpenAI key is required to validate
provider access and transcription accuracy; mock tests cannot establish either.

## Google Drive synchronization

Changes sync automatically after 15 seconds without edits; background checks run every 15 minutes.
**Sync now** (Command-R) bypasses the edit delay and retry backoff.
Edits made during a request remain queued for the next sync.
Opening Settings or waking this Mac checks only if the last successful sync is at
least five minutes old. Network recovery resumes queued work; errors back off and
authorization failures pause automatic attempts. Unchanged Drive replicas are
cached only in memory.

Turn on the native Google Drive switch in Settings, then choose a Google account
in your browser. Official builds supply the Desktop OAuth client configuration;
users do not enter client IDs or secrets. Sync includes the dictionary, word
replacements, post-processing, dictation mode and selected models, excluding API keys, audio and
history. Browser sign-in uses PKCE and a temporary loopback callback listener.
User refresh tokens stay in device-only Keychain storage.

Turning the switch off cancels a pending sign-in or sync, signs out only this Mac
and removes the user refresh token from Keychain. Application client configuration
is retained. Local and cloud settings remain, and other devices stay connected.
The next connection asks you to select a Google account.
See [setup and troubleshooting](../../docs/google-drive-sync.md).

For a custom build, download the Desktop client's JSON from the same Google Cloud
project as the Android client, then package with:

```bash
GOOGLE_DRIVE_OAUTH_CLIENT_FILE=/absolute/path/to/client.json apps/macos/scripts/package.sh
```

GitHub builds use the `MACOS_GOOGLE_DRIVE_OAUTH_CLIENT_JSON` repository secret.
The release workflow requires a valid installed-app client export. Keep the source
JSON outside Git; packaging includes only the application client ID and client
secret, never user tokens. Local builds without configuration can reuse a legacy
client already saved in Keychain; otherwise Google Drive remains unavailable.

## Packaging and releases

Stable releases are distributed through
[`lebedev-nikita/tap/open-dictate`](https://github.com/lebedev-nikita/homebrew-tap#opendictate).
After publishing a stable release, the release workflow updates the tap's pinned
version and SHA-256 using the tap's updater, which verifies the DMG against its
checksum attachment. The tap also checks every six hours as a fallback. Its
`livecheck` matches stable `macos-vX.Y.Z` tags, independently of the latest Android
release.
See the [installation commands](../../README.md#install-on-macos).

The Homebrew job uses `HOMEBREW_TAP_SSH_KEY`, a repository secret containing a
dedicated write-enabled deploy key for `lebedev-nikita/homebrew-tap`. Keep the
private key outside Git. To retry the update without republishing a release:

```sh
gh workflow run macos-homebrew.yml --ref main
```

Both platform icons use the approved [shared application artwork](../../docs/app-icon.md).
macOS packaging renders the original Android vector without adding lighting or shadows.

`scripts/package.sh` emits `dist/macos/OpenDictate.app`, a universal DMG, and a
SHA-256 checksum. It validates both CPU slices, the bundle signature and the DMG.
It also runs the packaged executable with `--verify-bundled-resources` to check
the installed resource paths without reading preferences or credentials, recording
audio or making network requests. Packaged apps load Core resources from
`Contents/Resources`; SwiftPM's build-directory lookup is used only outside app bundles.
After dragging OpenDictate to Applications, press Command + E in the disk image's
Finder window to eject it.
The image opens in icon view with one fixed row, left to right: **Applications →
Install OpenDictate.txt → OpenDictate.app**. Packaging requires Python 3
and installs pinned `ds_store` dependencies into an isolated `.build` environment
to generate Finder layout metadata. They are not included in the application.
The default uses an ad-hoc signature with a stable identifier requirement so
local Accessibility grants can survive rebuilds. This is not a Developer ID
signature or Apple notarization.

For Developer ID packaging, use an identity already present in the login
keychain, plus an existing `notarytool` keychain profile:

```bash
MACOS_SIGNING_IDENTITY='Developer ID Application: Your Name (TEAMID)' \
MACOS_NOTARY_PROFILE='opendictate-notary' apps/macos/scripts/package.sh
```

No certificate, private key or Apple credential belongs in the repository.
To release, update `VERSION`, `Resources/Info.plist` and `RELEASE_NOTES.md`, pass
platform checks, merge to main and push a matching `macos-vX.Y.Z` tag. Verify
the green release workflow, the DMG and checksum attachments before reporting
completion. macOS and Android versions are independent.

Release candidates use `X.Y.Z-rc.N` in `VERSION`, the displayed app version,
DMG filename and `macos-v*` tag. The Apple bundle short version contains only
`X.Y.Z`, per Apple's
[bundle version format](https://developer.apple.com/documentation/bundleresources/information-property-list/cfbundleshortversionstring);
increment the numeric bundle build for each distribution. Release CI
marks RC tags as GitHub prereleases and leaves the stable download unchanged.
`scripts/test-release-version.sh` verifies accepted/rejected version strings.

[Core Audio taps](https://developer.apple.com/documentation/coreaudio/capturing-system-audio-with-core-audio-taps)
(checked on 2026-11-16): taps need macOS 14.2 and `NSAudioCaptureUsageDescription`;
macOS asks the first time recording starts from an aggregate device with a tap.
Meetings reuse the file transcription request below unchanged.

Protocol references checked on 2026-09-30:
[Realtime transcription](https://developers.openai.com/api/docs/guides/realtime-transcription),
[file transcription](https://developers.openai.com/api/docs/guides/speech-to-text),
[Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs).
