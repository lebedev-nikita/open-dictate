# OpenDictate agent guide

Always reply to the user in Russian unless they ask for another language.

## Product contract

OpenDictate is an Android/macOS monorepo. Both apps insert OpenAI speech
transcriptions into the currently focused text field. On Android, an accessibility overlay
appears only while an IME window and an editable node are both present. A tap
starts dictation; a second tap stops it.

The user chooses between two paths:

- `LIVE` uses `gpt-live-transcribe` over a Realtime WebSocket and applies
  partial transcripts while the user speaks.
- `ACCURATE` records a temporary WAV file and sends it to
  `/v1/audio/transcriptions` with `gpt-transcribe` after recording stops.
- `ON_DEVICE` (Android, offered only where AICore has Gemini Nano) streams the
  same PCM to ML Kit speech recognition and applies partial text like `LIVE`.
  It needs no key, and the choice stays on the device, outside Drive sync.

Gemini Nano can also be the Android text model, again a device-local choice.
AICore answers text only while an OpenDictate window is in front
(`BACKGROUND_USE_BLOCKED` otherwise), so `TextRequests` falls back to the
OpenAI text model over other apps. Never bring a window forward to avoid this.
ML Kit's usage statistics are dropped by removing the datatransport backend
from the manifest; keep it removed, and check that it still is after ML Kit updates.

There is no backend. Audio goes directly from the device to OpenAI. The user
supplies the API key, secured with Android Keystore or macOS Keychain. Preserve this
privacy boundary: never log keys, audio, transcripts, focused-field contents,
or raw OpenAI payloads; never add analytics or persistent audio storage without
an explicit product decision.

On macOS, the app lives in the menu bar. A global shortcut toggles dictation;
Shift plus the same shortcut starts voice editing. A nonactivating status HUD
never takes keyboard focus. Secure fields and excluded apps are rejected.

macOS also records meetings into local transcripts for the user to hand to other
tools; it captures and transcribes, without summaries. Roles come from channels:
the microphone is "Me", a Core Audio process tap of the chosen call app (or all
system audio) is "Other speaker". Other participants' speech goes to OpenAI, so
keep the one-time consent notice and the System Audio Recording permission flow.
Meeting audio never touches disk; transcripts stay local, outside Drive.

## Android runtime flow and ownership

1. `OpenDictateAccessibilityService` detects the keyboard and focused editable
   node, owns `DictationOverlayView`, snapshots the original text and selection,
   and inserts transcript updates with accessibility actions.
2. `DictationForegroundService` owns one recording session, microphone
   lifecycle, notification, model selection, cleanup, and OpenAI client calls.
3. `DictationStateBus` is the in-process handoff between those services. Session
   IDs prevent stale results from an earlier dictation from reaching a newer
   target.
4. `OpenAiTranscriptionClient` implements the file and Realtime protocols.
5. `PcmAudioRecorder` emits 24 kHz, mono, PCM16 chunks every 40 ms. `WavFile`
   adds the container only for the accurate path.

Phone buttons are bound per action: `ButtonBindings` gives dictation, the
alternate dictation, voice editing and the AI agent command each at most one
button and gesture (press, hold or double press), and a button's gesture runs one
action. Buttons are keys Android reports or buttons that Android withholds from
apps, such as the OnePlus Plus Key: with adb-granted `READ_LOGS`, `LogButton`
runs one logcat filtered to the bound lines' tags and reports presses and, where
logged, releases. `ButtonGestureRecognizer` waits only for gestures bound on the
button; the accessibility service runs the actions, and the agent command runs in
`CommandSession`, shown by an overlay indicator at the top of the screen and
ended on release after a hold. Keep the log out of the app otherwise: no reader
without a bound button, and no unfiltered reader outside binding.

`AssistantCommands` carries out a spoken command: one text-model request with the
enabled tools of the user's MCP servers (`AssistantCommand`), then one tool call
through `McpClient` (MCP Kotlin SDK over Streamable HTTP), reported as a
notification. Only an error answer returns to the model, once, to correct the call. `McpSignIn` signs in with AppAuth and a loopback redirect.
Servers and tool lists live in `SettingsStore`, tokens in `McpSecrets`; never log
commands, tool arguments, tool answers or tokens.

Partial live text is cumulative. `EditableTextSnapshot.compose()` always
replaces the selection captured at session start, so every delta replaces the
previous partial result instead of appending it. Keep the target-package check
before `ACTION_SET_TEXT`; text must not be inserted after focus moves to another
app. Hiding the keyboard cancels an active session and removes the overlay
without committing audio for transcription.

## Latency invariants

Treat the live path as a tight loop:

- Keep microphone chunks small and stream them without disk I/O.
- Open the WebSocket immediately. Audio produced before `session.updated` is
  held in the bounded in-memory queue and flushed as soon as configuration is
  acknowledged.
- Keep Realtime turn detection disabled; the foreground service explicitly
  commits when the user stops.
- Apply partial transcripts immediately through `DictationStateBus`.
- Keep blocking audio and network work off the main thread.

Measure before changing chunk size, queue bounds, timeouts, or overlay debounce.
Optimizing throughput at the cost of first-text latency is a regression for this
product.

## Project map

- `apps/android/app/src/main/java/com/opendictate/app/service/`: accessibility integration,
  foreground dictation lifecycle, text composition, and session state.
- `apps/android/app/src/main/java/com/opendictate/app/network/`: OpenAI HTTP/WebSocket client.
- `apps/android/app/src/main/java/com/opendictate/app/ondevice/`: Gemini Nano through ML Kit
  GenAI and AICore; pure rules live in `model/OnDeviceModels.kt` and `model/OnDeviceText.kt`.
- `apps/android/app/src/main/java/com/opendictate/app/audio/`: PCM capture and WAV encoding.
- `apps/android/app/src/main/java/com/opendictate/app/data/`: encrypted credentials and local
  preferences.
- `apps/android/app/src/main/java/com/opendictate/app/ui/`: Compose setup screen and state.
- `apps/android/app/src/test/`: JVM tests for pure text and WAV logic.
- `.github/workflows/build.yml`: main/PR verification and debug APK artifact.
- `.github/workflows/release.yml`: signed APK publication for `v*` tags.

The build uses AGP 9 built-in Kotlin. Keep the Compose compiler plugin, but do
not re-add `org.jetbrains.kotlin.android`. `compileSdk` is 37 while `targetSdk`
remains 36; changing the target opts the app into new runtime behavior and must
be reviewed separately.

- `apps/macos/Sources/OpenDictate/`: native UI, hotkeys, Keychain, Accessibility,
  audio capture (microphone and process taps) and session ownership.
- `apps/macos/Sources/OpenDictateCore/`: pure text, WAV, meeting segmentation,
  transcript, echo and Markdown logic, and OpenAI protocol logic.
- `apps/macos/Tests/`: pure logic and mock transport tests.
- `apps/macos/scripts/package.sh`: universal app, signature and DMG verification.
- `.github/workflows/macos-build.yml` and `macos-release.yml`: macOS verification
  and publication on `macos-v*` tags.

## macOS runtime

`AppModel` owns one session UUID and immutable settings; `AudioRecorder` owns
a serial audio worker; `LiveTranscriptionSession` serializes socket sends.
`TextTarget` rechecks process, exact focused element, value and UTF-16 selection
before final clipboard insertion. Both modes deliver once after recording stops.
Cancellation restores only an unchanged target. Ordinary dictation continues
across focus changes and captures its delivery field when stopped. Focus changes
after stopping detach delivery; voice editing remains bound to the original
source and stops when it changes. Final results remain available to copy.
The status HUD never activates. Use registered hotkeys, without Input Monitoring.

macOS audio remains in memory for both paths. One bounded ordinary dictation
recording remains available for explicit Accurate retranscription after success
or failure, until the next ordinary dictation starts or the app quits. Cancelling
a new dictation discards its audio; cancelling a retry preserves the retained
recording. Voice-edit audio is not retained for retry. Retry captures delivery
at invocation and uses the same focus guards, cancellation and final-result path.
Keep audio and request encoding off the main actor. History is an existing
product capability, stored locally with explicit deletion and an off switch.

`MeetingController` owns meetings beside `AppModel`, never inside its dictation
session; dictation and voice editing keep their own microphone recorder during a
meeting, and their speech stays out of the transcript. Each channel is an
`AudioRecorder` without the dictation size limit (`ProcessTapCaptureEngine` for call
audio), cut at measured pauses into utterances stamped on one meeting clock, sent
one per `/v1/audio/transcriptions` request with the Accurate settings captured at
start, and merged by time. Recognized audio is released; untranscribed audio is
bounded and pauses capture visibly when full; failures retry one utterance. Echo
removal compares recognized text only. The tap's start can wait on the permission
prompt, so nothing waits on the call channel. Pure logic lives in
`OpenDictateCore` with behavior tests; `MeetingStore` keeps owner-only JSON per meeting.
Read `apps/macos/README.md` for protocol references, native smoke checks and
packaging before changing capture, delivery, permissions or release behavior.

## Local API access

For agent-run API calls and evaluation scripts, load credentials from the ignored
root `.env` file. Text processing uses `OPENAI_API_KEY` and `OPENAI_BASE_URL`
(`http://127.0.0.1:8317/v1`). Transcription uses only `TRANSCRIBE_KEY` with
`https://api.openai.com/v1`, regardless of `OPENAI_BASE_URL`. Keep the two
credentials separate; a missing credential must fail rather than use the other.
These settings apply to development tools, not the apps' stored credentials or
production endpoints. Keep secret values out of documentation and command output.

## Change workflow

Before editing, trace the affected path end to end. Changes to transcription or
Accessibility usually cross the overlay, session state, foreground service,
and client rather than belonging to one class in isolation. Verify current
OpenAI protocol fields against official OpenAI documentation whenever changing
model names, endpoints, Realtime events, or audio configuration.

Run the smallest relevant tests while iterating, then finish with:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
./gradlew lintRelease assembleRelease
```

For macOS changes, also run `swift test --package-path apps/macos` and
`apps/macos/scripts/package.sh`. Keep the developer's screen free while they
work: launch the app or a smoke bundle only when the developer asks, and report
the live checks you did not run. Those checks cover hotkeys, secure fields,
cursor edits, focus changes, cancellation, microphone permissions and insertion;
for meetings, also System Audio Recording, a real call app, and echo with
speakers. Mock tests cannot verify TCC, Accessibility, process taps or actual
OpenAI model access.

For UI changes, render the affected screens and states offscreen in both Russian
and English: set `OPENDICTATE_VIEW_CAPTURES` to an output directory and run the
capture tests (such as `SettingsCaptureTests` and `MeetingCaptureViewsTests`),
adding any state they lack. `TestWindowHiding` keeps test windows transparent.
Review the screenshots and compare them with the rest of the application's design.
Verify that every label and message is correctly translated and fits the app's
context, the control's purpose, and its placement. Keep UI copy concise,
especially helper text, and check that it fits without clipping, truncation,
or awkward wrapping. Fix translation, visual, and interaction inconsistencies
before handing the changes to a human.

The work is complete when required commands pass, no generated build output is
committed, and behavior-specific coverage exists for pure logic. Exercise
overlay visibility, focus changes, cancellation, and transcript insertion on a
real device or emulator for Accessibility changes; JVM tests cannot validate
those system interactions.

## Releases and secrets

Create every Android and macOS release candidate as a draft GitHub release.
Publish stable releases, including patch releases, as non-draft GitHub releases.
After the release workflow finishes, verify `draft: true` for release candidates
and `draft: false` for stable releases.

Release signing is configured only when `SIGNING_KEY`, `KEY_ALIAS`,
`KEY_PASSWORD`, and `STORE_PASSWORD` are present. `SIGNING_KEY` is a base64 JKS.
Never commit a keystore, decoded signing material, API key, or local properties.
Tags matching `v*` trigger the release workflow; confirm its green run and the
signed APK attachment before declaring a release complete.

macOS releases use independent versions and `macos-v*` tags. Verify the green
release workflow, universal DMG and checksum attachment before completion.
Ad-hoc releases must disclose that they are not notarized; keep Developer ID
and notary credentials outside Git. The root Gradle wrappers forward to
`apps/android`, preserving existing Android build commands.

Android temporary WAV files must remain cache-only and be deleted in `finally`. Keep
`android:allowBackup="false"`, cleartext traffic disabled, and accessibility
scope limited to keyboard detection and focused-field insertion. Review
`PRIVACY.md` and `SECURITY.md` whenever data handling, permissions, networking,
or storage changes.
