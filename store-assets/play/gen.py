import os
B=os.path.dirname(os.path.abspath(__file__)); S=f"file://{B}/src"; I=f"file://{B}/out/icon-512.png"
CSS="""
*{box-sizing:border-box}html,body{margin:0;overflow:hidden;font-family:-apple-system,'SF Pro Display',Helvetica,sans-serif;color:#fff}
.bg{position:absolute;inset:0;background:linear-gradient(160deg,#1d1650 0%,#0b1029 45%,#05060b 100%)}
.blob{position:absolute;border-radius:50%;filter:blur(90px);opacity:.75}
.grad{background:linear-gradient(90deg,#7CE0FF,#9B7BFF 50%,#FF5C8A);-webkit-background-clip:text;color:transparent}
.dev{position:absolute;background:#0a0b12;border:3px solid rgba(255,255,255,.18);box-shadow:0 40px 90px rgba(0,0,0,.65),0 0 0 10px #121423,0 0 80px rgba(155,123,255,.35);overflow:hidden}
.dev img{width:100%;height:100%;object-fit:cover;display:block}
.chip{display:inline-block;padding:10px 26px;border-radius:999px;background:rgba(255,255,255,.1);border:1px solid rgba(255,255,255,.22);font-weight:600;letter-spacing:.06em;text-transform:uppercase}
"""
def page(w,h,body):
    return f"<!doctype html><html><head><meta charset=utf-8><style>{CSS}html,body{{width:{w}px;height:{h}px}}</style></head><body><div class=bg></div>{body}</body></html>"
def blobs(sc=1,flip=False):
    a,b=("left","right") if not flip else ("right","left")
    return (f"<div class=blob style='width:{700*sc}px;height:{700*sc}px;background:#4b6bff;top:{-200*sc}px;{a}:{-250*sc}px'></div>"
            f"<div class=blob style='width:{650*sc}px;height:{650*sc}px;background:#ff3d7f;bottom:{-260*sc}px;{b}:{-260*sc}px;opacity:.55'></div>"
            f"<div class=blob style='width:{420*sc}px;height:{420*sc}px;background:#8a5cff;top:40%;{b}:{-120*sc}px;opacity:.45'></div>")
jobs=[]
# phone screenshots 1080x1920
phone=[("02-movies","Your IPTV,","beautifully played","Movies, series and live TV in one cinematic app"),
("10b-live-category","Live TV,","neatly organised","Categories, favourites and a now/next guide"),
("04-preview","Every title","at a glance","Artwork, ratings, cast and one-tap resume"),
("07-series-detail-offline","Binge-watch","your way","Seasons, episodes and watched marks"),
("08-downloads","Take it","offline","Download movies and episodes for the road")]
for i,(src,l1,l2,sub) in enumerate(phone,1):
    W,H=1080,1920; dw=640; dh=int(dw*1830/824)
    body=blobs(1,i%2==0)+f"""
<div style='position:absolute;top:110px;width:100%;text-align:center'>
 <div style='font-size:84px;font-weight:800;line-height:1.02;letter-spacing:-.02em'>{l1}<br><span class=grad>{l2}</span></div>
 <div style='margin-top:26px;font-size:36px;color:rgba(255,255,255,.75);font-weight:500'>{sub}</div></div>
<div class=dev style='width:{dw}px;height:{dh}px;left:{(W-dw)//2}px;top:470px;border-radius:64px'><img src='{S}/{src}.png'></div>"""
    jobs.append((f"phone-{i:02d}-{src}",W,H,page(W,H,body)))
# phone 6: player landscape + cast
W,H=1080,1920
body=blobs(1,True)+f"""
<div style='position:absolute;top:110px;width:100%;text-align:center'>
 <div style='font-size:84px;font-weight:800;line-height:1.02;letter-spacing:-.02em'>Big screen<br><span class=grad>ready</span></div>
 <div style='margin-top:26px;font-size:36px;color:rgba(255,255,255,.75);font-weight:500'>Cast to Chromecast &amp; Google TV, AirPlay to Apple TV</div></div>
<div class=dev style='width:1000px;height:563px;left:40px;top:520px;border-radius:30px'><img src='{S}/tv-03-movies-focus.png'></div>
<div class=dev style='width:900px;height:405px;left:90px;top:1180px;border-radius:44px'><img src='{S}/13-player.png'></div>
<div style='position:absolute;bottom:110px;width:100%;text-align:center'><span class=chip style='font-size:30px'>Phone · Tablet · Google TV · Car</span></div>"""
jobs.append(("phone-06-big-screen",W,H,page(W,H,body)))
# tablet 2560x1600 framed
for i,(src,t1,t2) in enumerate([("tab-02-movies","Cinematic on","every screen"),("tab-10b-live-category","Live TV in","clean columns")],1):
    W,H=2560,1600; dw=1960; dh=int(dw*1600/2560)
    body=blobs(1.6,i==2)+f"""
<div style='position:absolute;top:70px;width:100%;text-align:center;font-size:96px;font-weight:800;letter-spacing:-.02em'>{t1} <span class=grad>{t2}</span></div>
<div class=dev style='width:{dw}px;height:{dh}px;left:{(W-dw)//2}px;top:240px;border-radius:48px'><img src='{S}/{src}.png'></div>"""
    jobs.append((f"tablet-{i:02d}-{src[4:]}",W,H,page(W,H,body)))
# TV 1920x1080
for i,(src,t1,t2) in enumerate([("tv-03-movies-focus","Made for","Google TV"),("tv-06-series-detail","Series with","seasons & episodes"),("tv-07-live","Live TV with","now / next guide")],1):
    W,H=1920,1080; dw=1500; dh=int(dw*1080/1920)
    body=blobs(1.2,i==2)+f"""
<div style='position:absolute;top:44px;width:100%;text-align:center;font-size:66px;font-weight:800;letter-spacing:-.02em'>{t1} <span class=grad>{t2}</span></div>
<div class=dev style='width:{dw}px;height:{dh}px;left:{(W-dw)//2}px;top:160px;border-radius:26px'><img src='{S}/{src}.png'></div>"""
    jobs.append((f"tv-{i:02d}-{src[3:]}",W,H,page(W,H,body)))
# feature graphic 1024x500
W,H=1024,500
body=blobs(.7)+f"""
<img src='{I}' style='position:absolute;left:60px;top:110px;width:150px;height:150px;border-radius:36px;box-shadow:0 20px 50px rgba(0,0,0,.6)'>
<div style='position:absolute;left:60px;top:285px;font-size:64px;font-weight:800;letter-spacing:-.02em'>MK <span class=grad>IPTV</span></div>
<div style='position:absolute;left:62px;top:372px;font-size:24px;color:rgba(255,255,255,.8);font-weight:500;width:440px'>Your subscription, beautifully played.</div>
<div class=dev style='width:600px;height:338px;left:520px;top:40px;border-radius:18px;transform:rotate(-6deg)'><img src='{S}/tv-03-movies-focus.png'></div>
<div class=dev style='width:170px;height:378px;left:470px;top:150px;border-radius:26px;transform:rotate(-6deg)'><img src='{S}/10b-live-category.png'></div>"""
jobs.append(("feature-graphic-1024x500",W,H,page(W,H,body)))
# TV banner 1280x720
W,H=1280,720
body=blobs(.9)+f"""
<img src='{I}' style='position:absolute;left:150px;top:210px;width:300px;height:300px;border-radius:70px;box-shadow:0 30px 60px rgba(0,0,0,.6)'>
<div style='position:absolute;left:510px;top:240px;font-size:150px;font-weight:800;letter-spacing:-.03em'>MK <span class=grad>IPTV</span></div>"""
jobs.append(("tv-banner-1280x720",W,H,page(W,H,body)))
import subprocess
for name,w,h,html in jobs:
    p=f"{B}/html/{name}.html"; open(p,"w").write(html)
    subprocess.run([f"{B}/render.sh",p,f"{B}/out/{name}.png",str(w),str(h)])
    print(name)
