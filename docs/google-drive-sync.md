# Google Drive settings synchronization

Connect Google Drive in Settings on Android and macOS, using the same Google
account. Sync transfers the dictionary, word replacement rules and enabled
states, Live/Accurate mode and selected Live, Accurate and text model IDs.
AI formatting instructions also sync, including when post-processing is disabled.
The Accurate AI post-processing switch is saved only on each device and never synced.
Android preserves the instructions without applying an extra AI formatting step.
API keys, audio, transcripts/history, app exclusions and permissions are excluded. Model access still depends on each device's
OpenAI API key and local model catalogue.

Local changes upload automatically after **15 seconds without further edits**.
Dictionary typing and consecutive replacement/model edits are batched into one update.
Only changes to synchronized settings trigger an upload.

Cloud settings are checked at startup and **every 15 minutes after a successful
sync** while the process runs. Opening macOS Settings, waking the Mac or resuming
Android requests a check only when the last successful sync is at least **five
minutes old**. Returning connectivity sends queued edits or retries a failed sync;
without pending work it checks only when data is at least five minutes old.
Offline checks wait for connectivity. There is no background daemon or Android
scheduled worker.

Network errors retry after **1, 5, 15 and then 30 minutes**, capped at 30 minutes.
On Android, background failures produce no toast or notification, including when
Settings opens after a failure. Only failed user-initiated Google sign-in shows a
message. Local edits and automatic lifecycle checks respect retry backoff. Authorization
errors pause automatic attempts until sign-in; an explicit retry may attempt again.
**Sync now**, connecting and choosing a source request an immediate check.
On macOS, **Sync now** (Command-R) and source confirmation also bypass the
15-second quiet period for existing edits. Other requests still respect this
period. Edits made during a request remain queued. Errors preserve local settings
for retry.

Each check lists replica metadata. Validated replicas are cached **only in memory**
by file ID and Drive `version`; unchanged files are not downloaded again. Missing
versions are always downloaded, deleted replicas leave the cache, and uploading
invalidates the own-file cache. Connecting or choosing a connection source forces
a fresh download. Restarting the process clears the cache. Uploads remain conditional
on actual merged changes or a missing own replica. API keys, audio and history
never enter the cache or synchronization payload.

## Google Cloud setup

The OpenDictate sync project is `opendictate-sync-20261001` (project number
`951388859601`). Google Drive API and the `drive.appdata` consent scope are
configured there. The consent screen uses **External / In production**, published
on 2026-10-15. Users no longer need to be added to a test-user list. The following
OAuth clients were created on 2026-10-01; each device still needs user sign-in
and consent:

| Client | OAuth client ID |
| --- | --- |
| Android release | `951388859601-vc6mquegouncri0q502a77qpuimkag7m.apps.googleusercontent.com` |
| Android debug | `951388859601-7qpmqaqh1bqrtrc68g7mdus5mc065t8p.apps.googleusercontent.com` |
| Android preview | `951388859601-dkeaepvgt48ovur5etg7cclpinik3f9c.apps.googleusercontent.com` |
| macOS Desktop | `951388859601-rl3ituvc93tpg2715kbumrnbci6d4ueh.apps.googleusercontent.com` |

Desktop credentials are kept outside Git. Do not add the downloaded client secret
or user tokens to this document.

Android registration fingerprints prepared on 2026-10-01:

| Build | Package | Certificate SHA-1 |
| --- | --- | --- |
| Local debug | `com.opendictate.app.debug` | `8E:F4:A9:1A:C9:28:D1:BA:03:68:6A:74:79:1B:0C:DC:1B:B3:69:CB` |
| Signed release | `com.opendictate.app` | `40:4E:DE:59:44:8D:90:86:75:7B:60:87:7D:07:8E:1E:0E:6B:06:6D` |
| Signed preview | `com.opendictate.app.preview` | Same release certificate when using the repository's release signing configuration |

The release fingerprint was read from the public signed `v0.11.0-rc.5` APK.
Other developers' debug certificates and a future Play App Signing certificate
need their own Android OAuth registrations in this same project.

Before sign-in can work, configure one Google Cloud project:

1. Enable the **Google Drive API** and configure the OAuth consent screen with
   `https://www.googleapis.com/auth/drive.appdata`. In Testing mode, add the
   Google accounts that will test synchronization.
2. Create an **Android** OAuth client for every package/signing certificate used:
   `com.opendictate.app`, `com.opendictate.app.debug` and/or
   `com.opendictate.app.preview`. Register the actual certificate SHA-1. Use
   `./gradlew signingReport` for local fingerprints; register release/Play signing
   certificates separately. Google Identity Services identifies the app from its
   package and signature, so no Android client ID is embedded. Google Play
   services must be available on the device.
3. Create a **Desktop app** OAuth client in the **same project** and download its
   installed-app JSON. The macOS maintainer supplies that JSON through the
   `MACOS_GOOGLE_DRIVE_OAUTH_CLIENT_JSON` repository secret. Local packaging can
   instead use `GOOGLE_DRIVE_OAUTH_CLIENT_FILE=/absolute/path/to/client.json`.
   The build embeds only the application client ID and client secret; users
   choose their Google account without entering technical configuration. Release
   publication fails if the client configuration is missing or invalid. Keep the
   source JSON outside Git. Google's Desktop client secret is not a confidential
   server secret; PKCE protects the code exchange too.
4. Turn on the Google Drive switch on both platforms and accept the app-data permission.
   macOS opens the system browser and validates PKCE S256 and OAuth state through
   a temporary callback listener bound only to `127.0.0.1`. Android uses native
   Google Identity Services AuthorizationClient consent resolution.

To provision the macOS build configuration without putting its contents in shell
arguments or Git history, run:

```bash
gh secret set MACOS_GOOGLE_DRIVE_OAUTH_CLIENT_JSON --repo lebedev-nikita/open-dictate < /absolute/path/to/client.json
```

The macOS browser sign-in waits up to ten minutes. If it expires, turn the Google
Drive switch on again, or use **Sign in again** if it is already on; reloading an
expired local callback cannot complete sign-in. Turn the switch off to cancel.
Cancellation and expiry close the temporary listener without saving credentials.

The OAuth clients must belong to the same Cloud project to share the application
data space. No permission to ordinary Drive files is requested. There is no backend.

### Repeated sign-in or connection failures on macOS

Google Cloud projects with External / Testing status issue refresh tokens that
expire after **seven days** for `drive.appdata`.
[Google documents this limit](https://developers.google.com/identity/protocols/oauth2#expiration).
An expired or revoked grant pauses automatic sync. Use **Sign in again**, or turn
the Google Drive switch on if it is off. Complete browser sign-in; application
client configuration is supplied by the build. Local settings stay intact.

If the app reports that Google Drive is not configured in this build, install an
official configured build or contact the maintainer. Maintainers should check the
installed-app JSON supplied at packaging. Google error descriptions and token
responses are never displayed.

For a separate Cloud project, complete Google Auth Platform → Branding, then
change Audience → Publishing status to **In production** to allow users outside
the test-user list and remove the Testing seven-day expiry. Publishing does not
restore already expired tokens: reconnect once after changing the status.

The OpenDictate project is already in production. Its Branding configuration uses
the [application homepage](https://lebedev-nikita.github.io/open-dictate/) and
[privacy policy](https://lebedev-nikita.github.io/open-dictate/privacy.html), with
`lebedev-nikita.github.io` registered as an authorized domain. Google classifies
[`drive.appdata` as non-sensitive](https://developers.google.com/workspace/drive/api/guides/api-specific-auth);
Verification Centre confirms that data-access verification is not required for
this configuration. Brand verification is a separate step required to display
the app name and logo.

A Google sign-in error saying that the app is available only to approved testers
indicates a Cloud project still in Testing. Check the project associated with the
actual Android package/signing certificate or Desktop client; reinstalling the
app does not change its OAuth publishing status.

## Connecting and choosing a source

On Android and macOS, every connection or reconnection reads Drive before applying
or uploading any settings. If a known synced setting differs, choose **Cloud settings**
(the default) or **Local settings**. Cloud replaces this device's synced values;
Local publishes this device's synced values for other connected devices to receive.
The dictionary and replacement list are each selected as a whole, without combining
words or rules. Cancel disconnects without changing either source.

An empty cloud or matching synced values connects without a source dialog. Fields
missing from an older cloud journal keep their local values. Unknown future entries
still merge normally. An explicit source choice overrides offline timestamps and
advances clocks so stale replicas cannot undo it. The app reads Drive again before
using the choice; changes to local or cloud values require choosing again. This is
not a cross-device transaction: edits made after the final read follow normal
per-preference conflict rules.

Automatic sync cannot bypass a pending choice, including after a network failure
or app restart. After a successful connection, ordinary automatic synchronization
continues using the conflict rules below. Existing connected installations keep
automatic sync; the choice appears when they reconnect.

## Storage and conflicts

The hidden `appDataFolder` contains one `opendictate-settings-v1-<device>.json`
replica per installation. Each installation reads all replicas with pagination,
merges them and updates only its own file. Concurrent uploads cannot overwrite
another device's file. Empty dictionaries are explicit values, so older offline
replicas cannot resurrect a cleared dictionary.

Each preference merges independently. The latest `modifiedAt` wins; `deviceId`
and then `value` break ties deterministically. Clocks advance past the largest
observed timestamp even if the device clock moves backwards. New-device defaults
have timestamp zero and yield to existing cloud values when merged. A differing first connection now asks for a source.
**The dictionary is one preference:** simultaneous dictionary edits do not combine
words; the latest dictionary replaces the earlier one. The replacement rule list
is also one preference: simultaneous edits choose the latest complete list,
including each rule’s ID and enabled state. Clearing exports an explicit empty
list, so stale replicas cannot restore deleted rules. The master replacement
switch merges independently from the list. Offline changes use device clocks,
so keep system clocks reasonably accurate.

Disconnect signs this installation out of synchronization and cancels its current
sync task. On macOS it removes the user refresh token from Keychain while retaining
the application client configuration.
Every manual connection on both platforms asks you to select a Google account,
even when Google has cached authorization. No client settings need to be entered
again after disconnecting.
Local settings and cloud files remain. Android leaves token caching to Google Play services.
An upload already accepted by Google may finish during disconnection. Disconnect
does not revoke Google's grant or sign out other devices. To revoke the grant,
use Google Account → third-party connections.
Delete replicas through Google Drive → Settings → Manage apps → OpenDictate →
Delete hidden app data.

## Extending the format

Version 1 has an `entries` map. The authoritative key lists live in
[`SettingsSync.swift`](../apps/macos/Sources/OpenDictateCore/SettingsSync.swift)
and [`SettingsSyncDocument.kt`](../apps/android/app/src/main/java/com/opendictate/app/data/SettingsSyncDocument.kt):

```json
{
  "schemaVersion": 1,
  "entries": {
    "dictionary": {
      "value": "OpenDictate\nExample",
      "modifiedAt": 1760000000000,
      "deviceId": "installation-uuid"
    }
  }
}
```

Add namespaced keys to the explicit export allowlists and storage adapters on both
platforms for new settings. Unknown entries survive merges and round trips.
Unsupported schema versions fail closed rather than being overwritten. Values
are strings; structured future sections can encode JSON inside their value.
The `wordReplacements` value contains the shared version-1 replacement JSON
(`schemaVersion` and `rules` with `id`, `source`, `replacement`, `enabled`).
`wordReplacementsEnabled` is the string `true` or `false`. Invalid boolean
values reject the import before any setting is applied. Existing five-field
journals seed replacement settings at timestamp zero so new defaults yield to
cloud values. The former `accuratePunctuationEnabled` sync field is discarded
from local journals and incoming replicas, and excluded from uploads. Its
existing local value is preserved. Older app versions may still sync that field
among themselves until updated. Imported replacement JSON is preserved verbatim
to avoid platform-specific encoding creating a new edit. Remote rules are validated before any settings are applied.

`formattingRules` contains user-authored AI formatting instructions. An empty string
restores default punctuation correction. Rules are limited to 4,000 UTF-16 units;
oversized rules reject the whole import. Android relays the field without applying it.

Clearing needs an explicit value, because removing a key leaves older replicas intact.

Limits: 1 MiB per file, 100 replicas, 1,024 entries and 256 KiB per value.
Malformed or oversized data stops synchronization without uploading a partial
merge. Credentials, provider payloads, dictionary contents and replacement rules are
never logged.
Network work runs separately from audio and away from the main thread/actor.

## Verification

On 2026-10-01, the local macOS build completed browser sign-in with the configured
Desktop client and a real test account, uploaded its initial replica, then
completed **Sync now** using stored credentials to read and update that replica.
Real Android-to-macOS convergence has not yet been checked on a signed device.

Unit/mock tests cover convergence, separate settings, conflicts, clearing,
clock rollback, first-link defaults, unknown-key preservation, export allowlists,
schema rejection, pagination, private-folder creation, own-file-only updates and
safe auth errors. macOS tests also cover local edits during download and active
session model snapshots, replacement import, deletion, switches, migration from
five-field journals and invalid-rule rejection. A shared JSON fixture verifies
the Android/macOS replacement wire format.

Real-account checks require configured clients: connect both platforms, edit
each synced preference, clear the dictionary, edit different/the same settings
offline, retry after a network failure, deny consent, cancel browser sign-in,
revoke access and disconnect during sync. Check that active dictation retains its
captured settings, hotkeys, focus guards, cancellation and insertion behavior.

References: [Drive app data](https://developers.google.com/workspace/drive/api/guides/appdata),
[Android authorization](https://developer.android.com/identity/authorization),
[Desktop OAuth and PKCE](https://developers.google.com/identity/protocols/oauth2/native-app).
