OpenDictate for macOS **0.5.1** fixes ChatGPT session recovery. Requires macOS 14+ on Apple Silicon or Intel.

- Recover a saved ChatGPT account after a temporary Keychain read failure at startup without requiring another sign-in.
- Finish and persist token rotation when dictation is cancelled, then stop before sending a text-processing request.
- Keep a replacement token in memory when Keychain becomes unavailable during rotation or a write fails. Retry the protected write before continuing; preserve other accounts and newer sign-ins.
- Recheck Keychain access on each credential operation. A cached session cannot bypass a locked or inaccessible Keychain.
- Wait for an in-flight rotation before signing out and revoke the latest refresh token. Failed ChatGPT requests never silently fall back to API billing.

Upgrade with Homebrew or download the universal DMG and replace OpenDictate in Applications. This release is ad-hoc signed and **not notarized**. Verify the attached SHA-256 checksum.
