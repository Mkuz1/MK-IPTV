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
