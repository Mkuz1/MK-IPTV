"""Minimal App Store Connect API helper: api(method, path, body) -> (status, json). ES256 JWT signed with openssl."""
import base64, json, os, subprocess, tempfile, time, urllib.request, urllib.error

KEY_ID = os.environ.get("ASC_KEY_ID", "3THDD8249C")
ISSUER = os.environ.get("ASC_ISSUER_ID", "2e8d4101-d7ef-4e83-8b99-c805c5d2788f")
KEY_PATH = os.environ.get("ASC_KEY_PATH", os.path.expanduser("~/keys/AuthKey_3THDD8249C.p8"))

def _b64(b): return base64.urlsafe_b64encode(b).rstrip(b"=")

def _der_to_raw(der):
    # ECDSA DER (r,s) -> 64-byte raw
    assert der[0] == 0x30
    i = 2 if der[1] < 0x80 else 3
    out = b""
    for _ in range(2):
        assert der[i] == 0x02; ln = der[i + 1]; v = der[i + 2:i + 2 + ln]; i += 2 + ln
        out += v.lstrip(b"\x00").rjust(32, b"\x00")
    return out

def token():
    now = int(time.time())
    h = _b64(json.dumps({"alg": "ES256", "kid": KEY_ID, "typ": "JWT"}).encode())
    p = _b64(json.dumps({"iss": ISSUER, "iat": now, "exp": now + 1100, "aud": "appstoreconnect-v1"}).encode())
    msg = h + b"." + p
    with tempfile.NamedTemporaryFile() as f:
        f.write(msg); f.flush()
        der = subprocess.run(["openssl", "dgst", "-sha256", "-sign", KEY_PATH, f.name], capture_output=True, check=True).stdout
    return (msg + b"." + _b64(_der_to_raw(der))).decode()

def api(method, path, body=None):
    url = path if path.startswith("http") else "https://api.appstoreconnect.apple.com" + path
    req = urllib.request.Request(url, method=method, data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Authorization": "Bearer " + token(), "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req) as r:
            raw = r.read(); return r.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        raw = e.read(); return e.code, (json.loads(raw) if raw else {})
