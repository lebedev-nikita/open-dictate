# Control Android dictation from other apps

Automation apps such as Automate, Tasker or Key Mapper can start dictation from a
hardware button, shortcut or Quick Settings tile. OpenDictate has no
device-specific button code; map the button in the automation app and send a
command, or let OpenDictate learn a button from the system log.

Enable **Settings → Integrations → Control from other apps**. It is off by
default; while it is off, both entry points below are disabled Android components.

## Hardware button

**Settings → Keyboard → Hardware button** learns a button from its next press.
Hold the button in a text field to dictate and release it to finish. Elsewhere
the button keeps its usual behavior. A volume button starts dictation only after
a 0.4-second hold; a shorter press still changes the volume. Other buttons start
dictation as soon as they are pressed in a text field. Pressing the button during
a session started another way stops it.

Android sends key presses to OpenDictate only while a button is bound or being
learned, and only through its accessibility service. Back, Home, Recents and
Power cannot be bound. Android does not pass on buttons it cannot name, such as
some manufacturer keys. Use an automation app for those if it can detect them,
and send the commands below.

## Buttons that Android hides from apps

Some manufacturer buttons, such as the Plus Key on OnePlus phones, never reach
apps as key events but leave a line in the system log. OpenDictate can watch for
that line. Reading the log needs a permission that only adb can grant:

```bash
adb shell pm grant com.opendictate.app android.permission.READ_LOGS
```

**Settings → Keyboard → Button from the system log** appears after that. Turn it
on, press the button three times when asked and then both volume buttons;
OpenDictate finds the line that only your button leaves. A press then starts dictation into the focused text field and the
next press finishes it, with or without an open keyboard.

**Double press** takes a link with `{text}`, for example
`t3code://new?prompt={text}`. A quick second press then records with a small
panel instead, and opens the link with the dictated text in place of `{text}`.
A single press, or the panel's **Done** button, finishes that recording.

Android asks to allow log access each time the app starts reading, and only
while the app is open. After a restart of the phone or the app, a notification
asks you to open OpenDictate once. Key presses sent through adb can leave the
same log line and count as presses.

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

### Apps that can only open activities

Some button remappers can only open an activity. Start an activity with one of
the actions above in package `com.opendictate.app`, or with the component
`com.opendictate.app/.ui.DictationCommandActivity`. It has no window and closes
at once.

Opening any activity switches apps, and Android closes the keyboard. A session
started this way therefore needs only a focused text field and continues while
that field keeps focus; tap the field to bring the keyboard back. It is
cancelled if focus stays elsewhere for a second. Stop it with the same command
or the indicator; cancel it with the indicator or `DICTATION_CANCEL`.

For example, Plus Key Remapper on OnePlus phones maps the Plus Key with a custom
intent: action `com.opendictate.app.action.DICTATION_TOGGLE`, package
`com.opendictate.app` and component class
`com.opendictate.app.ui.DictationCommandActivity`. Without the class it opens
the app instead.

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
