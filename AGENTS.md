# OpenDictate agent guide

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

There is no backend. Audio goes directly from the device to OpenAI. The user
supplies the API key, secured with Android Keystore or macOS Keychain. Preserve this
privacy boundary: never log keys, audio, transcripts, focused-field contents,
or raw OpenAI payloads; never add analytics or persistent audio storage without
an explicit product decision.

On macOS, the app lives in the menu bar. A global shortcut toggles dictation;
Shift plus the same shortcut starts voice editing. A nonactivating status HUD
never takes keyboard focus. Secure fields and excluded apps are rejected.

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
  audio capture and session ownership.
- `apps/macos/Sources/OpenDictateCore/`: pure text, WAV and OpenAI protocol logic.
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
`apps/macos/scripts/package.sh`. Exercise hotkeys, secure fields, cursor edits,
focus changes, cancellation, microphone permissions and insertion on macOS.
Mock tests cannot verify TCC, Accessibility or actual OpenAI model access.

For UI changes, inspect the result yourself in the running app in both Russian
and English, capture and review screenshots of the affected screens and states
in each language, and compare them with the rest of the application's design.
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
Verify `draft: true` after the release workflow finishes.

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
