"""Builds site/index.html from site/src/index.html: inlines Phosphor icons ({{name}}) and the FAQ schema.
Run: python3 site/build.py   (icons are read from site/src/icons.json)"""
import html, json, os, re
here = os.path.dirname(os.path.abspath(__file__))
src = open(os.path.join(here, "src", "index.html")).read()
icons = json.load(open(os.path.join(here, "src", "icons.json")))

faq = re.findall(r'<summary class="title-2"><span>(.*?)</span>.*?</summary><p class="answer[^"]*">(.*?)</p>', src, re.S)
schema = {"@context": "https://schema.org", "@type": "FAQPage", "mainEntity": [
    {"@type": "Question", "name": html.unescape(q), "acceptedAnswer": {"@type": "Answer", "text": html.unescape(a)}} for q, a in faq]}
src = src.replace("{{faq-schema}}", json.dumps(schema, ensure_ascii=False))

def icon(m):
    name = m.group(1)
    return f'<svg viewBox="0 0 256 256" fill="currentColor" aria-hidden="true">{icons[name]}</svg>'
out = re.sub(r"\{\{([a-z-]+)\}\}", icon, src)
assert "{{" not in out, re.findall(r"\{\{[^}]*\}\}", out)
open(os.path.join(here, "index.html"), "w").write(out)
print(f"site/index.html: {len(out)//1024} KB, {len(faq)} FAQ entries")

# Stand-alone website (Cloudflare Pages): full HTML documents, absolute social links, 404 page.
SITE = os.environ.get("SITE_URL", "https://mkiptv.pages.dev")
HEAD = '<!doctype html>\n<html lang="en">\n<head>\n<meta charset="utf-8">\n<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">\n'
dist = os.path.join(here, "dist")
os.makedirs(dist, exist_ok=True)
page = out  # share tags in src/index.html are already absolute (https://mkiptv.pages.dev)
style = page[page.index("<style>"):page.index("</style>") + 8]
open(os.path.join(dist, "index.html"), "w").write(HEAD + page.replace("<a class=\"skip", "</head>\n<body>\n<a class=\"skip", 1) + "\n</body>\n</html>\n")
nf = (HEAD + '<title>Page not found · MK IPTV</title>\n<meta name="robots" content="noindex">\n<link rel="icon" href="/favicon.ico" sizes="48x48">\n<link rel="apple-touch-icon" href="/assets/apple-touch-icon.png">\n'
      '<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Geist:wght@400;500;600&display=swap">\n' + style +
      '\n<style>.nf{min-height:100vh;display:grid;place-items:center;text-align:center;padding-block:96px}.nf h1{background:linear-gradient(90deg,#FFFFFF,#9B9B9B);-webkit-background-clip:text;background-clip:text;color:transparent}</style>\n</head>\n<body>\n'
      '<main class="nf wrap"><div style="display:grid;gap:24px;justify-items:center">'
      '<img src="/assets/icon-192.png" alt="MK IPTV" width="64" height="64" style="border-radius:16px">'
      '<p class="eyebrow caption-3">Error 404</p><h1 class="heading-3">This page is off the air</h1>'
      '<p class="body-3 muted" style="max-width:480px">The address may have changed or never existed. Everything about MK IPTV is on the home page.</p>'
      '<a class="btn btn-primary button-md" href="/">Back to MK IPTV</a></div></main>\n</body>\n</html>\n')
open(os.path.join(dist, "404.html"), "w").write(nf)
import shutil
shutil.copytree(os.path.join(here, "assets"), os.path.join(dist, "assets"), dirs_exist_ok=True)
for f in ("favicon.ico", "site.webmanifest"):  # served from the site root
    shutil.copy(os.path.join(here, "assets", f), os.path.join(dist, f))
# Security headers (CSP lists every script, font and API host the page uses) + image caching.
HEADERS = "/*\n  Strict-Transport-Security: max-age=31536000; includeSubDomains\n  X-Content-Type-Options: nosniff\n  X-Frame-Options: DENY\n  Referrer-Policy: strict-origin-when-cross-origin\n  Permissions-Policy: camera=(), microphone=(), geolocation=(), payment=(), usb=()\n  Content-Security-Policy: default-src 'self'; script-src 'self' 'unsafe-inline' https://static.cloudflareinsights.com; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src 'self' data: https://fonts.gstatic.com; img-src 'self' data: blob: https:; media-src 'self' data: blob:; connect-src 'self' https://api.github.com https://cloudflareinsights.com; worker-src 'self' blob:; manifest-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'; upgrade-insecure-requests\n\n/assets/*\n  Cache-Control: public, max-age=604800\n"
open(os.path.join(dist, '_headers'), 'w').write(HEADERS)
open(os.path.join(dist, "robots.txt"), "w").write(f"User-agent: *\nAllow: /\nSitemap: {SITE}/sitemap.xml\n")
open(os.path.join(dist, "sitemap.xml"), "w").write(f'<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9"><url><loc>{SITE}/</loc><lastmod>2026-11-26</lastmod></url></urlset>\n')
print("site/dist ready:", sorted(os.listdir(dist)))
