"""Generates the Apple TV brand assets (layered icons, top shelf) and the Mac icon from the iOS icon.
Run: python3 tools/apple-assets/make_assets.py"""
import json, os
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
IOS_ICON = os.path.join(ROOT, "ios/MKIPTV/Assets.xcassets/AppIcon.appiconset/icon.png")
TV = os.path.join(ROOT, "ios/MKIPTVTV/Assets.xcassets")
FONT = "/usr/share/fonts/truetype/inter/InterDisplay-Black.ttf"
INFO = {"author": "xcode", "version": 1}

def font(size):
    for f in [FONT, "/usr/share/fonts/opentype/inter/InterDisplay-Black.otf", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"]:
        if os.path.exists(f): return ImageFont.truetype(f, size)
    import glob
    c = glob.glob("/usr/share/fonts/**/InterDisplay-Black*", recursive=True) + glob.glob("/usr/share/fonts/**/Inter*Black*", recursive=True)
    return ImageFont.truetype(c[0], size) if c else ImageFont.load_default()

def gradient(w, h):
    tl, tr, bl, br = (78, 112, 232), (138, 78, 224), (14, 10, 30), (222, 70, 140)
    small = Image.new("RGB", (2, 2)); small.putdata([tl, tr, bl, br])
    return small.resize((w, h), Image.BICUBIC)

def glass_logo(w, h, scale, text=True):
    """Transparent front layer: frosted rounded tile with the play glyph (and MK)."""
    im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    s = int(min(w, h) * scale)
    x0, y0 = (w - s) // 2, (h - s) // 2
    r = int(s * 0.24)
    shadow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle([x0, y0 + s * 0.04, x0 + s, y0 + s * 1.04], r, fill=(0, 0, 0, 110))
    im.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(s * 0.05)))
    d.rounded_rectangle([x0, y0, x0 + s, y0 + s], r, fill=(255, 255, 255, 58), outline=(255, 255, 255, 150), width=max(2, s // 120))
    cx, cy = x0 + s * 0.53, y0 + s * (0.37 if text else 0.5)
    t = s * 0.2
    d.polygon([(cx - t * 0.55, cy - t), (cx - t * 0.55, cy + t), (cx + t, cy)], fill=(225, 240, 255, 255))
    if text:
        f = font(int(s * 0.24))
        tw = d.textlength("MK", font=f)
        d.text((x0 + (s - tw) / 2, y0 + s * 0.6), "MK", font=f, fill="white")
    return im

def save(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)

def contents(path, obj):
    os.makedirs(path, exist_ok=True)
    with open(os.path.join(path, "Contents.json"), "w") as f: json.dump(obj, f, indent=2)

def imagestack(name, sizes):
    """sizes: list of (scale, w, h)."""
    base = os.path.join(TV, "App Icon & Top Shelf Image.brandassets", name)
    contents(base, {"info": INFO, "layers": [{"filename": "Front.imagestacklayer"}, {"filename": "Back.imagestacklayer"}]})
    for layer in ["Front", "Back"]:
        lp = os.path.join(base, f"{layer}.imagestacklayer")
        contents(lp, {"info": INFO})
        ip = os.path.join(lp, "Content.imageset")
        imgs = []
        for scale, w, h in sizes:
            fn = f"{layer.lower()}-{w}x{h}.png"
            img = gradient(w, h) if layer == "Back" else glass_logo(w, h, 0.78)
            save(img, os.path.join(ip, fn))
            imgs.append({"filename": fn, "idiom": "tv", "scale": scale})
        contents(ip, {"images": imgs, "info": INFO})

def topshelf(name, w, h):
    ip = os.path.join(TV, "App Icon & Top Shelf Image.brandassets", name)
    imgs = []
    for scale, k in [("1x", 1), ("2x", 2)]:
        W, H = w * k, h * k
        im = gradient(W, H).convert("RGBA")
        logo = glass_logo(int(H * 0.62), int(H * 0.62), 0.95)
        im.alpha_composite(logo, (int(W * 0.08), int(H * 0.19)))
        d = ImageDraw.Draw(im)
        d.text((int(W * 0.08 + H * 0.72), int(H * 0.27)), "MK IPTV", font=font(int(H * 0.22)), fill="white")
        d.text((int(W * 0.08 + H * 0.74), int(H * 0.55)), "Live TV · Movies · Series", font=font(int(H * 0.085)), fill=(255, 255, 255, 200))
        fn = f"{name.split('.')[0].replace(' ', '-').lower()}@{k}x.png"
        save(im.convert("RGB"), os.path.join(ip, fn))
        imgs.append({"filename": fn, "idiom": "tv", "scale": scale})
    contents(ip, {"images": imgs, "info": INFO})

def tv_assets():
    contents(TV, {"info": INFO})
    contents(os.path.join(TV, "AccentColor.colorset"), {"colors": [{"color": {"color-space": "srgb", "components": {"alpha": "1.000", "blue": "1.000", "green": "0.770", "red": "0.490"}}, "idiom": "universal"}], "info": INFO})
    contents(os.path.join(TV, "App Icon & Top Shelf Image.brandassets"), {"assets": [
        {"filename": "App Icon - App Store.imagestack", "idiom": "tv", "role": "primary-app-icon", "size": "1280x768"},
        {"filename": "App Icon.imagestack", "idiom": "tv", "role": "primary-app-icon", "size": "400x240"},
        {"filename": "Top Shelf Image Wide.imageset", "idiom": "tv", "role": "top-shelf-image-wide", "size": "2320x720"},
        {"filename": "Top Shelf Image.imageset", "idiom": "tv", "role": "top-shelf-image", "size": "1920x720"},
    ], "info": INFO})
    imagestack("App Icon.imagestack", [("1x", 400, 240), ("2x", 800, 480)])
    imagestack("App Icon - App Store.imagestack", [("1x", 1280, 768)])
    topshelf("Top Shelf Image.imageset", 1920, 720)
    topshelf("Top Shelf Image Wide.imageset", 2320, 720)

def mac_icon():
    """macOS icons aren't masked by the system: rounded tile with margin and shadow, all sizes."""
    src = Image.open(IOS_ICON).convert("RGBA")
    canvas = Image.new("RGBA", (1024, 1024), (0, 0, 0, 0))
    tile = src.resize((824, 824), Image.LANCZOS)
    mask = Image.new("L", (824, 824), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, 823, 823], 185, fill=255)
    shadow = Image.new("RGBA", (1024, 1024), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle([100, 112, 924, 936], 185, fill=(0, 0, 0, 90))
    canvas.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(14)))
    canvas.paste(tile, (100, 100), mask)
    setdir = os.path.join(ROOT, "ios/MKIPTV/Assets.xcassets/AppIcon.appiconset")
    images = [{"filename": "icon.png", "idiom": "universal", "platform": "ios", "size": "1024x1024"}]
    for pt in [16, 32, 128, 256, 512]:
        for sc in [1, 2]:
            px = pt * sc
            fn = f"mac-{pt}@{sc}x.png"
            canvas.resize((px, px), Image.LANCZOS).save(os.path.join(setdir, fn))
            images.append({"filename": fn, "idiom": "mac", "scale": f"{sc}x", "size": f"{pt}x{pt}"})
    contents(setdir, {"images": images, "info": INFO})

if __name__ == "__main__":
    tv_assets()
    mac_icon()
    print("ok")
