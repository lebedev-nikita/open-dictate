# Control Android dictation from other apps

Automation apps such as Automate, Tasker or Key Mapper can start dictation from a
hardware button, shortcut or Quick Settings tile. OpenDictate has no
device-specific button code; map the button in the automation app and send a
command.

Enable **Settings → Integrations → Control from other apps**. It is off by
default; while it is off, both entry points below are disabled Android components.

## Commands: dictate into the focused field

Send an explicit broadcast to package `com.opendictate.app` with one of these actions:

| Action | Effect |
|---|---|
| `com.opendictate.app.action.DICTATION_START` | Start dictation into the focused field |
| `com.opendictate.app.action.DICTATION_STOP` | Stop recording and deliver the text |
| `com.opendictate.app.action.DICTATION_TOGGLE` | Start when idle, otherwise stop |
| `com.opendictate.app.action.DICTATION_CANCEL` | Discard the session; Live text is undone |

Commands use the same path as the button above the keyboard: the current
Live or Accurate mode, the focused-field and app guards, the app filter, and
cancellation when the keyboard closes. Start requires the OpenDictate
accessibility service, an open keyboard and a focused text field. Stop, toggle
and cancel act on whichever session is running. Commands never return text.

When **Button above the keyboard** is off, the button appears only while a
session runs. It shows recording and processing; tap it to stop or swipe it
to cancel.

- **Hold to talk in Automate**: a *Key pressed* block takes its YES path on
  press and its UP path on release. Send `DICTATION_START` from YES and
  `DICTATION_STOP` from UP.
- **Key Mapper** runs an action on press only. Use an Intent action with target
  *Broadcast receiver* and `DICTATION_TOGGLE`.

## Return text to the calling app

OpenDictate answers the standard `android.speech.action.RECOGNIZE_SPEECH`
activity request. The caller must start it for a result, for example with
Automate's *App decision* block. The first request from each app asks for
permission. Revoke access under **Control from other apps** in settings.

A request records with a small panel and transcribes with the Accurate path,
current post-processing, dictionary and replacements. The panel's **Done**
button, `DICTATION_STOP` or `DICTATION_TOGGLE` finishes it. Cancel, back or
`DICTATION_CANCEL` discards it. The transcript is saved to history; its audio
is a temporary cache file and is not kept for retry.

The result uses `RecognizerIntent` codes:

| Result | Extras |
|---|---|
| `RESULT_OK` | `android.speech.extra.RESULTS` (one string) and `com.opendictate.app.extra.TEXT` |
| Other codes | `com.opendictate.app.extra.ERROR` |

`ERROR` is one of `cancelled`, `not_allowed`, `busy`, `missing_api_key`,
`microphone_permission`, `network`, `timeout`, `server`, `empty` or `unexpected`.
Without the overlay privilege, Automate opens the panel from a notification;
enable *Show window* and grant the privilege to open it directly.

## Security

Android does not identify broadcast senders, so any app can send a command
while the feature is on. A command's text goes only into the field the user is
editing, and the session is visible. The recording indicator, Android's
microphone indicator and cancel are always available. Text is returned only to
apps the user allowed, which Android identifies through the activity result.
