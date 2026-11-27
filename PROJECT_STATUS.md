# MK IPTV: project status

_Source of truth for this app's claude.ai chat. As of 2026-11-27._

IPTV player for the user's own Xtream Codes playlists, streaming-style UI, liquid glass design. Ships with a public demo playlist.

## Current state

| | |
|---|---|
| Versions | Android **1.7.9** (2026-11-25); Apple (iOS/iPadOS/Mac Catalyst/tvOS) marketing version **1.7.1** |
| Platforms | Android phone/tablet, Google TV / Android TV, iPhone, iPad, Mac (Catalyst), **Apple TV** |
| App Store | **Live**: id6836639475 (`com.mkuz.mkiptv`), iPhone, iPad, Mac and **Apple TV** (tvOS released 2026-11-26) |
| Google Play | `com.mkuz.iptv` is **not live** on Play (as of 2026-11-26 only Mushaf is on Play); Android is APK only |
| Downloads | GitHub releases in this repo (`android-v*`: APK + AAB + version.json; `ios-v*`: IPA). `Mkuz1/mk-iptv-releases` holds the privacy policy and delete-account pages |
| Website | **https://mkiptv.pages.dev** (`site/`, deploys via `site-deploy.yml`) |
| Default / deploy branch | **`claude/upbeat-allen-sin4my`** (not main) |
| Demo server | https://mk-iptv-demo.mk-uz02.workers.dev (`tools/demo-server/`) |

## Recent changes

- 2026-11-26: Site shows the live Apple TV App Store badge (PR #2); koussa.net invite (PR #1); SEO/share previews (PR #3); site moved to Cloudflare Pages with auto-deploy.
- 2026-11-25: Android 1.7.9, Google TV player keeps remote focus on the controls.
- 2026-11-24: iPhone background audio fix for App Review 2.5.4; Apple TV and Mac App Store screenshots; ASC tooling.

## Open items

- Confirm the App Store listing now shows tvOS (it lagged on 2026-11-26).
- Google Play listing if wanted (`play-upload.yml` exists).

## Rules for every MK app (decided in the MK APPS project)

- **This file is the hand-off to the app's own Claude chat.** That chat reads it from GitHub through Project knowledge. Every change to this app (code, release, store, website, decision) must also update this file in the same PR or commit.
- **Nothing local-only.** Every asset, store file and site file lives in the app's GitHub repo. Signing keys and passwords are the only exception and are never committed (they stay in `~/keys` on the Mac).
- **Websites deploy themselves** to Cloudflare Pages from GitHub Actions on push (secrets `CLOUDFLARE_API_TOKEN` / `CLOUDFLARE_ACCOUNT_ID` are set on the repo). No manual or terminal deploys.
- **Every app site invites visitors to https://www.koussa.net** (the MK Apps showcase, source in `Mkuz1/MK-APPS`, served by Cloudflare project `mkuzapps`). Keep that card when editing the site, and update the app's row in `MK-APPS/build.py` when platforms, versions or store links change.
- **SEO and share previews (done 2026-11-26):** keep the OG + Twitter tags with an absolute 1200x630 image under 300 KB, canonical, favicon.ico + 192/512 icons + 180 px touch icon, web manifest, robots.txt, sitemap.xml and SoftwareApplication JSON-LD. When replacing a share image, give it a new file name so WhatsApp refreshes its cache.
- **Store badges only for what is really live.** Re-check the App Store / Play page before adding a badge.
- **Mohamad merges pull requests himself** (Claude Code's auto mode on the Mac blocks `gh pr merge`).

---
_Last updated 2026-11-27. Update this file with every change to the app._
