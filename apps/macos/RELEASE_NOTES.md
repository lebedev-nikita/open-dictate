OpenDictate for macOS **0.5.0-rc.30** restores the waveform indicator's original movement speed. Requires macOS 14+ on Apple Silicon or Intel.

- The ten waveform bars now advance ten times per second, showing about one second of microphone levels. Faster audio callbacks no longer make the waveform scroll faster.
- The first level appears immediately, and starting a new dictation clears the previous waveform. The indicator's size and controls remain unchanged.
- Keeps RC29's faster microphone startup. Audio buffer sizes, recording and 40 ms Live network chunks are unchanged.

Download the universal DMG and replace OpenDictate in Applications. This release candidate is ad-hoc signed and **not notarized**. Verify the attached SHA-256 checksum.
