# TD Flew

**Your Telegram drive, with wings.**

TD Flew is an Android file manager and backup app that turns a Telegram chat into a
private drive. It uses TDLib directly, so your Telegram password never passes through
a third-party server, and files stay in your own Telegram account.

The interface is built with Jetpack Compose and Material 3 Expressive styling. It has
three tabs: Gallery, Files, and Menu.

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

Only **outgoing** messages are indexed. Anything forwarded into the chat from another
account is not silently treated as a file in the folder tree.

## First run

1. Install the APK.
2. Enter your phone number in international format.
3. Enter the login code from Telegram.
4. If two-step verification is enabled, enter your password.
5. Handle any additional Telegram confirmation steps.

Saved Messages is selected as the default drive automatically. You can switch to any
chat, channel, or group where your account can post through **Menu → Change drive**.

## Downloads and sharing

On Android 10 and newer, saved files go to the public `Downloads/TD Flew` collection
through MediaStore. On older versions they go to the app-specific Downloads folder and
are shared through a `FileProvider`.

Gallery previews and thumbnails are cached by TDLib. **Menu → Free cache** clears the
local TDLib file cache and preview files, but never deletes messages from Telegram.

## Limitations

- The drive index is rebuilt from at most the latest 5,000 messages in the selected chat.
- Attachments sent by other people and older messages without a `TDFLEW1` marker are not
  part of the managed folder tree.
- Telegram caption limits and app-side validation cap names and paths.
- The app needs a working Telegram login and cannot browse a drive while signed out.
- Very large uploads depend on Telegram and TDLib limits. Videos are not re-encoded.

## Privacy

TD Flew talks directly to Telegram through TDLib. It does not run a backend, collect
analytics, or upload your credentials anywhere. Telegram receives the same metadata it
would receive for a normal message or file. Use **Menu → Sign out of TDLib** to end the
session on this device.

## License

TD Flew is distributed under the GNU General Public License, version 3. See
[`LICENSE`](LICENSE).

The TDLib bindings and prebuilt binaries are distributed under the Boost Software
License 1.0. Third-party notices are in [`THIRD_PARTY_LICENSES.txt`](THIRD_PARTY_LICENSES.txt).

## Links

- Repository: <https://github.com/shinbhairava/td-flew>
- TDLib: <https://github.com/tdlib/td>
- EasyTDLib reference: <https://github.com/kcvabeysinghe/EasyTDLib>
