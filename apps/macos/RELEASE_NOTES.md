OpenDictate for macOS **0.5.0-rc.29** makes microphone readiness faster without keeping the microphone active between dictations. Requires macOS 14+ on Apple Silicon or Intel.

- Audio capture now uses Apple's AVAudioSinkNode with a bounded, preallocated buffer. Audio processing stays off the realtime callback, and Live network chunks remain 40 ms.
- The indicator becomes ready after the first captured samples. A local built-in microphone check measured a median of 67 ms to readiness over six runs, compared with the earlier 153 ms baseline. Bluetooth microphone startup depends on the device; begin speaking when the ready indicator appears.
- Stopping includes the final captured samples and the audio converter's tail. Buffer overflow reports a capture failure instead of silently losing speech. Startup cancellation, secure-field protection and input-device selection remain supported.

Download the universal DMG and replace OpenDictate in Applications. This release candidate is ad-hoc signed and **not notarized**. Verify the attached SHA-256 checksum.
