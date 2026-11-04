# TD Flew

**Your Telegram drive, with wings.**

TD Flew is an Android file manager and backup app that turns a Telegram chat into a
private drive. It uses TDLib directly, so your Telegram password never passes through
a third-party server, and files stay in your own Telegram account.

The interface is built with Jetpack Compose and Material 3 Expressive styling. It has
three tabs:

| Tab | What it does |
| --- | --- |
| **Gallery** | Browse photos and videos stored in your drive. Upload media, search by name or date tag, save, share, or open items. |
| **Files** | Browse folders, upload documents, create folders, sort by name/date/size, and manage saved copies. |
| **Menu** | Choose the Telegram drive chat, inspect upload/download history, clear the local cache, read logs, open the GitHub page, view licenses, and sign out of TDLib. |

## How storage works

There is no folder API in Telegram, so TD Flew stores a compact marker in the caption
of each outgoing message:

```text
TDFLEW1:F:<base64url path>   # file or media
TDFLEW1:D:<base64url path>   # folder
```

The app scans the selected chat's outgoing history and rebuilds the folder tree from
those markers. Folders are zero-byte text messages; files, photos, and videos are normal
Telegram messages. Deleting a folder deletes every marked descendant message.

Only **outgoing** messages are indexed. This keeps the drive private and predictable:
anything you forward into the chat from another account is not silently treated as a
file in your folder tree.

## First run

1. Install the APK.
2. Enter your phone number in international format.
3. Enter the login code from Telegram (SMS, call, or in-app message).
4. If two-step verification is enabled, enter your password.
5. Handle any additional email, registration, or other-device confirmation Telegram asks for.

Saved Messages is selected as the default drive automatically. You can switch to any
chat, channel, or group where your account can post by opening **Menu → Change drive**.
Enter a numeric chat ID, an `@username`, or a `t.me` link.

## Build with GitHub Actions

The project is set up so contributors do not need a local JDK, Gradle, or Android SDK.
CI builds every pull request and push to `main`.

The workflow is [`.github/workflows/android.yml`](.github/workflows/android.yml). It:

- runs `testDebugUnitTest` and `assembleDebug` on every pull request;
- builds a signed release APK on every workflow run;
- uploads one `build` artifact containing the release APK;
- publishes a GitHub Release for tags.

### Telegram API credentials

TD Flew needs Telegram `api_id` and `api_hash` values from
[my.telegram.org](https://my.telegram.org). Add these repository secrets:

| Secret | Value |
| --- | --- |
| `API_ID` | Your numeric Telegram API ID |
| `API_HASH` | Your Telegram API hash |

If they are absent, the build falls back to the public credentials used by the
reference project. That fallback is only a convenience for trying the app. It can be
rate-limited or revoked without notice, and it is not appropriate for a production
release. Always configure your own credentials for published builds.

The API credentials are compiled into `BuildConfig`. They are application identifiers,
not account credentials, but you should still use your own pair.

### Release signing

Create a keystore locally, then add these repository secrets:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64-encoded keystore: `base64 -w0 release.keystore` |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Key alias |
| `ANDROID_KEY_PASSWORD` | Key password |

Every CI run decodes the keystore to `$RUNNER_TEMP/release.keystore`, exports
`ANDROID_KEYSTORE_PATH`, and signs both the release artifact and the tag release. CI fails
early if any signing secret is missing. Pull requests from forks cannot access repository
secrets; run those workflows from the repository or use a maintainer-approved rerun.

Example keystore creation:

```bash
keytool -genkeypair -v \
  -keystore release.keystore \
  -alias tdflew \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.keystore   # paste this into ANDROID_KEYSTORE_BASE64
```

Never commit a keystore, `keystore.properties`, or passwords. `.gitignore` already
excludes common keystore and signing files.

### Versioning

CI sets `APP_VERSION_CODE` from the workflow run number and `APP_VERSION_NAME` from the
tag (with the leading `v` removed). To publish:

```bash
git tag v1.0.0
git push origin v1.0.0
```

The workflow builds the release and creates a GitHub Release with the APK attached.

## Optional local build

A local build is supported but not required:

```bash
export ANDROID_HOME=/path/to/android-sdk   # platform 36 + build-tools 36.0.0
./gradlew testDebugUnitTest assembleDebug
```

For a signed release, export the four `ANDROID_KEYSTORE_*` variables before running
`./gradlew assembleRelease`. Without them, the release build is produced unsigned.

## Project layout

```text
app/src/main/java/com/shinbhairava/tdflew/
├── data/       settings, transfer history, cache tools, Downloads/MediaStore saving
├── drive/      drive models, caption/path codec, sort options
├── engine/     TDLib client wrapper, media inspection, gallery date search
└── ui/         Compose screens: auth, gallery, files, menu, shared components
```

TDLib's Java bindings and generated `TdApi` live under
`app/src/main/java/org/drinkless/tdlib/`. Prebuilt native libraries for all supported
ABIs are under `app/src/main/jniLibs/`.

## Downloads and sharing

On Android 10 and newer, saved files go to the public
`Downloads/TD Flew` collection through MediaStore. On older versions they go to the
app-specific Downloads folder and are shared through a `FileProvider`. Deleting a file
from Telegram never deletes a copy already saved to Downloads.

Gallery previews and thumbnails are cached by TDLib. **Menu → Free cache** clears the
local TDLib file cache and preview files, but never deletes messages from Telegram.

## Limitations

- The drive index is rebuilt from at most the latest 5,000 messages in the selected chat.
  A warning appears when this cap is reached.
- Attachments sent by other people, and older messages without a `TDFLEW1` marker, are
  not part of the managed folder tree.
- Telegram caption limits and app-side path validation cap names at 96 characters and
  paths at 480 characters.
- The app needs a working Telegram login. It cannot browse a drive while signed out.
- Very large uploads depend on Telegram and TDLib limits. The app does not re-encode
  videos before upload.

## Privacy

TD Flew talks directly to Telegram through TDLib. It does not run a backend, collect
analytics, or upload your credentials anywhere. Telegram receives the same metadata it
would receive for any normal message or file. Use **Menu → Sign out of TDLib** to end the
session on this device.

## License

TD Flew is distributed under the GNU General Public License, version 3. See
[`LICENSE`](LICENSE).

The TDLib bindings and prebuilt binaries are distributed under the Boost Software
License 1.0. The `Client.java` and `TdApi.java` bindings are based on
[EasyTDLib](https://github.com/kcvabeysinghe/EasyTDLib), which is MIT licensed. Full
third-party notices are in [`THIRD_PARTY_LICENSES.txt`](THIRD_PARTY_LICENSES.txt) and are
also bundled in the app under **Menu → About → Licenses**.

## Links

- Repository: <https://github.com/shinbhairava/td-flew>
- TDLib: <https://github.com/tdlib/td>
- EasyTDLib reference: <https://github.com/kcvabeysinghe/EasyTDLib>
