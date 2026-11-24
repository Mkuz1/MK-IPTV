"""Uploads an Android App Bundle to Google Play with the Play Developer API.
Usage: python3 tools/play_upload.py <aab> <track> <mode> [notes]
  track: internal | alpha (closed) | beta (open) | production
  mode:  validate (checks everything, changes nothing) | draft | release
Needs PLAY_SERVICE_ACCOUNT_JSON (the service-account key JSON) in the environment."""
import json, os, sys
from google.oauth2 import service_account
from googleapiclient.discovery import build
from googleapiclient.http import MediaFileUpload

PACKAGE = "com.mkuz.iptv"
aab, track, mode = sys.argv[1], sys.argv[2], sys.argv[3]
notes = (sys.argv[4] if len(sys.argv) > 4 else "").strip()

creds = service_account.Credentials.from_service_account_info(
    json.loads(os.environ["PLAY_SERVICE_ACCOUNT_JSON"]), scopes=["https://www.googleapis.com/auth/androidpublisher"])
edits = build("androidpublisher", "v3", credentials=creds, cache_discovery=False).edits()

edit = edits.insert(packageName=PACKAGE, body={}).execute()["id"]
for t in edits.tracks().list(packageName=PACKAGE, editId=edit).execute().get("tracks", []):
    for r in t.get("releases", []):
        print(f"Play now: {t['track']:<11} {r.get('status'):<10} {r.get('name', '')} codes={r.get('versionCodes')}")

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
