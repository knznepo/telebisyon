# Redraws res/drawable-xhdpi/banner.png and res/drawable-xxxhdpi/icon.png. Needs Python with Pillow, on a
# Mac (it uses Arial Black from the system fonts):  python3 art.py res
# Draws FLIXNET's TV banner (640x360, xhdpi) and icon (192x192, xxxhdpi): a gold-to-orange wordmark
# on a warm near-black, with a soft glow.
import sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont
out = sys.argv[1]
FONT = "/System/Library/Fonts/Supplemental/Arial Black.ttf"

def background(w, h):
    img = Image.new("RGB", (w, h), (14, 10, 12))
    glow = Image.new("RGB", (w, h), (0, 0, 0))
    d = ImageDraw.Draw(glow)
    d.ellipse((-w * 0.3, h * 0.15, w * 1.3, h * 1.6), fill=(70, 30, 12))
    return Image.blend(img, glow.filter(ImageFilter.GaussianBlur(w / 6)), 0.75)

def gradient_text(img, text, size, center, track=0):
    font = ImageFont.truetype(FONT, size)
    w, h = img.size
    mask = Image.new("L", (w, h), 0)
    md = ImageDraw.Draw(mask)
    widths = [md.textlength(c, font=font) for c in text]
    total = sum(widths) + track * (len(text) - 1)
    x = center[0] - total / 2
    box = font.getbbox(text)
    y = center[1] - (box[1] + box[3]) / 2
    for c, cw in zip(text, widths):
        md.text((x, y), c, font=font, fill=255)
        x += cw + track
    grad = Image.new("RGB", (w, h))
    gd = ImageDraw.Draw(grad)
    top, bottom = (255, 214, 102), (255, 112, 36)
    for yy in range(h):
        t = yy / max(1, h - 1)
        gd.line([(0, yy), (w, yy)], fill=tuple(int(a + (b - a) * t) for a, b in zip(top, bottom)))
    shadow = mask.filter(ImageFilter.GaussianBlur(size / 10))
    img.paste((255, 120, 40), (0, 0), shadow.point(lambda v: v * 0.35))
    img.paste(grad, (0, 0), mask)
    return img

banner = gradient_text(background(640, 360), "FLIXNET", 92, (320, 180), track=4)
banner.save(out + "/drawable-xhdpi/banner.png", optimize=True)

icon = background(192, 192)
icon = gradient_text(icon, "F", 150, (96, 100))
m = Image.new("L", (192, 192), 0)
ImageDraw.Draw(m).rounded_rectangle((0, 0, 191, 191), radius=40, fill=255)
rounded = Image.new("RGBA", (192, 192), (0, 0, 0, 0))
rounded.paste(icon, (0, 0), m)
rounded.save(out + "/drawable-xxxhdpi/icon.png", optimize=True)
print("drawn")
