# Account sync – Firebase setup

MK IPTV syncs playlists, profiles, favorites, Continue Watching, watched marks and preferences between
Android phones/tablets, Google TV and iPhone through Firebase (Auth + Cloud Firestore).

Firebase project: `mk-iptv-eb526` · Android package `com.mkuz.iptv` · iOS bundle id `com.mkuz.mkiptv`.

## 1. Firestore
1. Firebase console › **Firestore Database** › Create database (production mode, any region).
2. **Rules** tab › paste [`firestore.rules`](../firestore.rules) › Publish.

## 2. Sign-in providers (Authentication › Sign-in method)
| Provider | Needed for | Extra steps |
|---|---|---|
| **Anonymous** | link codes (no account) – required | none |
| **Google** | "Continue with Google" | Android: add the SHA-1 of the release key **and** of Play App Signing (Play Console › Setup › App signing) in Project settings › Your apps › Android. Then download both config files again (they now contain the OAuth client IDs). |
| **Apple** | "Sign in with Apple" on iPhone | enable it; the App ID needs the *Sign in with Apple* capability (automatic signing in CI adds it). |

## 3. Config files (kept out of Git)
- `app/google-services.json` and `ios/MKIPTV/GoogleService-Info.plist` for local builds.
- For CI add repository secrets (Settings › Secrets and variables › Actions), paste the whole file:
  - `GOOGLE_SERVICES_JSON` – used by *Publish release* (Android)
  - `GOOGLE_SERVICE_INFO_PLIST` – used by *iOS build* and *iOS App Store upload*

Without them the apps build normally and show "Sync isn't available in this build".

## How linking works
- **Google / Apple sign-in**: every device signed in to the same account opens the same library.
- **Link code**: on a synced device tap *Link another device* (or *Get a code* to start without an account);
  enter the 8-character code on the new device within 15 minutes. Works on Google TV and iPhone too.
- On first link, playlists with the same server + username and profiles with the same name are merged
  instead of duplicated. Afterwards the newest change wins.
- The iPhone app follows one profile (picker in Settings when there are several).
