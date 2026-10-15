# OpenDictate

Dictate into the app you're already using, on **Android and macOS**.
This monorepo contains two native applications: an Android app with a floating
keyboard overlay and a macOS menu bar app with global keyboard shortcuts.
Both insert transcriptions into the currently focused text field.

OpenDictate sends audio directly to OpenAI using your own API key. There is
no backend, analytics or account to create.

[Install on Android](#install-on-android) · [Install on macOS](#install-on-macos) ·
[Build from source](#repository-and-builds)

| | Android | macOS |
|---|---|---|
| Start / finish | Tap the keyboard overlay | **Option + Space** |
| Voice editing | Long-press → Edit text | **Option + Shift + Space** |
| Installation | Signed APK | Universal DMG, Apple Silicon + Intel |
| Requires | Android 8+ | macOS 14+ |
| Downloads | [Android releases](https://github.com/OpenDictate/open-dictate/releases/latest) | [macOS releases](https://github.com/OpenDictate/open-dictate/releases?q=macos-v) |

## Features

- **Live** (`gpt-live-transcribe`) inserts text as you speak.
- **Accurate** (`gpt-transcribe`) transcribes a completed recording.
- Voice-directed editing of the selection, or the whole field without a selection,
  using GPT-6 Luna or GPT-6 Sol.
- A transcription dictionary, local history, on-device search, and explicit AI search.
- English/Russian settings, automatic speech language detection and language hints.
- Focus guards prevent delayed text from being inserted in another field or app.
- Encrypted credentials: Android Keystore on Android, Keychain on macOS.
- Optional Google Drive sync for the dictionary, word replacements, dictation
  mode and selected models. [Setup and conflict behavior](docs/google-drive-sync.md).

## Install on macOS

Install the latest stable macOS release with Homebrew:

```bash
brew install --cask lebedev-nikita/tap/open-dictate
```

Quit OpenDictate before updating:

```bash
brew update
brew upgrade --cask lebedev-nikita/tap/open-dictate
```

The [tap](https://github.com/lebedev-nikita/homebrew-tap#opendictate-maintenance)
checks stable macOS releases every six hours and verifies the DMG checksum before
updating the cask. Android releases and release candidates are excluded.
For an identical manually installed release, quit the app and use
`brew install --cask --adopt lebedev-nikita/tap/open-dictate`.
Settings, history and Keychain credentials are retained.

To install from a DMG instead:

1. Choose a release from [macOS Releases](https://github.com/OpenDictate/open-dictate/releases?q=macos-v)
   and download its universal DMG for Apple Silicon and Intel. Releases marked
   **Pre-release** are release candidates.
2. Open it, drag **OpenDictate** into **Applications**, and eject the disk image.
3. Launch OpenDictate. GitHub release builds are locally signed and **not notarized**.
   If macOS blocks launching it, choose **System Settings → Privacy & Security →
   Open Anyway** for OpenDictate, then launch it again.
4. Save your own OpenAI API key and allow **Microphone** and **Accessibility**
   with the setup buttons. Reopen the app if macOS requests it.
5. Focus a text field in another app. Press **Option + Space** to start and again
   to finish. **Escape** cancels. Add **Shift** to edit text with a spoken instruction.

OpenDictate lives in the menu bar. Its menu provides Settings, mode selection,
copy/paste of the latest transcript and adding selected text to the dictionary.
Dictation and voice-editing shortcuts are configurable independently, including
Globe/Fn and modifier-only taps. Excluded apps and launch at login are configurable.
The compact recording indicator stays out of keyboard focus; its red finish
button submits the recording, and a spinner shows processing.

macOS recordings stay in memory and are discarded after use; there is no
recording archive or audio retry. Completed transcripts remain available in
local history, which can be disabled or deleted. Editors without writable
Accessibility text use a final clipboard paste. Previous clipboard contents
are restored if unchanged. Secure fields are excluded.

API billing is separate from a ChatGPT subscription. A model must be available
to your OpenAI API project. See [macOS documentation](apps/macos/README.md).

## Install on Android

1. Download and install the latest signed APK from [Android Releases](https://github.com/OpenDictate/open-dictate/releases/latest).
2. Save your OpenAI API key, grant microphone access and enable the OpenDictate
   service in the system accessibility settings.
3. Open a text field and tap the overlay to dictate. Long-press for voice editing,
   the dictionary, last recording retry and paste-last actions.

The overlay appears while the keyboard and an editable field are present.
In Settings → Keyboard → Apps, choose all apps except selected ones or only
selected apps. Each mode remembers its own selection; an empty selection in
Only selected apps hides the button everywhere.
Swipe right to dismiss it until the keyboard closes. Add dictionary words from
Android's text selection or Share menu. Android retains one private recording
for retry; the next dictation replaces it.

Preview APKs install separately as `com.opendictate.app.preview`. Disable the
stable accessibility service before enabling the preview service.

Use the theme button at the top right of Settings on either platform to choose
System, Light or Dark appearance. The choice is saved locally. Android follows
the system by default; macOS keeps its previous dark default.

## Repository and builds

```text
apps/android/   Kotlin/Jetpack Compose application, Gradle wrapper and tests
apps/macos/     SwiftUI/AppKit menu bar application, Swift package and tests
docs/          Public website
.github/       Platform verification and release workflows
```

### Android

Android requires JDK 17+ and Android SDK 37. The root wrapper forwards to
`apps/android` so existing commands keep working:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
./gradlew lintRelease assembleRelease
```

Run the dictation settings and native Accessibility delivery checks on a fresh
emulator debug installation (without an API key):

```bash
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.opendictate.app.ui.DictationSettingsTest
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.opendictate.app.service.DictationDeliveryTest
```

These checks verify settings after upgrading, text insertion, processing
animation and cancellation using synthetic provider results, and make no
OpenAI requests. The editor fixture is included only in debug builds.

APKs are under `apps/android/app/build/outputs/apk/`.
Release signing requires the environment variables listed below.

### macOS

Build on macOS 14+ with Xcode 16+:

```bash
swift test --package-path apps/macos
apps/macos/scripts/package.sh
open dist/macos/OpenDictate.app
```

The packaging script builds both architectures, creates and verifies a signed
`.app`, and packages a DMG and SHA-256 checksum in `dist/macos/`.
Build output is ignored by Git. No third-party Swift dependencies are needed.
See the [macOS developer guide](apps/macos/README.md) for architecture,
permission testing, native smoke checks, shortcut configuration and packaging.

## Releases

- Android: `v*` tags publish signed APKs through `release.yml`. Signing uses
  `SIGNING_KEY`, `KEY_ALIAS`, `KEY_PASSWORD` and `STORE_PASSWORD` Actions secrets.
  Preview builds retain the weekly/manual `preview.yml` workflow.
- macOS: `macos-v*` tags publish universal DMGs and checksums through
  `macos-release.yml`. The version must match `apps/macos/VERSION`.
  macOS releases do not replace the latest Android download.
- Local macOS packaging also supports Developer ID signing and notarization
  when an identity and notary profile are supplied; see the platform README.

## Privacy and security

The only external service is OpenAI. Voice editing sends only the selected
text (or whole focused field) and the recorded instruction. AI history search
sends history only when explicitly requested. Unrelated screen contents are
not collected. See [PRIVACY.md](PRIVACY.md) and [SECURITY.md](SECURITY.md).

## License

MIT. See [third-party notices](THIRD_PARTY_NOTICES.md).

## Word replacements

Both Android and macOS support local word and phrase replacements in Settings.
Add a recognized phrase and its preferred spelling, edit or delete it, disable
individual rules, or turn replacements off. Live and Accurate dictation apply
the same case-insensitive, whole-word rules; longer phrases win and replacements
do not cascade. Changes take effect with the next recording. Android retry also
applies the current rules. Voice editing is unchanged. Rules are separate from
the recognition dictionary and are not sent to OpenAI. Connecting Google Drive
syncs the rule list, each rule’s enabled state and the master switch across devices.
