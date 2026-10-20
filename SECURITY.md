# Security

Please report vulnerabilities privately through GitHub Security Advisories.
Do not include API keys, recordings, or other sensitive data in public issues.

Word replacements use literal, case-insensitive matches with Unicode word
boundaries. Rules are compiled once per dictation, operate only on recognized
text, and never alter the captured original field or voice-edit instructions.
Rules are applied on-device. Optional Drive sync uses the same app-data scope
for the rule list and enabled states.

Optional post-processing sends recognized dictation text and generated
instructions plus configured value-format examples directly to OpenAI through Responses with storage disabled.
Only the session’s selected speech languages contribute bundled language-specific
examples; automatic detection sends no such examples. Generated prompts remain
request-only and never enter synchronization or history. It is
available after ordinary Accurate dictation and Accurate retranscription on both platforms;
voice-edit commands skip it. Settings are captured at session start. Disabled
post-processing or no enabled rules makes no additional request. Failures retain
the original transcript and display a curated warning; cancellation still prevents
delivery. The punctuation-only configuration rejects changes to words, numbers
and address contents. Value formatting changes notation and is not constrained by that punctuation-only
validator. A bounded Russian/English cardinal parser checks supported scaled integer
values before accepting formatted output; changed values retain the source with the
existing failure warning. This is not a general semantic validator for all languages
or numeric types. Fractional expressions and convertible durations are excluded
from that integer check. Examples are bounded data, not a free-form instruction editor. Custom rules pair
a value type and language/all-language scope with the desired rendering of a
bundled fixed reference value; no model is called to configure or reconstruct it.
Version-2 settings synchronize those examples and captured regional locale tags.
Specific language styles override general styles; unknown/mixed-language input
uses the general style. Invalid or oversized remote settings are rejected atomically.

ChatGPT text streams retain finished output items only in request memory. A successful
terminal event is required before returning assembled output; deltas, interruption,
incomplete responses and errors cannot deliver a partial result.

## Optional Google Drive synchronization

Validated replicas and Drive versions are cached only in process memory, without
persistent storage or credentials, audio or history. Android's normal
`ACCESS_NETWORK_STATE` permission and macOS path monitoring are used only to defer
sync offline and resume queued work when connectivity returns.

Sync uses Google's HTTPS endpoints without a backend, requests only
`drive.appdata` and exports an allowlist of dictionary, replacement, mode and
model settings and post-processing
configuration (enabled value types, scoped format examples and regional locale tags). A shared text-processing
model defaults to GPT-6 Luna and is captured at session start for transformation
and post-processing. Legacy Accurate punctuation and transcription-prompt/text-model sync entries are discarded.
API keys, audio and history are excluded. Tokens, formatting examples, dictionary/replacement contents and raw
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

## ChatGPT plan credentials

Optional Sign in with ChatGPT uses the public-client OAuth code flow with PKCE,
state and nonce, an IPv4 loopback callback, issued client IDs and verified RS256
OIDC identity. Only official OpenAI authorization, token, JWKS, revocation and
API endpoints receive these credentials. Credentials never come from another
app or Codex installation. Redirects, cookies and persistent HTTP caches are
disabled. The loopback listener is bounded and closes after completion,
cancellation or timeout; Android still disables cleartext client traffic.

Account registrations and rotating tokens stay in a separate encrypted Android
credential namespace or non-synchronizing device-only macOS Keychain item.
Refreshes are serialized per session, terminal refresh errors discard unusable
tokens, and temporary errors retain credentials. A captured ChatGPT billing
selection never silently falls back to the API key. Corrupt or locked credential
storage fails closed. API-key transcription remains a prerequisite to sign-in.
Plan inference requires `store: false`, `stream: true`, array input and the
terminal `response.completed`; deltas, incomplete streams and usage errors are
not successful results. OAuth and SSE responses are bounded and never logged.
See [setup and protocol references](docs/chatgpt-plan.md).

## Android

OpenDictate intentionally never logs authorization headers, API keys, audio,
transcript contents, transformation instructions, or focused-field text.
Model discovery calls OpenAI directly and stores only model IDs in app-private
preferences. The model list API does not supply endpoint capabilities or prices,
so recognition pickers keep only the transcription aliases. Text model offers
GPT-6 Luna and GPT-6 Luna Fast, defaulting to Fast, and controls post-processing
and voice editing. The service tier is stored and synchronized separately from
the model ID and captured at session start. AI history
search remains fixed to GPT-6 Luna; retired legacy selections cannot override it.
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

History request durations, model IDs, post-processing service tier and the Live
mode label remain local history metadata.
They are excluded from Drive synchronization and AI history-search payloads.
Accurate timings use monotonic elapsed time around each full request, including
upload and response. Recording and text delivery are excluded.
