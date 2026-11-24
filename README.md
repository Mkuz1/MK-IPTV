# MK IPTV

An IPTV player for **Xtream Codes** playlists on **Android phones and tablets, Google TV / Android TV, iPhone, iPad
and Apple Silicon Macs**, with a streaming-style browsing experience and an Apple "liquid glass" inspired design.
MK IPTV provides no content of its own: it plays the user's own IPTV service.

| Platform | Store | Source |
|---|---|---|
| Android phone, tablet, Google TV | Google Play (`com.mkuz.iptv`) | `app/` (Kotlin, Jetpack Compose, Media3) |
| iPhone, iPad, Mac (Apple Silicon) | App Store (`com.mkuz.mkiptv`) | `ios/` (SwiftUI, AVKit) |
| Public demo server | `https://mk-iptv-demo.mk-uz02.workers.dev` | `tools/demo-server/` (Cloudflare Worker) |

Builds are also attached to [GitHub Releases](https://github.com/Mkuz1/MK-IPTV/releases) (`android-v*`: APK + AAB, `ios-v*`: IPA for sideloading).

## Built-in demo (try it without a subscription)

On first launch both apps add the playlist **MK IPTV Demo** (removable like any playlist). It is an ordinary
Xtream Codes account on MK IPTV's public demo server, handled exactly like a user's own provider:

- Server `https://mk-iptv-demo.mk-uz02.workers.dev`, username `demo`, password `demo`.
- **Live TV**: Al Quran Al Kareem TV (Makkah Live), the public live stream of the Saudi Broadcasting Authority.
- **Movies**: four public-domain NASA films (Earth from space). **Series**: *NASA Science Live* (4 episodes).
- The server only answers the standard `player_api.php` API and redirects streams to their public sources; it hosts
  no video. Deploy changes with `cd tools/demo-server && npx wrangler@3 deploy`.
- Playlists created by older versions (server `http://demo`) are moved to the real server automatically.

## Android features (phone, tablet, Google TV)

- **Multiple Xtream playlists**: add as many accounts as you like (server URL, username, password). Shows account status, expiry date, connections and channel/movie/series counts. Passwords are encrypted with the Android Keystore.
- **Profiles ("Who's watching?")**: each profile has its own favorites (My List), watch history and Continue Watching. A profile can be limited to certain playlists. Kids profiles always hide adult categories.
- **Tabs**: Live TV · Movies · Series · Favorites · Downloads. Playlists, profiles, playback options and app updates live in **Settings** (playlist button in any header → "Settings & playlists…", or Settings in the TV menu).
- **Continue Watching** (Movies and Series tabs): resumes exactly where you stopped, even after the app is closed, killed or the phone restarts. Progress is saved every 3 seconds and on every pause, seek and exit, using durable (fsync'd, atomic) writes. Series show the latest episode, or **Up next** once it's finished. Long-press a card to remove it or mark it watched.
- **Watched marks**: finished episodes (credits reached) show a green **WATCHED** badge, and partly watched ones show a progress bar with the time left. Long-press an episode to mark it watched or unwatched, or use "Mark season watched".
- **Favorites**: one place for your My List movies and series and your favorite live channels.
- **Movies & Series tabs**: a hero banner plus streaming-style rows (Continue Watching, My List, Recently Added, Top Rated / Top 10, every genre). Tapping a title opens a glass **quick preview** with the poster, year, runtime, age rating, rating, genre, description, cast and director, plus Play / Trailer / My List / Download.
- **Series detail**: seasons, episodes with thumbnails and summaries, resume where you left off, auto-play of the next episode.
- **Live TV**: Mac Finder-style columns.
  - The left column holds "All channels", "Favorites" and every category with its channel count, plus a filter box.
  - Picking a category opens its channels in the right column, with now/next guide, progress bars, ♡ favorite and a channel filter box.
  - Wide screens add a third column with the live preview and a large now/next panel.
  - Short windows (car head units, split-screen) skip the inline preview, and a tap opens the channel full screen.
  - The last category is remembered. On TV, hold OK on a channel to (un)favorite it.
- **Refresh library**: ⟳ button in every main header (and in the TV menu) re-downloads channels, movies and series from the provider while you keep browsing.
- **Downloads**: download movies, single episodes or a whole season and watch fully offline.
  - The Downloads page lists every title with its **thumbnail, title, size and status** (downloading %, paused, failed, ready offline).
  - Opening a series shows **all downloaded episodes**, grouped by season, with play / delete for each.
  - Downloads keep running in the background (with a notification) and can be paused, resumed, retried or deleted.
- **Casting**:
  - **Google Cast** (Chromecast, Google TV, Cast-enabled TVs): the cast button in every header and in the player. Playback moves to the TV and back seamlessly.
  - **AirPlay** (Apple TV, AirPlay-enabled TVs): the AirPlay button in the player finds receivers on your Wi-Fi and sends the stream, with play/pause/seek/stop from the phone.
- **Player**: liquid-glass controls, ±10s, seek bar, playback speed, screen lock, audio & subtitle track selection, aspect ratio (fit/zoom/fill), picture-in-picture, resume position, automatic HLS → MPEG-TS fallback for live channels.
- **Search** across movies, series and channels.

## iPhone, iPad and Mac features

A native SwiftUI app (`ios/`, iOS 17+). On Apple Silicon Macs it runs as the iPad app.

- **Live TV**: category list, channel search, and a **muted live preview** of the selected channel with an unmute button (iPad: preview plus a large now/next panel). Tap the selected channel again for full screen.
- **Movies & Series**: hero banner, Continue Watching (long-press to remove), My List, Recently Added, Top Rated and every category; **search** in both tabs (results as a poster grid, best matches first).
- **Quick previews**: movie preview with Play / My List / Download; series preview sheet with *Episodes & Play*, My List, Download, Seasons and Details.
- **Series pages**: seasons, episodes with thumbnails, resume and up-next.
- **Favorites** and **Downloads** (movies and episodes, watched offline).
- **Player** (AVPlayerViewController): picture-in-picture, subtitles/audio tracks, speed, resume, next episode, full-screen landscape button (hidden on Mac), fast start (plays as soon as media arrives, keyframe-tolerant resume).
- **AirPlay** (Apple TV, AirPlay 2 TVs) and **Chromecast** (official Google Cast SDK, Default Media Receiver): buttons on Live TV, Movies, Series and in the player. The Cast button is always visible; tapping it searches the Wi-Fi (local-network permission) and opens the device list. Casting hands over the current title and position, and playback returns to the phone when you disconnect.
- **Sync** with Sign in with Apple, Google or a link code; the iPhone follows one synced profile (chosen in Settings).
- Passwords live in the iOS Keychain (this device only); app data is excluded from backups.

## Sync across devices

Settings › **Sync across devices** keeps playlists, profiles, favorites, Continue Watching, watched episodes and
preferences identical on Android phones/tablets, Google TV and iPhone. Sign in with Google (also on Google TV) or
Apple, or link devices with a one-time 8-character code (no account needed). Changes upload a few seconds after they
happen and arrive on the other devices live; the newest change wins. **Sync now** forces a full upload and download,
and sync reconnects by itself after network errors. *Delete synced data* removes the cloud copy.

- **End-to-end encrypted passwords**: playlist passwords are sealed on the device with a library key (AES-256-GCM)
  before upload. Each device has its own P-256 key pair; the library key is wrapped for every linked device
  (ECDH + HKDF-SHA256), so Firebase never sees a password. Android and iPhone interoperate.
- **Firestore rules** (`firestore.rules`) allow only members of a library to read or write it, require a valid,
  unexpired link code to join someone else's library, and accept only the exact fields the apps write, with size limits.
- **Firebase App Check** (Play Integrity on Android, App Attest on Apple devices) is installed and registered; it runs
  in monitoring mode until most installs send verified requests, then enforcement can be turned on for Firestore.

Firebase setup: [docs/SYNC_SETUP.md](docs/SYNC_SETUP.md).

## One app for phone, tablet and Google TV

A single universal APK detects the device and adapts:

- **Phone**: glass bottom tab bar, streaming-style rows, quick-preview sheets.
- **Tablet** (≥ 840 dp, e.g. tablets in landscape and unfolded foldables): floating glass **navigation rail**, larger posters, wide hero with the full poster, and two-pane Live TV and series pages.
- **Google TV / Android TV**: a dedicated 10-foot interface for the remote.
  - **Left menu**: Search, Live TV, Movies, Series, Favorites, Downloads, Settings.
  - **Movies and Series**: rows with a large hero that shows the focused title's backdrop, rating and description.
  - **Detail pages**: full movie and series pages, with horizontal episode rows and watched badges.
  - **Live TV**: a channel guide whose preview follows focus.
  - **Remote in the player**:
    - OK brings up the controls (focus on Play/Pause) without pausing; a second OK pauses.
    - ◀ ▶ scrub through a movie (10 s steps, 30 s then 60 s while held); the seek happens when you let go or press OK. The time and seek bar follow as you scrub.
    - On the seek bar (▼ from the buttons) ◀ ▶ scrub the same way; the bar shows a large thumb with an accent ring when focused.
    - +10 / −10 always move the right way (exact seeking) and keep focus when pressed again. Controls stay up 8 s after the last press and never hide while scrubbing.
    - ▲ ▼ change channel on live TV, ⏩ ⏪ seek 30 s, Channel ± and Next/Previous supported; Back hides the controls first.
  - **Sign in with Google** for sync works on Google TV (Credential Manager); TVs without it are linked with a code from the phone.
  - The app shows up on the Google TV home screen with its own banner. Cast/AirPlay/PiP are hidden on TV.

## In-app updates (all devices)

The app checks an update feed (`version.json`) at start-up (at most every 6 h) and from **Settings → App updates → Check now**. When a newer version is live it shows the release notes and installs it in-app:

1. Downloads the APK over HTTPS with progress.
2. Verifies the SHA-256 checksum from the feed.
3. Verifies the APK is signed with the **same MK IPTV certificate** as the installed app (and Android enforces this again).
4. Installs it with Android's PackageInstaller. The first time, Android asks you to allow "Install unknown apps" for MK IPTV.

### Publishing a release (one click)

After the one-time setup below, open **Actions → Publish release → Run workflow** in this repo, type the release notes and run it. The workflow tests, builds and signs the APK, writes `version.json` and publishes both to `mkuz1/mk-iptv-releases`. Bump `versionCode`/`versionName` first.

One-time setup:
1. Create the **public** repo `mkuz1/mk-iptv-releases` and add `tools/releases-repo/README.md` as its README.
2. In this repo's *Settings → Secrets and variables → Actions*, add `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_PASSWORD` and `RELEASES_TOKEN` (see the top of `.github/workflows/publish-release.yml`).

### Publishing a release manually

The feed must be publicly reachable. The default is
`https://github.com/mkuz1/mk-iptv-releases/releases/latest/download/version.json`
(you can change it under Settings → App updates → Update feed).

1. Create a **public** GitHub repo named `mk-iptv-releases` (it only holds release files, no code).
2. Bump `versionCode` / `versionName` in `app/build.gradle.kts`.
3. Run `tools/make-release.sh "What's new…"` → produces `dist/MK-IPTV-<version>.apk` and `dist/version.json`.
4. In `mk-iptv-releases`, create a release with tag `v<version>` and upload **both** files.

Every installed copy (phone, tablet, TV) will offer the update.

## Streaming performance

- **Instant start-up**: the catalog is cached on disk (gzip). The app opens straight into your library and refreshes it silently in the background.
- **Fast playback start**: playback begins after ~1 s of buffer (2.5 s after a stall), with a large buffer behind it. Low-RAM TV boxes get a memory-capped buffer.
- **Right quality from the first second**: one bandwidth estimate is shared across all playback.
- **Fast channel switching**: live TV uses MPEG-TS first ("Fastest", configurable in Settings → Playback), starts without waiting for a keyframe, and falls back to HLS automatically (full-screen player, phone preview and Google TV preview). Cast always uses HLS.
- **One stream at a time**: the muted live previews release their stream when you leave Live TV or open a channel full screen, so providers that allow a single connection start the next title immediately.
- **Stall watchdog**: if a title hasn't started after 15 s, the player tries the other live format, then shows a Retry message instead of a black screen.
- **Connection reuse**: one tuned HTTP client (connection pool, more parallel requests, 10 s connect timeout) is shared by API calls, artwork and video, so streams reuse warm connections.
- **Resilient decoding**: automatic fallback to a software decoder if a hardware decoder fails. Exact seeking. Tunneled playback is off (it stalls on many TV boxes with IPTV streams).

## Split-screen, car screens & free-form windows

- Every screen is resizable and handles size changes itself, so dragging the split-screen divider never restarts the app or interrupts playback.
- In multi-window the player follows the window (no forced landscape).
- Playback continues while you use the other app in the split. It pauses only when the player is fully hidden.
- Layouts are driven by the **window** size:
  - The hero banner never takes more than ~¾ of the window height.
  - Sheets and dialogs scroll in short windows.
  - The player uses compact controls below 380 dp of height.
- Tested sizes: full car head unit (1024×600), half of it in split-screen (512×600) and a short top/bottom split (1024×300).
- Works on Android head units that run regular Android. **Android Auto** (phone projection) does not run third-party video apps, and built-in **Android Automotive** cars restrict video while driving.

## Foldables, tablets & large screens

- Layouts adapt to the window size, re-evaluated live when the device is folded or unfolded or the window is resized:
  - **Live TV**: the player plus a large now/next guide on the left, the channel list on the right.
  - **Series**: details on the left, seasons and episodes on the right.
  - **Downloads, downloaded episodes and Playlists**: multi-column grids.
  - **Movies/Series hero**: the full poster beside the details.
  - **Tab bar and editors**: kept at a comfortable width.
- **No restart when folding or unfolding**: playback, scroll position and the current tab are kept.
- **Tabletop mode**: when the phone is half-folded with a horizontal hinge, the player shows the video above the fold and the controls below it.
- Regular phones open videos in landscape. Foldables, tablets and multi-window follow the device orientation so tabletop and book postures work.

## Security & privacy

- **No secrets in the app**: there are no private API keys or tokens in the app. It talks to the Xtream server you enter, TVs you choose to cast to, and (only if you turn on sync) Firebase, where passwords are end-to-end encrypted (see *Sync across devices*).
- **Passwords** are encrypted with an AES-256-GCM key held in the Android Keystore. On a device where the Keystore is broken (rare), the password is stored only base64-encoded, inside the app's private storage.
- **Downloads** store a credential-free internal address (`mkiptv://…`); the real URL is built only at request time, so passwords are never written to the download database (verified by a test).
- **No backup / device transfer** of app data (playlists, history and downloads stay on the device).
- **No logging** of stream URLs in release builds; HTTPS trusts only system certificate authorities.
- Only the launcher screen is exported; the player and download service are private to the app.

What you should know:

- The **Xtream Codes protocol puts your username and password in every request URL**, and many providers only support **plain HTTP**. Anyone who can watch your network traffic (e.g. on public Wi-Fi) can see them. This is a limitation of Xtream itself; use your provider's `https://` address if it has one.
- When you **cast or AirPlay**, the stream URL (including the credentials) is handed to the TV, because the TV streams directly from your provider. Only cast to devices you trust.

## Notes and limitations

- **AirPlay** uses the AirPlay video protocol. If the Apple TV asks for a code or password, set *Settings → AirPlay → Allow Access* to **Everyone** (or "Anyone on the same network") and turn off **Require Password**. Receivers that demand device pairing will show a clear message instead of playing. Android has no system AirPlay support, so this is built into the app.
- **Casting** sends the provider's stream URL to the TV, so the TV needs internet access to your IPTV server. Downloaded files are cast from the server, not from the phone. Chromecast supports MP4/HLS best; MKV may not play on some Chromecast models.
- Google Cast needs Google Play services. On devices without them the cast button simply finds no devices.

## Google Play build

There are two build variants:

- **direct**: the sideloaded APK with the in-app updater (GitHub releases).
- **play**: an Android App Bundle for Google Play. Play policy does not allow apps to update themselves, so this variant has no updater, no `REQUEST_INSTALL_PACKAGES` permission and no Settings → App updates section. Play delivers the updates.

`tools/make-release.sh` produces both: `dist/MK-IPTV-<version>.apk` and `dist/MK-IPTV-<version>-play.aab`. Upload the `.aab` in Play Console. With Play App Signing, the MK IPTV key acts as the **upload key**.

## Building (iPhone / iPad / Mac)

Requirements: Xcode 26+ and [XcodeGen](https://github.com/yonaskolb/XcodeGen). `ios/MKIPTV/GoogleService-Info.plist` comes from the Firebase console.

```bash
cd ios && xcodegen generate
xcodebuild archive -project MKIPTV.xcodeproj -scheme MKIPTV -destination 'generic/platform=iOS' \
  -archivePath build/MKIPTV.xcarchive -allowProvisioningUpdates DEVELOPMENT_TEAM=<team> CURRENT_PROJECT_VERSION=<build>
```

Swift packages: Firebase (Auth, Firestore, App Check), GoogleSignIn, Google Cast (`GoogleCastStatic`, needs `-ObjC -lc++`).
`.github/workflows/ios-appstore.yml` builds and uploads to App Store Connect; `tools/asc/ascapi.py` is a small
App Store Connect API helper (uses a local API key, never committed).

## Building (Android)

Requirements: JDK 17+ and the Android SDK (platform 35, build-tools 35).

```bash
./gradlew :app:assembleDirectRelease  # sideload APK (signed if keystore.properties exists)
./gradlew :app:bundlePlayRelease     # Google Play App Bundle
./gradlew :app:testDirectDebugUnitTest  # API/AirPlay tests + full UI flow screenshots in app/build/shots
```

To sign a release build, create `keystore.properties` in the project root (git-ignored):

```
storeFile=keystore/mk-iptv-release.jks
storePassword=…
keyAlias=mkiptv
keyPassword=…
```

Keep that keystore safe: future updates must be signed with the same key to install over an existing install (otherwise Android requires an uninstall, which deletes downloads).

## Tests

- `XtreamApiTest`: the Xtream client against payloads with the quirks real panels have (numbers as strings, nulls, object-keyed seasons, base64 EPG), URL normalisation, and the AirPlay HTTP requests.
- `AppFlowScreenshotTest`: drives the real app (Robolectric) against a fake Xtream panel: profiles → movies → preview → series → **real downloads through the download manager** → Downloads page → downloaded episodes → Live TV → playlists → add playlist → player. Screenshots are saved to `app/build/shots` (copies in `docs/screenshots`).

## Project layout

```
app/src/main/java/com/mk/iptv/
  data/        Xtream API client, models, catalog cache, local store, keystore crypto
  download/    Media3 DownloadManager wrapper + foreground download service
  cast/        Google Cast options + route button
  airplay/     Bonjour discovery + AirPlay video session
  player/      PlayerActivity (ExoPlayer / CastPlayer / AirPlay) + glass controls, TV remote scrubbing
  sync/        Firebase sync engine, end-to-end encryption (E2e.kt)
  ui/          Compose screens (ui/tv for Google TV) and liquid-glass components
ios/           iPhone / iPad / Mac app (SwiftUI): App, Browse, Live/Other views, Player, Cast, Downloads, Sync, E2e
tools/demo-server/  Public demo Xtream server (Cloudflare Worker)
tools/asc/     App Store Connect API helper
tools/store-graphics/, store-assets/, docs/store-graphics/  Store listing artwork and the templates it is rendered from
firestore.rules  Firestore security rules for sync
concept/       Original HTML design concept
```
