"""Google Play publishing with the Play Developer API.
Usage: python3 tools/play_upload.py <aab> <track> <mode> [notes]
  track: internal | alpha (closed) | beta (open) | production
  mode:  validate (checks everything, changes nothing) | draft | release
       python3 tools/play_upload.py next-code            -> prints the next unused version code
       python3 tools/play_upload.py promote <from> <to> [notes]  -> copies the newest release of a track to another
Needs PLAY_SERVICE_ACCOUNT_JSON (the service-account key JSON) in the environment."""
import json, os, sys
from google.oauth2 import service_account
from googleapiclient.discovery import build
from googleapiclient.http import MediaFileUpload

PACKAGE = "com.mkuz.iptv"

creds = service_account.Credentials.from_service_account_info(
    json.loads(os.environ["PLAY_SERVICE_ACCOUNT_JSON"]), scopes=["https://www.googleapis.com/auth/androidpublisher"])
edits = build("androidpublisher", "v3", credentials=creds, cache_discovery=False).edits()

edit = edits.insert(packageName=PACKAGE, body={}).execute()["id"]

if sys.argv[1] == "next-code":
    codes = [b["versionCode"] for b in edits.bundles().list(packageName=PACKAGE, editId=edit).execute().get("bundles", [])]
    edits.delete(packageName=PACKAGE, editId=edit).execute()
    print(max(codes + [0]) + 1)
    sys.exit(0)

if sys.argv[1] == "promote":
    src, dst = sys.argv[2], sys.argv[3]
    notes = (sys.argv[4] if len(sys.argv) > 4 else "").strip()
    rel = edits.tracks().get(packageName=PACKAGE, editId=edit, track=src).execute().get("releases", [])
    rel = max(rel, key=lambda r: max(int(c) for c in r["versionCodes"]))
    new = {"name": rel.get("name"), "versionCodes": rel["versionCodes"], "status": "completed",
           "releaseNotes": [{"language": "en-US", "text": notes[:500]}] if notes else rel.get("releaseNotes", [])}
    edits.tracks().update(packageName=PACKAGE, editId=edit, track=dst, body={"track": dst, "releases": [new]}).execute()
    edits.commit(packageName=PACKAGE, editId=edit).execute()
    print(f"DONE: {rel.get('name')} (codes {rel['versionCodes']}) promoted from '{src}' to '{dst}'.")
    sys.exit(0)
for t in edits.tracks().list(packageName=PACKAGE, editId=edit).execute().get("tracks", []):
    for r in t.get("releases", []):
        print(f"Play now: {t['track']:<11} {r.get('status'):<10} {r.get('name', '')} codes={r.get('versionCodes')}")

aab, track, mode = sys.argv[1], sys.argv[2], sys.argv[3]
notes = (sys.argv[4] if len(sys.argv) > 4 else "").strip()
bundle = edits.bundles().upload(packageName=PACKAGE, editId=edit,
                                media_body=MediaFileUpload(aab, mimetype="application/octet-stream", resumable=True)).execute()
code = bundle["versionCode"]
print(f"Uploaded bundle: versionCode {code}")

release = {"versionCodes": [str(code)], "status": "draft" if mode == "draft" else "completed"}
if notes:
    release["releaseNotes"] = [{"language": "en-US", "text": notes[:500]}]
edits.tracks().update(packageName=PACKAGE, editId=edit, track=track, body={"track": track, "releases": [release]}).execute()

if mode == "validate":
    edits.validate(packageName=PACKAGE, editId=edit).execute()
    edits.delete(packageName=PACKAGE, editId=edit).execute()
    print(f"VALID: versionCode {code} could go to '{track}'. Nothing was changed on Google Play.")
else:
    edits.commit(packageName=PACKAGE, editId=edit).execute()
    print(f"DONE: versionCode {code} committed to '{track}' as {release['status']}.")
