"""Fits a captured Mac window onto an exact App Store Mac screenshot size (2880x1800 or 1440x900) on the app's background."""
import sys
from PIL import Image

src, dst = sys.argv[1], sys.argv[2]
im = Image.open(src).convert("RGB")
W, H = (2880, 1800) if im.width > 1500 else (1440, 900)
if (im.width, im.height) == (W, H):
    im.save(dst); raise SystemExit
bg = Image.new("RGB", (W, H), (6, 7, 12))
scale = min(W / im.width, H / im.height)
im = im.resize((int(im.width * scale), int(im.height * scale)), Image.LANCZOS)
bg.paste(im, ((W - im.width) // 2, (H - im.height) // 2))
bg.save(dst)
