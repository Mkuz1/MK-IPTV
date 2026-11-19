# MK IPTV – Privacy Policy

_Last updated: 19 November 2026_

MK IPTV is a video player for IPTV services that use the Xtream Codes API. It does not provide any TV channels, movies or series. You connect it to a service you already subscribe to.

## What the app stores, and where

Unless you turn on **Sync across devices**, everything stays **on your device**:

- **Playlist details you enter**: server address, username and password. The password is encrypted with a key held in the Android Keystore.
- **Profiles, favourites, watch progress and settings.**
- **A cached copy of your provider's channel/movie/series list**, so the app opens quickly.
- **Downloaded videos**, in the app's private storage.

This data is excluded from Android cloud backup and device-to-device transfer.

## What is sent over the network

- **To the IPTV provider you configured**: your username and password, plus requests for channel lists, programme guides, artwork and video streams. This is required for the app to work. That provider's own privacy policy applies to it.
- **When you cast (Google Cast) or use AirPlay**: the stream address of what you are watching is sent to the TV or device you choose on your network.

## Sync across devices (optional, off by default)

If you turn on sync (Settings › Sync across devices), the app stores a copy of your **playlists (including their passwords), profiles, favourites, watch progress, items removed from Continue Watching and the live-stream format preference** in Google Firebase (Cloud Firestore), so your other devices can download it.

- You can sign in with **Google** or **Apple**, or use a **link code** without an account (an anonymous Firebase account is created). With Google/Apple sign-in, Firebase Authentication receives your account's email address and user ID.
- Firestore security rules allow only devices you linked to your library to read or write it. Data is encrypted in transit and at rest by Google.
- **Turn off** stops syncing on that device. **Delete synced data** removes your synced library and sync account from Firebase; data on your devices stays until you remove it.
- Firebase is operated by Google LLC under the [Firebase terms and privacy information](https://firebase.google.com/support/privacy).

## What we (the developer) receive

MK IPTV has no developer server, no analytics, no advertising and no tracking. Synced data is stored in the app's Firebase project; the developer does not access or use it for any purpose other than keeping your devices in sync.

If the app crashes, it can offer to let you **share an error report**. It is only sent if you choose to share it, through the app you pick, and passwords and stream addresses are removed from it first.

## Children

MK IPTV is not directed at children. Kids profiles can hide adult categories, but the content shown comes from your own IPTV provider.

## Deleting your data

Remove a playlist or profile in the app's Settings, or uninstall the app, to delete all data it stored on your device. To delete synced data from the cloud, use **Settings › Sync across devices › Delete synced data**.

## Contact

Questions about this policy: open an issue at https://github.com/Mkuz1/MK-IPTV/issues
