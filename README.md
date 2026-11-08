# MK IPTV

An Android IPTV player for **Xtream Codes** playlists with a Netflix-style browsing experience and an Apple "liquid glass" inspired design.

## Features

- **Multiple Xtream playlists**: add as many accounts as you like (server URL, username, password). Shows account status, expiry date, connections and channel/movie/series counts. Passwords are encrypted with the Android Keystore.
- **Profiles ("Who's watching?")**: each profile has its own favorites (My List), watch history and Continue Watching. A profile can be limited to certain playlists. Kids profiles always hide adult categories.
- **Tabs**: Live TV · Movies · Series · Favorites · Downloads. Playlists are managed from the playlist button in every header ("Manage playlists…").
- **Continue Watching** (Movies and Series tabs): resumes exactly where you stopped, even after the app is closed, killed or the phone restarts. Progress is saved every 3 seconds and on every pause, seek and exit, using durable (fsync'd, atomic) writes. Series show the latest episode, or **Up next** once it's finished. Long-press a card to remove it or mark it watched.
- **Watched marks**: finished episodes (credits reached) show a green **WATCHED** badge, and partly watched ones show a progress bar with the time left. Long-press an episode to mark it watched or unwatched, or use "Mark season watched".
- **Favorites**: one place for your My List movies and series and your favorite live channels.
- **Movies & Series tabs**: a hero banner plus Netflix-style rows (Continue Watching, My List, Recently Added, Top Rated / Top 10, every genre). Tapping a title opens a glass **quick preview** with the poster, year, runtime, age rating, rating, genre, description, cast and director, plus Play / Trailer / My List / Download.
- **Series detail**: seasons, episodes with thumbnails and summaries, resume where you left off, auto-play of the next episode.
- **Live TV**: inline live preview, categories and favorites, now/next TV guide with progress bars, channel up/down in the player.
- **Downloads**: download movies, single episodes or a whole season and watch fully offline.
  - The Downloads page lists every title with its **thumbnail, title, size and status** (downloading %, paused, failed, ready offline).
  - Opening a series shows **all downloaded episodes**, grouped by season, with play / delete for each.
  - Downloads keep running in the background (with a notification) and can be paused, resumed, retried or deleted.
- **Casting**:
  - **Google Cast** (Chromecast, Google TV, Cast-enabled TVs): the cast button in every header and in the player. Playback moves to the TV and back seamlessly.
  - **AirPlay** (Apple TV, AirPlay-enabled TVs): the AirPlay button in the player finds receivers on your Wi-Fi and sends the stream, with play/pause/seek/stop from the phone.
- **Player**: liquid-glass controls, ±10s, seek bar, playback speed, screen lock, audio & subtitle track selection, aspect ratio (fit/zoom/fill), picture-in-picture, resume position, automatic HLS → MPEG-TS fallback for live channels.
- **Search** across movies, series and channels.

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

- **No secrets in the app**: there are no API keys, tokens or backend servers. The app talks only to the Xtream server you enter (plus TVs you choose to cast to).
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

## Building

Requirements: JDK 17+ and the Android SDK (platform 35, build-tools 35).

```bash
./gradlew :app:assembleRelease      # signed if keystore.properties exists, unsigned otherwise
./gradlew :app:testDebugUnitTest    # API/AirPlay tests + full UI flow screenshots in app/build/shots
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
  player/      PlayerActivity (ExoPlayer / CastPlayer / AirPlay) + glass controls
  ui/          Compose screens and liquid-glass components
concept/       Original HTML design concept
```
