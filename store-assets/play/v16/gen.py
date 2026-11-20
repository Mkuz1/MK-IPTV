import os, subprocess
R=os.path.expanduser("~/Documents/MK-IPTV-playbuild"); S=f"file://{R}/docs/screenshots"; ICON=f"file://{R}/ios/MKIPTV/Assets.xcassets/AppIcon.appiconset/icon.png"
B=os.path.dirname(os.path.abspath(__file__)); RAW=B+"/raw"; REN=os.path.expanduser("~/Downloads/MK-IPTV-play-assets/render.sh")
def css(W,H): return f"""*{{margin:0;padding:0;box-sizing:border-box}}
html,body{{width:{W}px;height:{H}px;overflow:hidden;background:#05060c;font-family:'Inter Display','Inter',-apple-system,'SF Pro Display',sans-serif;color:#fff}}
.bg{{position:absolute;inset:0;overflow:hidden}}.blob{{position:absolute;border-radius:50%;filter:blur({W*.06}px);opacity:.75}}
.grain{{position:absolute;inset:0;background:radial-gradient(ellipse at 50% 0%,rgba(255,255,255,.08),transparent 60%)}}
em{{font-style:normal;background:linear-gradient(100deg,#7dc4ff 0%,#b48cff 45%,#ff5f8f 100%);-webkit-background-clip:text;background-clip:text;color:transparent}}
.kicker{{display:inline-flex;align-items:center;gap:.8em;font-weight:700;letter-spacing:.22em;color:rgba(255,255,255,.75);padding:.7em 1.4em;border-radius:99em;background:rgba(255,255,255,.08);border:1px solid rgba(255,255,255,.18)}}
.kicker i{{width:.7em;height:.7em;border-radius:50%;background:#ff5f8f;box-shadow:0 0 1em #ff5f8f}}
h1{{font-weight:900;letter-spacing:-.035em;line-height:1.02}} p.sub{{color:rgba(255,255,255,.66);font-weight:500;line-height:1.35}}
.dev{{position:absolute;background:linear-gradient(145deg,#3a3d4a,#101119 40%,#2b2d38);box-shadow:0 0 0 2px rgba(255,255,255,.14) inset,0 50px 120px rgba(0,0,0,.65),0 0 120px rgba(125,196,255,.18)}}
.dev .scr{{width:100%;height:100%;overflow:hidden;background:#000;position:relative}} .dev .scr img{{width:100%;height:100%;object-fit:cover;object-position:top}}
.dev .scr:after{{content:'';position:absolute;inset:0;background:linear-gradient(115deg,rgba(255,255,255,.10),transparent 35%)}}"""
blobs="""<div class="bg"><div class="blob" style="width:55%;height:70%;left:-15%;top:-25%;background:#6b4dff"></div>
<div class="blob" style="width:50%;height:65%;right:-15%;top:20%;background:#ff3d7f;opacity:.55"></div>
<div class="blob" style="width:55%;height:60%;left:20%;bottom:-40%;background:#2bc2ff;opacity:.5"></div><div class="grain"></div></div>"""
def slide(W,H,k,t,sub,shot,kind):
    f=W/100
    if kind=="tv":
        dw=58; dh=dw*W/H*1080/1920
        frame=f"""<div class="dev" style="right:4%;top:{(100-dh)/2+3}%;width:{dw}%;height:{dh}%;padding:.8%;border-radius:1.4%/2.5%"><div class="scr" style="border-radius:.9%/1.6%"><img src="file://{RAW}/{shot}"></div></div>
<div style="position:absolute;right:{4+dw/2-6}%;top:{(100-dh)/2+3+dh+1.2}%;width:12%;height:1.2%;border-radius:1em;background:rgba(255,255,255,.22)"></div>"""
    else:
        dw=56; dh=dw*W/H/1.6
        frame=f"""<div class="dev" style="right:4.5%;top:{(100-dh)/2+2}%;width:{dw}%;height:{dh}%;padding:1.3%;border-radius:3.2%/5.1%"><div class="scr" style="border-radius:2%/3.2%"><img src="file://{RAW}/{shot}"></div></div>"""
    return f"""<html><head><meta charset=utf-8><style>{css(W,H)}</style></head><body>{blobs}
<div style="position:absolute;left:5.5%;top:0;bottom:0;width:34%;display:flex;flex-direction:column;justify-content:center;font-size:{f}px">
 <div class="kicker" style="font-size:1.05em;align-self:flex-start"><i></i>{k}</div>
 <h1 style="font-size:4.6em;margin-top:.4em">{t}</h1><p class="sub" style="font-size:1.55em;margin-top:.7em">{sub}</p>
 <div style="display:flex;align-items:center;gap:.7em;margin-top:2.2em;font-weight:800;font-size:1.5em"><img src="{ICON}" style="width:2.2em;height:2.2em;border-radius:.5em">MK IPTV</div>
</div>{frame}</body></html>"""
tv=[("GOOGLE TV","Made for the<br><em>big screen.</em>","A remote-friendly library for the IPTV service you already subscribe to.","tv-movies.png"),
("MOVIES","Every title<br><em>at a glance.</em>","Artwork, cast, My List and downloads.","tv-detail.png"),
("SERIES","Series, <em>beautifully</em> organized.","New episodes, seasons and watched marks, synced with your phone.","tv-series.png"),
("LIVE TV","Live TV with<br><em>now &amp; next.</em>","A programme guide built for the remote.","tv-live.png")]
tab=[("MOVIES · SERIES · LIVE TV","Your TV.<br><em>Beautifully</em> organized.","A cinematic library for the IPTV service you already subscribe to.","tab-movies.png"),
("LIVE TV","Thousands of channels.<br><em>Zero chaos.</em>","Channels, guide and a live preview.","tab-live.png"),
("MOVIES","Every title<br><em>at a glance.</em>","Artwork, cast, My List and one-tap downloads.","tab-detail.png"),
("SERIES","Every episode,<br><em>tracked.</em>","New episodes, seasons and Continue Watching.","tab-series.png")]
jobs=[]
for i,s in enumerate(tv,1): jobs.append((f"tv-{i:02d}",1920,1080,slide(1920,1080,*s,"tv")))
for i,s in enumerate(tab,1): jobs.append((f"tablet-{i:02d}",1920,1080,slide(1920,1080,*s,"tab")))
banner=f"""<html><head><meta charset=utf-8><style>{css(1280,720)}</style></head><body>{blobs}
<div style="position:absolute;inset:0;display:flex;align-items:center;justify-content:center;gap:56px">
<img src="{ICON}" style="width:300px;height:300px;border-radius:68px;box-shadow:0 30px 70px rgba(0,0,0,.6)">
<div style="font-weight:900;font-size:150px;letter-spacing:-.03em">MK <em>IPTV</em></div></div></body></html>"""
jobs.append(("tv-banner-1280x720",1280,720,banner))
for n,w,h,html in jobs:
    p=f"{B}/html/{n}.html"; open(p,"w").write(html); subprocess.run([REN,p,f"{B}/out/{n}.png",str(w),str(h)]); print(n)
