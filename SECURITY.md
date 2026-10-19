# Security

Please report vulnerabilities privately through GitHub Security Advisories.
Do not include API keys, recordings, or other sensitive data in public issues.

Word replacements use literal, case-insensitive matches with Unicode word
boundaries. Rules are compiled once per dictation, operate only on recognized
text, and never alter the captured original field or voice-edit instructions.
Rules are applied on-device. Optional Drive sync uses the same app-data scope
for the rule list and enabled states.

Optional post-processing sends recognized dictation text and the configured
instruction directly to OpenAI through Responses with storage disabled. It is
available after ordinary Live, Accurate and retranscription on both platforms;
voice-edit commands skip it. Settings are captured at session start. Disabled
post-processing or an empty prompt makes no additional request. Failures retain
the original transcript and display a curated warning; cancellation still prevents
delivery. The built-in punctuation-only preset rejects changes to words, numbers
and address contents. Custom instructions, including number formatting, can
rewrite text and are not constrained by that punctuation-only validator.

## Optional Google Drive synchronization

Validated replicas and Drive versions are cached only in process memory, without
persistent storage or credentials, audio or history. Android's normal
`ACCESS_NETWORK_STATE` permission and macOS path monitoring are used only to defer
sync offline and resume queued work when connectivity returns.

Sync uses Google's HTTPS endpoints without a backend, requests only
`drive.appdata` and exports an allowlist of dictionary, replacement, mode and
model settings, custom transcription prompts and explicit resets, and post-processing
configuration (enabled, model and optional prompt override). Legacy Accurate punctuation sync entries are discarded.
API keys, audio and history are excluded. Tokens, custom prompts, dictionary/replacement contents and raw
provider payloads are never logged. Drive clients disable redirects, cookies and
persistent caches. Remote data is bounded and validated before application;
unknown keys survive, while unsupported versions stop sync.

Android uses Google Identity Services and Play services' token cache; Google
tokens are not stored in preferences. macOS uses the system browser, PKCE S256,
random OAuth state and a temporary IPv4 loopback listener. Builds include the
Desktop application's client configuration, supplied through the macOS build
secret; it is not a user credential or a confidential server secret. User refresh
tokens and legacy client configuration use device-only, non-synchronizing Keychain
storage. Failed authorization does not replace credentials. OAuth errors are mapped to known
categories without displaying provider descriptions or payloads.
Access tokens stay in memory. Disconnect cancels the local sync task and removes
macOS user refresh tokens while retaining application client configuration, without
deleting cloud data or revoking Google's grant. Empty signed-out tokens are rejected
before any refresh request. Release publication requires valid build configuration.
Android requests `SELECT_ACCOUNT` for manual connections; background token
requests remain silent. macOS browser connections use `select_account`. Signing
out on one installation does not sign out other devices.

Connection source selection blocks application and upload until differences are
resolved explicitly. Pending connections persist across restart and network
failure; choices are checked against a fresh download before application.
Per-device replicas and per-field merge preserve concurrent writes. Incoming
settings do not change an active dictation's captured settings. See
[Google Cloud setup and verification](docs/google-drive-sync.md).

## macOS

The macOS app uses device-only Keychain credentials, ephemeral URLSession
connections to OpenAI over HTTPS/WSS, bounded in-memory audio and no audio
files. It never logs keys, audio, transcripts, field contents or raw provider
responses. Provider errors are mapped to safe status messages rather than
displayed verbatim. Text Responses requests disable storage.

Native macOS error notifications use only curated, localized status messages;
unknown errors receive a generic notification. They never include raw system or
provider descriptions, credentials, transcripts, audio or focused-field contents.
Notification delivery failures do not open windows or generate further errors.

Accessibility insertion checks the captured process and exact element, current
text, selection and secure-field subrole before every write. UUID session guards
discard stale callbacks. Ordinary dictation continues across focus changes and
captures an eligible active field at stop, rechecking Accessibility, secure fields and the session
exclusion list. Delivery captures its latest text and selection, preserving user
edits. Voice editing retains the original source guard. Text/selection changes during clipboard
staging reject delivery. Final clipboard delivery is directed at the captured
process (the original process for voice editing) and rechecks the field before
posting Paste. No AX text writes or app-specific
exceptions are used. Each paste is posted once and verified by text/caret readback.
Clipboard restoration is skipped
if another action changed the clipboard. Cancellation before final delivery leaves the original field unchanged. Secure fields and user-excluded apps are rejected before capture.

Ordinary shortcuts use exclusive Carbon registrations. Only configured Globe/Fn
or modifier-only shortcuts enable AppKit event monitors with Accessibility access;
they inspect key codes and modifier flags, never event characters. Monitor state
is in memory and removed on reconfiguration, shortcut recording or shutdown.
The temporary local shortcut recorder reads a key label only while focused.
No Input Monitoring permission is requested. Monitored shortcuts cannot suppress
other applications' or macOS's own keyboard actions.

Local transcript history has owner-only directory/file permissions and can be
disabled or deleted. macOS backup software is controlled by the user. macOS keeps
one bounded retry recording only in memory, never in a file or backup. Starting
a new ordinary dictation or quitting releases it. Cancellation discards a new
recording; cancelling a retry preserves the previous recording. Voice-edit audio
is not retained for retry. Only an explicit menu action resends it to OpenAI via
the existing Accurate upload path. Retry captures and validates an eligible field
at invocation and cannot deliver after focus changes; without an eligible field,
the result remains available for explicit copying. The app is not sandboxed because it
uses system-wide Accessibility insertion; its entitlements allow microphone input,
with macOS privacy permissions still required. It has no privileged helper.

Universal DMGs are built in GitHub Actions from `macos-v*` tags, with SHA-256
checksums. The initial release uses ad-hoc signing, not Developer ID signing or
Apple notarization; installation instructions disclose the Gatekeeper step.
Developer ID identities and notary profiles are supported for local packaging
but their certificates, private keys and credentials must stay outside Git.
Debug-only local smoke checks do not call OpenAI and are absent from release builds.

## Android

OpenDictate intentionally never logs authorization headers, API keys, audio,
transcript contents, transformation instructions, or focused-field text.
Model discovery calls OpenAI directly and stores only model IDs in app-private
preferences. The model list API does not supply endpoint capabilities or prices,
so the picker keeps the transcription aliases and up to five recent general GPT
text models, excluding Astra and specialized models.
The app picker queries launchable apps only. The filter mode and separate allowed
and excluded package lists stay in app-private preferences, outside Drive sync.
The accessibility service checks the filter before showing the overlay, starting
an action or inserting a result. Only-selected mode rejects unlisted and unknown
packages, including system interfaces. Cancellation can still restore the original
field after its app is disabled, using the existing target and restoration guards.
Release signing material is expected to live only in GitHub Actions secrets.
The text selection and floating-menu actions store only selected text that the
user explicitly adds to the dictionary. The Share action stores only the shared
text. These actions do not log the text or read surrounding field contents.

Transcript history is stored only in the app-private SQLite database. Android
backup remains disabled. History search results are kept in memory, and AI
history search requests use the OpenAI Responses API with storage disabled.
The latest dictation audio is kept in one app-private WAV file and replaced by
the next dictation. It is excluded from Android backup with the rest of the app.

Completed text is copied to the Android system clipboard when it cannot be
inserted into the original field. Users can also explicitly copy an item from
history. The floating menu's Paste last action inserts text directly without
changing the clipboard. The fallback clipboard item is marked sensitive so
Android can conceal its preview; users should still treat the system clipboard
as shared device state.
