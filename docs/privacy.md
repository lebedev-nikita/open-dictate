---
layout: default
title: OpenDictate Privacy Policy
---

# OpenDictate Privacy Policy

OpenDictate does not operate a backend and does not include analytics or ads.

Word replacement rules are stored locally on each device and applied on-device
after recognition. Rules are not uploaded as transcription hints. Connecting Google Drive also
synchronizes rules and their enabled states with your other devices.
Completed text saved to history or copied to the clipboard includes replacements.
An explicit AI history search may send that saved text to OpenAI.

## Punctuation

Both apps request punctuation as part of the original Live or Accurate
transcription. Android does not send recognized text for a separate punctuation pass.

On macOS, **Correct punctuation** appears only in Accurate mode and is off by
default. When enabled, Accurate sends only the recognized transcript directly
to OpenAI's `gpt-6-luna` through the Responses API with `store: false`, before local word
replacements. It adds a separate processing step; failures keep the original
transcript. Live and voice editing do not use this step. The switch is saved
only on the current device and is excluded from Google Drive sync; the transcript is never included in sync.

## Optional Google Drive synchronization

Replica versions and validated settings are cached only in process memory to avoid
re-downloading unchanged cloud files. The cache adds no persistent storage and
contains no API keys, audio or transcript history. Android uses the normal
`ACCESS_NETWORK_STATE` permission only to defer sync offline and resume when
connectivity returns; macOS monitors network availability for the same purpose.


If you connect Google Drive in Settings, the dictionary, word replacement rules and
enabled states, dictation mode and selected model IDs are sent directly to
Google and stored in your account's hidden app-data folder. Sync requests only `drive.appdata`, without access to
ordinary Drive files. API keys, audio, transcripts/history, exclusions and
permissions are excluded; other preferences remain local.

When connecting, the app reads cloud settings first. If synced values differ,
you choose cloud or local settings before either source changes. Cancel leaves
settings unchanged. A pending choice blocks automatic sync even after a restart.

Android uses Google Play services authorization and token caching. macOS stores
its OAuth client secret and refresh token in device-only, non-synchronizing
Keychain storage. The client ID stays in preferences. macOS sign-in opens your
browser and briefly listens on loopback for the OAuth response. That listener
closes on success, failure, cancellation or timeout. No tokens, dictionary contents or replacement rules are logged.

Disconnect stops sync and removes macOS OAuth credentials; local and cloud
settings remain. An upload already accepted by Google may finish. Delete hidden
data through Google Drive's Manage apps settings and revoke access through your
Google Account. See [setup and conflict behavior](https://github.com/lebedev-nikita/open-dictate/blob/main/docs/google-drive-sync.md).

## macOS

- Error notifications contain only built-in status messages, never audio,
  transcripts, focused-field contents, credentials or raw provider responses.
  Notification Center retention and previews follow your macOS settings.

- Your OpenAI API key is stored in a non-synchronizing, device-only Keychain
  item, available while your login keychain is unlocked. It is never saved in
  preferences, the app bundle, logs or release artifacts.
- The API key copy button places the entered key, or the saved Keychain key
  when the field is empty, on the system clipboard only when you click it.
  The clipboard item is marked confidential for compatible clipboard utilities.
  Other software on the Mac can still access clipboard data.
- Dictation sends microphone audio directly to OpenAI. Live streams 24 kHz
  PCM in memory; Accurate uploads a WAV constructed in memory. Audio is
  never written to disk. The latest ordinary dictation recording (up to eight
  minutes) stays only in memory after completion or failure so you can explicitly
  retranscribe it through Accurate. Starting a new ordinary dictation or quitting
  clears it; cancelled recordings and voice-edit instructions are not retained
  for retry. Cancelling a retranscription keeps the existing recording available.
  Retranscribing sends that audio directly to OpenAI again using current settings.
- Microphone access is used only for a session you start. Accessibility reads
  the focused editable control and its selection, and inserts transcription
  there. Secure text fields are excluded; unrelated screen contents are not collected.
- Ordinary global shortcuts use registered hotkeys. When you choose Globe/Fn
  or a modifier-only shortcut, AppKit monitors key codes and modifier flags
  using the existing Accessibility permission. These monitors never read typed
  characters, log keystrokes or require Input Monitoring permission. Recording
  a shortcut reads its label only inside the focused settings control and ends
  when focus changes. Ordinary dictation continues during typing and switching
  windows. Stopping captures the active eligible field and inserts at its latest selection. Changing
  fields after stopping prevents automatic insertion; voice editing requires
  the original source to remain unchanged.
  A completed result remains available
  to copy explicitly from the menu.
- All macOS editors receive a final clipboard paste. Only the transcript is
  placed on the clipboard, marked confidential for compatible clipboard utilities;
  the previous clipboard contents are
  restored after insertion if no other app or user action changed them.
  Explicit copy keeps the transcript on the system clipboard. Other software
  on the Mac can access clipboard data.
- Voice editing sends only the selected text (or the whole focused field when
  nothing is selected) and your spoken instruction to OpenAI. Text Responses
  requests set `store: false`.
- The last 500 completed transcripts are stored locally in
  `~/Library/Application Support/OpenDictate/history.json`. You can disable
  saving future history and delete existing items or all history separately.
  The directory and file are owner-only. macOS backup software may include
  this folder according to your own backup configuration.
- Ordinary history search runs on-device. Only explicit AI search sends its
  query and saved transcript text directly to OpenAI. Search results are
  held in memory, and Responses requests set `store: false`.
- Dictionary terms, excluded app bundle identifiers and preferences are saved in
  UserDefaults locally. With optional Drive sync, dictionary, replacement and model settings
  also go to Google as described above. Dictionary terms are sent to OpenAI as transcription
  hints only when you start a session. Exclusions are checked before capture.
- Launch at login is optional and uses macOS Login Items. OpenDictate does
  not install a background daemon or a network service.

## Android

- Your OpenAI API key is encrypted locally with Android Keystore.
- When the settings screen opens, OpenDictate may use that key to fetch the
  available model IDs directly from OpenAI. It caches only model IDs locally
  and checks again after 24 hours when the settings screen opens. You can also
  refresh the list manually.
- Microphone audio is sent directly to OpenAI during dictation or when you
  explicitly retry the latest recording.
- The latest dictation is kept as one WAV file in app-private storage, even if
  transcription fails. Starting another dictation replaces it. The floating
  menu's Last dictation action sends that audio to OpenAI again. Text
  transformation recordings remain temporary and are deleted after use.
- Successful dictation transcripts are stored in the application's private
  local database so you can review and delete them. They are not included in
  Android backups.
- Fuzzy history search runs entirely on the device. AI history search runs only
  when you explicitly tap its button and sends the search query and transcript
  text directly to OpenAI. OpenDictate sets API response storage to off for these
  requests and does not persist the response.
- If the original text field is no longer available when processing finishes,
  OpenDictate copies the completed result to the Android system clipboard so it
  is not lost. Clipboard access and retention are controlled by Android and
  other software on the device.
- You can explicitly copy a saved transcript from the history screen. The
  floating menu's Paste last action inserts into the focused field without
  changing the clipboard.
- Accessibility is used to find the focused editable control, detect the input
  method window, and update its text. When you explicitly start a text
  transformation, the selected text—or the focused field's text when there is
  no selection—and your spoken instruction are sent directly to OpenAI. Other
  screen contents are not collected, persisted, or transmitted.
- The app picker reads names and icons of launchable apps installed on the
   device. The app filter mode and separate selections of allowed and excluded
   apps are stored locally as package names. They are not sent to OpenAI or
   included in Google Drive sync.
- A transformation may return a short answer or error as a private Android
  notification. OpenDictate does not persist that message; Android notification
  history is controlled by the device's system settings.
- Android backup is disabled for the application.
- When you choose Add to OpenDictate dictionary from Android's text selection
  menu, the floating button's long-press menu, or Android's Share menu, only
  the selected or shared text is saved in app-private preferences. The
  dictionary is sent directly to OpenAI as transcription context during later
  dictations.

OpenAI processes API requests under its API data usage policies. Review those
policies before using the application with sensitive information.
