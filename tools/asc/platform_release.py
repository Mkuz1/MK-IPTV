"""Creates / fills / submits an App Store version for another Apple platform of MK IPTV (Mac or Apple TV),
reusing the iPhone listing (description, keywords, URLs, review contact and notes).

Usage: platform_release.py <MAC_OS|TV_OS> <step> [screenshots_dir]
  step = prepare  : create the version if needed, copy the listing and review details, upload screenshots
         attach   : wait for the newest processed build of this platform and attach it
         submit   : submit the version for App Review
         all      : prepare + attach + submit
"""
import glob, hashlib, json, os, sys, time, urllib.request
sys.path.insert(0, os.path.dirname(__file__))
from ascapi import api

PLATFORM, STEP = sys.argv[1], sys.argv[2]
SHOTS = sys.argv[3] if len(sys.argv) > 3 else None
BUNDLE = os.environ.get("BUNDLE_ID", "com.mkuz.mkiptv")
VERSION = os.environ.get("VERSION", "1.7.1")
DISPLAY = {"MAC_OS": "APP_DESKTOP", "TV_OS": "APP_APPLE_TV", "IOS": "APP_IPHONE_67"}[PLATFORM]
NAME = {"MAC_OS": "Mac", "TV_OS": "Apple TV", "IOS": "iPhone and iPad"}[PLATFORM]

REVIEW_NOTE = f"""MK IPTV for {NAME} is a player for the user's own IPTV subscription (Xtream Codes API: server, username, password). It provides no content.

To review without a subscription: the app adds the playlist "MK IPTV Demo" on first launch. It is an ordinary Xtream account on our public demo server https://mk-iptv-demo.mk-uz02.workers.dev (username demo, password demo), handled exactly like any provider - nothing is simulated or hidden inside the app. It contains Al Quran Al Kareem TV (the public live stream of the Saudi Broadcasting Authority) and public-domain NASA films and series. You can also add it manually in Settings > Add playlist.

BACKGROUND AUDIO (UIBackgroundModes "audio", guideline 2.5.4): MK IPTV is a live TV and video player; people listen to live channels such as Al Quran Al Kareem TV with the screen off. To check it: open Live TV, play "Al Quran Al Kareem TV (Makkah Live)" full screen, then press Home or lock the device - the audio keeps playing and can be controlled from Control Center and the Lock Screen (Now Playing). The same background mode is also required for Picture in Picture (swipe home while a video plays full screen) and for AirPlay.

All features are visible to every user: Live TV, Movies, Series, Favorites, Search, Settings, and optional sync across devices (Sign in with Apple or a one-time link code; Delete synced data is in Settings > Sync across devices)."""


def ok(st, body, what):
    if st >= 300:
        raise SystemExit(f"{what} failed ({st}): {json.dumps(body)[:1500]}")
    return body


def app_id():
    st, b = api("GET", f"/v1/apps?filter[bundleId]={BUNDLE}")
    return ok(st, b, "app")["data"][0]["id"]


def versions(app, platform):
    st, b = api("GET", f"/v1/apps/{app}/appStoreVersions?filter[platform]={platform}&limit=10")
    return ok(st, b, "versions")["data"]


def ios_reference(app):
    """The iPhone version whose listing we copy: the newest one (in review or live)."""
    vs = versions(app, "IOS")
    return vs[0]


def get_or_create_version(app):
    for v in versions(app, PLATFORM):
        state = v["attributes"].get("appVersionState") or v["attributes"].get("appStoreState")
        if state not in ("READY_FOR_DISTRIBUTION", "READY_FOR_SALE", "REPLACED_WITH_NEW_VERSION", "REMOVED_FROM_SALE"):
            print(f"Using {PLATFORM} version {v['attributes']['versionString']} ({state})")
            return v
    st, b = api("POST", "/v1/appStoreVersions", {"data": {"type": "appStoreVersions",
        "attributes": {"platform": PLATFORM, "versionString": VERSION, "releaseType": "AFTER_APPROVAL"},
        "relationships": {"app": {"data": {"type": "apps", "id": app}}}}})
    v = ok(st, b, "create version")["data"]
    print(f"Created {PLATFORM} version {VERSION}")
    return v


def localizations(version_id):
    st, b = api("GET", f"/v1/appStoreVersions/{version_id}/appStoreVersionLocalizations?limit=50")
    return ok(st, b, "localizations")["data"]


def copy_listing(ref, ver):
    v_attrs = ref["attributes"]
    if v_attrs.get("copyright"):
        api("PATCH", f"/v1/appStoreVersions/{ver['id']}", {"data": {"type": "appStoreVersions", "id": ver["id"],
            "attributes": {"copyright": v_attrs["copyright"]}}})
    mine = {l["attributes"]["locale"]: l for l in localizations(ver["id"])}
    for src in localizations(ref["id"]):
        a = src["attributes"]
        attrs = {k: a.get(k) for k in ("description", "keywords", "marketingUrl", "promotionalText", "supportUrl") if a.get(k)}
        if "description" in attrs and PLATFORM == "TV_OS":
            attrs["description"] = attrs["description"].replace("iPhone and iPad", "Apple TV").replace("iPhone", "Apple TV")
        loc = a["locale"]
        if loc in mine:
            st, b = api("PATCH", f"/v1/appStoreVersionLocalizations/{mine[loc]['id']}",
                        {"data": {"type": "appStoreVersionLocalizations", "id": mine[loc]["id"], "attributes": attrs}})
        else:
            st, b = api("POST", "/v1/appStoreVersionLocalizations", {"data": {"type": "appStoreVersionLocalizations",
                "attributes": dict(attrs, locale=loc), "relationships": {"appStoreVersion": {"data": {"type": "appStoreVersions", "id": ver["id"]}}}}})
        ok(st, b, f"listing {loc}")
        print(f"Listing copied ({loc})")


def copy_review_detail(ref, ver):
    st, b = api("GET", f"/v1/appStoreVersions/{ref['id']}/appStoreReviewDetail")
    src = (b.get("data") or {}).get("attributes", {}) if st < 300 else {}
    attrs = {k: src.get(k) for k in ("contactFirstName", "contactLastName", "contactPhone", "contactEmail", "demoAccountName",
                                     "demoAccountPassword", "demoAccountRequired") if src.get(k) is not None}
    attrs["notes"] = REVIEW_NOTE
    st, b = api("GET", f"/v1/appStoreVersions/{ver['id']}/appStoreReviewDetail")
    cur = b.get("data") if st < 300 else None
    if cur:
        st, b = api("PATCH", f"/v1/appStoreReviewDetails/{cur['id']}", {"data": {"type": "appStoreReviewDetails", "id": cur["id"], "attributes": attrs}})
    else:
        st, b = api("POST", "/v1/appStoreReviewDetails", {"data": {"type": "appStoreReviewDetails", "attributes": attrs,
            "relationships": {"appStoreVersion": {"data": {"type": "appStoreVersions", "id": ver["id"]}}}}})
    ok(st, b, "review details")
    print("Review details set")


def upload_screenshots(ver, folder):
    files = sorted(glob.glob(os.path.join(folder, "*.png")))
    if not files:
        print(f"No screenshots in {folder}"); return
    for loc in localizations(ver["id"]):
        st, b = api("GET", f"/v1/appStoreVersionLocalizations/{loc['id']}/appScreenshotSets?limit=50")
        sets = {s["attributes"]["screenshotDisplayType"]: s for s in ok(st, b, "screenshot sets")["data"]}
        if DISPLAY in sets:
            sset = sets[DISPLAY]
            st, b = api("GET", f"/v1/appScreenshotSets/{sset['id']}/appScreenshots?limit=50")
            for shot in b.get("data", []):
                api("DELETE", f"/v1/appScreenshots/{shot['id']}")
        else:
            st, b = api("POST", "/v1/appScreenshotSets", {"data": {"type": "appScreenshotSets", "attributes": {"screenshotDisplayType": DISPLAY},
                "relationships": {"appStoreVersionLocalization": {"data": {"type": "appStoreVersionLocalizations", "id": loc["id"]}}}}})
            sset = ok(st, b, "create screenshot set")["data"]
        for f in files[:10]:
            data = open(f, "rb").read()
            st, b = api("POST", "/v1/appScreenshots", {"data": {"type": "appScreenshots",
                "attributes": {"fileName": os.path.basename(f), "fileSize": len(data)},
                "relationships": {"appScreenshotSet": {"data": {"type": "appScreenshotSets", "id": sset["id"]}}}}})
            shot = ok(st, b, "reserve screenshot")["data"]
            for op in shot["attributes"]["uploadOperations"]:
                chunk = data[op["offset"]:op["offset"] + op["length"]]
                req = urllib.request.Request(op["url"], data=chunk, method=op["method"],
                                             headers={h["name"]: h["value"] for h in op["requestHeaders"]})
                urllib.request.urlopen(req).read()
            st, b = api("PATCH", f"/v1/appScreenshots/{shot['id']}", {"data": {"type": "appScreenshots", "id": shot["id"],
                "attributes": {"uploaded": True, "sourceFileChecksum": hashlib.md5(data).hexdigest()}}})
            ok(st, b, "commit screenshot")
            print(f"Screenshot uploaded ({loc['attributes']['locale']}): {os.path.basename(f)}")


def attach_build(app, ver, wait_minutes=60):
    deadline = time.time() + wait_minutes * 60
    while True:
        st, b = api("GET", f"/v1/builds?filter[app]={app}&filter[preReleaseVersion.platform]={PLATFORM}"
                           f"&filter[preReleaseVersion.version]={ver['attributes']['versionString']}&sort=-uploadedDate&limit=1" + (f"&filter[version]={os.environ['BUILD_NUMBER']}" if os.environ.get("BUILD_NUMBER") else ""))
        builds = ok(st, b, "builds")["data"]
        want = os.environ.get("BUILD_NUMBER")   # wait for this exact build (a fresh upload takes a while to appear)
        if want and builds and builds[0]["attributes"]["version"] != want:
            builds = []
        if builds and builds[0]["attributes"]["processingState"] == "VALID":
            build = builds[0]; break
        state = builds[0]["attributes"]["processingState"] if builds else "not uploaded yet"
        if time.time() > deadline:
            raise SystemExit(f"No processed {PLATFORM} build ({state})")
        print(f"Waiting for build processing ({state})…"); time.sleep(60)
    if build["attributes"].get("usesNonExemptEncryption") is None:
        api("PATCH", f"/v1/builds/{build['id']}", {"data": {"type": "builds", "id": build["id"], "attributes": {"usesNonExemptEncryption": False}}})
    st, b = api("PATCH", f"/v1/appStoreVersions/{ver['id']}/relationships/build", {"data": {"type": "builds", "id": build["id"]}})
    ok(st, b, "attach build")
    print(f"Build {build['attributes']['version']} attached")


PRIVACY_TEXT = """MK IPTV is a player for your own IPTV service (Xtream Codes). It provides no content.

On your device the app stores the playlists you enter (the password in the Keychain), favorites, watch progress, settings and a cached copy of your provider's channel and movie lists.

The app connects only to the IPTV provider you configured (your username and password are sent to it to load channels, guides, artwork and video), to the TVs you choose for AirPlay, and, only if you turn on sync, to Google Firebase.

Sync is optional and off by default. When you turn it on (Sign in with Apple, Google, or a link code), a copy of your playlists, profiles, favorites, watch progress and settings is kept in Firebase so your own devices stay in sync. Playlist passwords are end-to-end encrypted on your device before upload. This data is not sold, shared, or used for advertising or analytics. Settings > Sync across devices > Delete synced data removes it.

MK IPTV has no ads, no analytics and no tracking. Full policy: https://github.com/Mkuz1/MK-IPTV/blob/claude/upbeat-allen-sin4my/docs/PRIVACY.md
Contact: mohamad@koussa.org"""


def ensure_privacy_text(app):
    """tvOS requires the privacy policy as text (App Information > Privacy Policy)."""
    st, b = api("GET", f"/v1/apps/{app}/appInfos?limit=10")
    for info in b.get("data", []):
        st, l = api("GET", f"/v1/appInfos/{info['id']}/appInfoLocalizations?limit=50")
        for loc in l.get("data", []):
            if not loc["attributes"].get("privacyPolicyText"):
                st, r = api("PATCH", f"/v1/appInfoLocalizations/{loc['id']}", {"data": {"type": "appInfoLocalizations", "id": loc["id"],
                    "attributes": {"privacyPolicyText": PRIVACY_TEXT}}})
                print(f"Privacy policy text ({loc['attributes']['locale']}, {info['attributes'].get('appStoreState') or info['attributes'].get('state')}): {'set' if st < 300 else 'not editable ' + str(st)}")


def submit(app, ver):
    ensure_privacy_text(app)
    st, b = api("GET", f"/v1/reviewSubmissions?filter[app]={app}&filter[platform]={PLATFORM}&limit=10")
    sub = next((s for s in b.get("data", []) if s["attributes"]["state"] in ("READY_FOR_REVIEW", "UNRESOLVED_ISSUES")), None)
    if not sub:
        st, b = api("POST", "/v1/reviewSubmissions", {"data": {"type": "reviewSubmissions", "attributes": {"platform": PLATFORM},
            "relationships": {"app": {"data": {"type": "apps", "id": app}}}}})
        sub = ok(st, b, "create review submission")["data"]
    st, b = api("POST", "/v1/reviewSubmissionItems", {"data": {"type": "reviewSubmissionItems",
        "relationships": {"reviewSubmission": {"data": {"type": "reviewSubmissions", "id": sub["id"]}},
                          "appStoreVersion": {"data": {"type": "appStoreVersions", "id": ver["id"]}}}}})
    if st >= 300:
        if sub["attributes"]["state"] == "UNRESOLVED_ISSUES":
            print("Version is already part of the rejected submission; resubmitting it")
        elif "already" not in json.dumps(b):
            ok(st, b, "add version to submission")
    st, b = api("PATCH", f"/v1/reviewSubmissions/{sub['id']}", {"data": {"type": "reviewSubmissions", "id": sub["id"], "attributes": {"submitted": True}}})
    ok(st, b, "submit")
    print(f"SUBMITTED {NAME} {ver['attributes']['versionString']} for App Review")


app = app_id()
ver = get_or_create_version(app)
if STEP in ("prepare", "all"):
    ref = ios_reference(app)
    if PLATFORM != "IOS":
        copy_listing(ref, ver)
    copy_review_detail(ref, ver)
    if SHOTS:
        upload_screenshots(ver, SHOTS)
if STEP in ("attach", "all"):
    attach_build(app, ver)
if STEP in ("submit", "all"):
    submit(app, ver)
