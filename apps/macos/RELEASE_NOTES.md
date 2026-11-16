OpenDictate for macOS **0.9.0-rc.1** transcribes meetings. Requires macOS 14.2+ on Apple Silicon or Intel.

- **Record meeting** in the menu bar records your microphone as Me and the call as Other speaker, then saves a timestamped transcript. Choose the call app (Zoom, Chrome, Teams…) or all system audio; the choice is remembered. An optional shortcut is in Settings → Keyboard shortcuts.
- Each phrase is transcribed with your Accurate settings a few seconds after it ends. A network failure retries only that phrase; silence is not sent. Meetings have no length limit and pause instead of dropping speech if recognition falls far behind.
- The meeting window shows the live transcript, Pause, Finish and a confirmed Cancel. Dictation and voice editing keep working during a meeting, and their speech stays out of the transcript.
- Without headphones, the other side's words that your microphone picks up are removed from Me.
- Settings → Meetings lists saved meetings with search, speaker renaming, Copy as Markdown, Save as `.md` and Delete. An optional folder receives every finished meeting as Markdown. **Copy last meeting** is in the menu.
- macOS asks for **System Audio Recording** permission on the first meeting. Other participants' speech is sent to OpenAI; getting their consent is your responsibility. Transcripts stay on this Mac and are not synced; audio is never saved.
- macOS 14.0 and 14.1 are no longer supported.

Download the universal DMG and replace OpenDictate in Applications. This release candidate is ad-hoc signed and **not notarized**. Verify the attached SHA-256 checksum. The stable Homebrew release remains unchanged.
