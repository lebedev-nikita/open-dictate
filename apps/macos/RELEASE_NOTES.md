OpenDictate for macOS **0.5.0-rc.28** reduces microphone startup delay and shows when recording is actually ready. Requires macOS 14+ on Apple Silicon or Intel.

- The audio graph is prepared ahead of dictation without starting the microphone or recording audio. Switching the default input device while idle still uses the newly selected microphone.
- While a microphone starts, the indicator shows a spinner. The microphone or waveform and the recording menu-bar icon appear only after the first captured PCM buffer. With AirPods or other Bluetooth microphones, begin speaking once this ready state appears; speech before the device starts cannot be recovered.
- Finishing or cancelling while the microphone is still starting ends safely without uploading an empty recording. Accurate continues to send audio only after recording stops.

Download the universal DMG and replace OpenDictate in Applications. This release candidate is ad-hoc signed and **not notarized**. Verify the attached SHA-256 checksum.
