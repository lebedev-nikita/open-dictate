# Product

<!-- impeccable:product-schema 1 -->

## Platform

adaptive

## Users

Android and macOS users who want to dictate or transform text directly inside the app they are already using. The primary job is fast, system-wide text entry without copying a recording into a separate transcription tool. This audience description is inferred from the current product behavior and documentation.

## Product Purpose

OpenDictate turns speech into text in the currently focused field on Android or macOS. Success means the overlay appears only when it is useful, begins listening with one tap, returns text quickly, and never sends unrelated screen contents or stored recordings anywhere.

## Positioning

OpenDictate combines an Accessibility-powered, system-wide insertion overlay with two direct-to-OpenAI transcription paths: low-latency live transcription and a more accurate recorded-file flow. There is no product backend.

## Operating Context

The app is used while an Android keyboard and editable field are visible. A foreground service owns each microphone session, while the accessibility service guards focus and inserts cumulative transcript updates. Users provide their own OpenAI API key and install signed APK releases from GitHub.

## Capabilities and Constraints

- Live and accurate transcription modes use OpenAI directly from the device.
- Voice-directed text transformation and on-device search over local transcript history are supported.
- API keys are encrypted with Android Keystore; audio is temporary and cache-only.
- No analytics, ads, backend, persistent audio storage, or logging of sensitive data.
- The app targets Android 36, compiles with SDK 37, and requires Android 8.0 or later.
- The package ID is `com.opendictate.app`; this clean rebrand is a new Android app identity and cannot update an installation that used the previous package ID.

macOS uses a global shortcut and menu bar utility instead of a keyboard overlay.
Its native SwiftUI/AppKit settings are clean, compact and fast, with advanced
features on separate screens. Keys stay in Keychain; audio stays in memory.
The app requires macOS 14+, ships a universal DMG for Apple Silicon and Intel,
and uses independent `macos-v*` releases.

## Brand Commitments

The product name is OpenDictate. Android settings use a modern, minimalist, functional interface with neutral Material 3 surfaces, system sans-serif type, and light or dark appearance with a saved System/Light/Dark choice (System by default). Setup readiness and readable grouped controls take priority. The launcher mark and public website retain their established black, white, and restrained silver identity. The GitHub presence and public documentation must use only the OpenDictate identity.

## Evidence on Hand

- Working Android source and JVM tests in `apps/android/app/`.
- Privacy and security disclosures in `PRIVACY.md` and `SECURITY.md`.
- Signed APK release automation in `.github/workflows/`.
- Existing launcher waveform asset in `apps/android/app/src/main/res/drawable/ic_launcher_foreground.xml`.
- No testimonials, customer logos, usage metrics, or independent benchmarks are available; future surfaces must not invent them.

## Product Principles

- Put dictated text where the user is already typing.
- Prefer first-text latency in the live path and accuracy in the recorded path.
- Keep private data device-local except for explicit direct requests to OpenAI.
- Make session ownership and focus checks prevent stale text from reaching the wrong field.
- Keep installation, setup, and release artifacts understandable without a backend or account system.

## Accessibility & Inclusion

The overlay must remain operable with Android accessibility conventions, preserve 48 dp touch targets, respect keyboard and system insets, and never broaden Accessibility access beyond keyboard detection and focused-field insertion.
