OpenDictate for macOS **0.5.0-rc.24** fixes a crash after installing the app and synchronizing formatting rules. Requires macOS 14+ on Apple Silicon or Intel.

- Load formatting rules, language examples and integer vocabulary from the installed app's Resources directory, without relying on the CI build directory.
- Packaging now runs an isolated resource check against the finished app before creating the DMG. This check does not read preferences or credentials and does not use the microphone or network.
- Retains RC23's one-session post-processing menu, processing-stage HUD and history model details.

Download the universal DMG and replace OpenDictate in Applications. This release candidate is locally signed and **not notarized**. Verify the attached SHA-256 checksum.
