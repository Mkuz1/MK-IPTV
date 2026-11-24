"""Prints the App Store Connect state of MK IPTV: platforms, versions, review state, latest builds."""
import sys, os
sys.path.insert(0, os.path.dirname(__file__))
from ascapi import api

BUNDLE = os.environ.get("BUNDLE_ID", "com.mkuz.mkiptv")
st, apps = api("GET", f"/v1/apps?filter[bundleId]={BUNDLE}")
app = apps["data"][0]
print("App:", app["id"], app["attributes"]["name"], app["attributes"]["bundleId"], "primaryLocale", app["attributes"].get("primaryLocale"))
st, v = api("GET", f"/v1/apps/{app['id']}/appStoreVersions?limit=20&fields[appStoreVersions]=platform,versionString,appStoreState,appVersionState,createdDate")
for x in v.get("data", []):
    a = x["attributes"]
    print(f"Version {a['platform']:<8} {a['versionString']:<8} {a.get('appVersionState') or a.get('appStoreState')}  id={x['id']}")
st, b = api("GET", f"/v1/builds?filter[app]={app['id']}&sort=-uploadedDate&limit=8&fields[builds]=version,uploadedDate,processingState,preReleaseVersion&include=preReleaseVersion&fields[preReleaseVersions]=version,platform")
pre = {p["id"]: p["attributes"] for p in b.get("included", [])}
for x in b.get("data", []):
    pr = pre.get(x["relationships"]["preReleaseVersion"]["data"]["id"], {})
    print(f"Build {pr.get('platform','?'):<8} {pr.get('version','?'):<8} ({x['attributes']['version']}) {x['attributes']['processingState']} {x['attributes']['uploadedDate']}")
st, rs = api("GET", f"/v1/reviewSubmissions?filter[app]={app['id']}&limit=10")
for x in rs.get("data", []):
    print("ReviewSubmission", x["attributes"].get("platform"), x["attributes"].get("state"), x["attributes"].get("submittedDate"), x["id"])
st, bid = api("GET", f"/v1/bundleIds?filter[identifier]={BUNDLE}")
for x in bid.get("data", []):
    print("BundleId", x["attributes"]["identifier"], x["attributes"]["platform"], x["id"])
