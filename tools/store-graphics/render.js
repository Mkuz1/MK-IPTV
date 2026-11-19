// Renders App Store / Google Play listing graphics: node tools/store-graphics/render.js
const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');
const root = path.resolve(__dirname, '../..');
const shots = path.join(root, 'docs/screenshots');
const out = path.join(root, 'docs/store-graphics');
const icon = 'file://' + path.join(root, 'ios/MKIPTV/Assets.xcassets/AppIcon.appiconset/icon.png');
const img = f => 'file://' + path.join(shots, f);

const slides = [
  { id: '01-browse', kicker: 'MOVIES · SERIES · LIVE TV', title: 'Your TV.<br><em>Beautifully</em> organized.', sub: 'Bring your Xtream Codes playlist and get a cinematic library for live TV, movies and series.', shot: '02-movies.png', tilt: -4 },
  { id: '02-continue', kicker: 'CONTINUE WATCHING', title: 'Pick up<br>right where<br>you <em>left off.</em>', sub: 'Saved to the second, even after you close the app.', shot: '05b-series-continue.png', tilt: 4 },
  { id: '03-sync', kicker: 'SYNC ACROSS DEVICES', title: 'One library.<br><em>Every screen.</em>', sub: 'Phone, tablet, TV and iPhone stay in sync. Sign in with Apple or Google, or link with a code.', sync: true },
  { id: '04-downloads', kicker: 'DOWNLOADS', title: 'Download.<br><em>Watch anywhere.</em>', sub: 'Movies and whole seasons offline, on the plane or the road.', shot: '08-downloads.png', tilt: -4 },
  { id: '05-live', kicker: 'LIVE TV', title: 'Thousands of channels.<br><em>Zero chaos.</em>', sub: 'Categories on the left, channels on the right, favorites one tap away.', shot: '10b-live-category.png', tilt: 4 },
  { id: '06-episodes', kicker: 'SERIES', title: 'Every episode,<br><em>tracked.</em>', sub: 'Watched marks, seasons and Up Next — never lose your place.', shot: '06-series-detail.png', tilt: -4 },
];

const css = (W, H) => `
*{margin:0;padding:0;box-sizing:border-box}
html,body{width:${W}px;height:${H}px;overflow:hidden;background:#05060c;font-family:'Inter Display','Inter',sans-serif;color:#fff}
.u{font-size:${W / 100}px}
.bg{position:absolute;inset:0;overflow:hidden}
.blob{position:absolute;border-radius:50%;filter:blur(${W * .09}px);opacity:.75}
.grain{position:absolute;inset:0;background:radial-gradient(ellipse at 50% 0%,rgba(255,255,255,.08),transparent 60%)}
em{font-style:normal;background:linear-gradient(100deg,#7dc4ff 0%,#b48cff 45%,#ff5f8f 100%);-webkit-background-clip:text;background-clip:text;color:transparent}
.kicker{display:inline-flex;align-items:center;gap:.8em;font-weight:700;letter-spacing:.22em;color:rgba(255,255,255,.75);
  padding:.7em 1.4em;border-radius:99em;background:rgba(255,255,255,.08);border:1px solid rgba(255,255,255,.18);backdrop-filter:blur(20px)}
.kicker i{width:.7em;height:.7em;border-radius:50%;background:#ff5f8f;box-shadow:0 0 1em #ff5f8f}
h1{font-weight:900;letter-spacing:-.035em;line-height:1.02}
p.sub{color:rgba(255,255,255,.66);font-weight:500;line-height:1.35}
.phone{position:absolute;border-radius:9.5%/4.4%;padding:1.6%;background:linear-gradient(145deg,#3a3d4a,#101119 40%,#2b2d38);
  box-shadow:0 0 0 2px rgba(255,255,255,.14) inset,0 60px 140px rgba(0,0,0,.65),0 0 120px rgba(125,196,255,.18)}
.phone .scr{width:100%;height:100%;border-radius:8.4%/3.9%;overflow:hidden;background:#000;position:relative}
.phone .scr img{width:100%;height:100%;object-fit:cover;object-position:top}
.phone .scr:after{content:'';position:absolute;inset:0;background:linear-gradient(115deg,rgba(255,255,255,.10),transparent 35%)}
.island{position:absolute;top:2.6%;left:50%;transform:translateX(-50%);width:28%;height:2.6%;background:#000;border-radius:99em;z-index:2}
.glass{background:rgba(255,255,255,.07);border:1px solid rgba(255,255,255,.18);backdrop-filter:blur(30px);box-shadow:0 30px 80px rgba(0,0,0,.45)}
`;

const blobs = `<div class="bg">
 <div class="blob" style="width:70%;height:40%;left:-25%;top:-8%;background:#6b4dff"></div>
 <div class="blob" style="width:65%;height:38%;right:-25%;top:30%;background:#ff3d7f;opacity:.55"></div>
 <div class="blob" style="width:70%;height:40%;left:-15%;bottom:-18%;background:#2bc2ff;opacity:.5"></div>
 <div class="grain"></div></div>`;

function syncArt(W) {
  return `<div style="position:absolute;left:0;right:0;top:38%;height:56%">
    <div class="glass" style="position:absolute;left:8%;top:6%;width:84%;height:44%;border-radius:2.2em;font-size:${W/100}px">
      <div style="position:absolute;inset:7% 6% 14% 6%;border-radius:1.4em;overflow:hidden;background:#000">
        <img src="${img('tv-02-movies.png')}" style="width:100%;height:100%;object-fit:cover"></div>
      <div style="position:absolute;bottom:4%;left:50%;transform:translateX(-50%);width:20%;height:3%;border-radius:1em;background:rgba(255,255,255,.25)"></div>
      <div style="position:absolute;top:-6%;left:5%;font-weight:800;font-size:2.6em;letter-spacing:.1em;color:rgba(255,255,255,.8)">GOOGLE TV</div>
    </div>
    <div class="phone" style="left:6%;top:43%;width:30%;height:56%"><div class="island"></div><div class="scr"><img src="${img('05b-series-continue.png')}"></div></div>
    <div class="phone" style="right:6%;top:43%;width:30%;height:56%"><div class="island"></div><div class="scr"><img src="${img('10b-live-category.png')}"></div></div>
    <div class="glass" style="position:absolute;left:50%;top:72%;transform:translateX(-50%);width:46%;border-radius:2em;padding:2.4% 2%;text-align:center;font-size:${W/100}px">
      <div style="font-weight:700;letter-spacing:.2em;font-size:2.1em;color:rgba(255,255,255,.65)">LINK CODE</div>
      <div style="font-weight:900;font-size:5.4em;letter-spacing:.06em;white-space:nowrap;margin:.15em 0">K7QM-4XPZ</div>
      <div style="display:flex;justify-content:center;gap:.6em;font-size:2.2em;font-weight:700;color:#33c77a">● Synced</div>
    </div>
  </div>`;
}

function slideHtml(s, W, H) {
  const f = W / 100;
  const phoneW = 64, phoneH = phoneW * (2.22) * W / H;  // screenshot aspect 824x1830
  if (W / H > 0.6 && !s.sync) {   // iPad: landscape tablet screenshot in a tablet frame
    const tw = 88, th = tw / 1.6 * W / H;
    return `<html><head><style>${css(W, H)}</style></head><body>${blobs}
    <div style="position:absolute;left:7%;right:7%;top:5%;font-size:${f}px">
      <div class="kicker" style="font-size:2.1em"><i></i>${s.kicker}</div>
      <h1 style="font-size:7.6em;margin-top:.35em">${s.title.replace(/<br>/g, ' ')}</h1>
      <p class="sub" style="font-size:3em;margin-top:.5em;max-width:85%">${s.sub}</p>
    </div>
    <div class="phone" style="left:${(100 - tw) / 2}%;top:${96 - th}%;width:${tw}%;height:${th}%;border-radius:3.2%/5.1%;padding:1.4%">
      <div class="scr" style="border-radius:2.2%/3.5%"><img src="${'file://' + path.join(shots, 'tablet', s.shot)}" style="object-fit:cover"></div></div>
    </body></html>`;
  }
  const top = s.sync ? '' : `<div class="phone" style="left:${(100 - phoneW) / 2}%;top:${H / W > 1.6 ? 36 : 40}%;width:${phoneW}%;height:${phoneH}%;transform:rotate(${s.tilt}deg)">
      <div class="island"></div><div class="scr"><img src="${img(s.shot)}"></div></div>`;
  return `<html><head><style>${css(W, H)}</style></head><body>${blobs}
  <div style="position:absolute;left:7%;right:7%;top:${H / W > 1.9 ? 6.5 : 5}%;font-size:${f}px">
    <div class="kicker" style="font-size:2.3em"><i></i>${s.kicker}</div>
    <h1 style="font-size:9.4em;margin-top:.35em">${s.title}</h1>
    <p class="sub" style="font-size:3.7em;margin-top:.55em;max-width:92%">${s.sub}</p>
  </div>
  ${s.sync ? syncArt(W) : top}
  </body></html>`;
}

function featureHtml() {
  const W = 1024, H = 500;
  return `<html><head><style>${css(W, H)}</style></head><body>${blobs}
   <div style="position:absolute;left:56px;top:0;bottom:0;display:flex;flex-direction:column;justify-content:center;width:520px">
     <div style="display:flex;align-items:center;gap:22px">
       <img src="${icon}" style="width:104px;height:104px;border-radius:24px;box-shadow:0 18px 50px rgba(0,0,0,.5)">
       <div style="font-weight:900;font-size:60px;letter-spacing:-.02em">MK IPTV</div>
     </div>
     <h1 style="font-size:46px;margin-top:26px">Live TV, movies & series.<br><em>Beautifully organized.</em></h1>
     <p class="sub" style="font-size:20px;margin-top:14px">Downloads · Continue Watching · Sync on every screen</p>
   </div>
   <div class="phone" style="right:200px;top:70px;width:200px;height:444px;transform:rotate(-8deg)"><div class="island"></div><div class="scr"><img src="${img('05b-series-continue.png')}"></div></div>
   <div class="phone" style="right:40px;top:40px;width:200px;height:444px;transform:rotate(6deg)"><div class="island"></div><div class="scr"><img src="${img('02-movies.png')}"></div></div>
  </body></html>`;
}

const tmp = path.join(require('os').tmpdir(), 'mk-store-slide.html');
async function load(page, html) {   // file:// page so local screenshots can load
  fs.writeFileSync(tmp, html);
  await page.goto('file://' + tmp, { waitUntil: 'load' });
}

(async () => {
  const browser = await chromium.launch();
  const sets = [
    { dir: 'app-store-6.9in', W: 1320, H: 2868 },
    { dir: 'app-store-6.5in', W: 1284, H: 2778 },
    { dir: 'app-store-ipad-13in', W: 2064, H: 2752 },
    { dir: 'google-play-phone', W: 1080, H: 2160 },
  ];
  for (const set of sets) {
    fs.mkdirSync(path.join(out, set.dir), { recursive: true });
    const page = await browser.newPage({ viewport: { width: set.W, height: set.H }, deviceScaleFactor: 1 });
    for (const s of slides) {
      await load(page, slideHtml(s, set.W, set.H));
      await page.screenshot({ path: path.join(out, set.dir, s.id + '.png') });
    }
    await page.close();
  }
  const page = await browser.newPage({ viewport: { width: 1024, height: 500 }, deviceScaleFactor: 1 });
  await load(page, featureHtml());
  await page.screenshot({ path: path.join(out, 'google-play-feature-graphic.png') });
  await browser.close();
  console.log('done');
})();
