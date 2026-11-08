# Redraws NEKPLIK's pictures: res/drawable-xhdpi/banner.png (the TV's app row, 640x360),
# res/drawable-xxxhdpi/icon.png (192x192), res/drawable-xhdpi/logo.png (the wordmark at the top of
# each screen) and share-app/icon.png (1024x1024, for the Mac app). Needs Python with Pillow, on a Mac
# (it uses DIN Condensed Bold from the system fonts):
#   python3 art.py res
#
# A tall, condensed, heavy red wordmark on black, with a darker red edge underneath.
import os
import sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

out = sys.argv[1]
FONT = "/System/Library/Fonts/Supplemental/DIN Condensed Bold.ttf"
RED, DEEP = (229, 9, 20), (120, 0, 6)


def wordmark(text, height, track):
    """The word in red, stretched a little taller than the font, on a transparent background."""
    font = ImageFont.truetype(FONT, height)
    probe = ImageDraw.Draw(Image.new("L", (1, 1)))
    widths = [probe.textlength(c, font=font) for c in text]
    w = int(sum(widths) + track * (len(text) - 1)) + 8
    box = font.getbbox(text)
    h = box[3] - box[1] + 8
    mask = Image.new("L", (w, h), 0)
    d = ImageDraw.Draw(mask)
    x = 4
    for c, cw in zip(text, widths):
        d.text((x, 4 - box[1]), c, font=font, fill=255)
        x += cw + track
    mask = mask.resize((w, int(h * 1.18)), Image.LANCZOS)  # taller and narrower-looking
    img = Image.new("RGBA", mask.size, (0, 0, 0, 0))
    edge = Image.new("RGBA", mask.size, DEEP + (255,))
    img.paste(edge, (0, 3), mask)  # a darker red just below, for depth
    img.paste(Image.new("RGBA", mask.size, RED + (255,)), (0, 0), mask)
    return img


def on_black(size, word, center):
    img = Image.new("RGB", size, (0, 0, 0))
    glow = Image.new("RGB", size, (0, 0, 0))
    ImageDraw.Draw(glow).ellipse((-size[0] * 0.2, size[1] * 0.35, size[0] * 1.2, size[1] * 1.5), fill=(45, 0, 4))
    img = Image.blend(img, glow.filter(ImageFilter.GaussianBlur(size[0] / 8)), 0.8)
    img.paste(word, (int(center[0] - word.width / 2), int(center[1] - word.height / 2)), word)
    return img


on_black((640, 360), wordmark("NEKPLIK", 150, 6), (320, 180)).save(out + "/drawable-xhdpi/banner.png", optimize=True)
wordmark("NEKPLIK", 110, 4).save(out + "/drawable-xhdpi/logo.png", optimize=True)
# The intro's logo, large, so it stays sharp as it zooms towards you (drawable-nodpi: never resized).
os.makedirs(out + "/drawable-nodpi", exist_ok=True)
wordmark("NEKPLIK", 520, 19).save(out + "/drawable-nodpi/intro_logo.png", optimize=True)

icon = on_black((192, 192), wordmark("N", 170, 0), (96, 100))
rounded = Image.new("RGBA", (192, 192), (0, 0, 0, 0))
m = Image.new("L", (192, 192), 0)
ImageDraw.Draw(m).rounded_rectangle((0, 0, 191, 191), radius=40, fill=255)
rounded.paste(icon, (0, 0), m)
rounded.save(out + "/drawable-xxxhdpi/icon.png", optimize=True)

# The same icon at 1024 pixels, for the Mac app that shares a computer's movies (share-app/).
big = on_black((1024, 1024), wordmark("N", 900, 0), (512, 535))
m = Image.new("L", (1024, 1024), 0)
ImageDraw.Draw(m).rounded_rectangle((0, 0, 1023, 1023), radius=220, fill=255)
big_rounded = Image.new("RGBA", (1024, 1024), (0, 0, 0, 0))
big_rounded.paste(big, (0, 0), m)
big_rounded.save(out + "/../share-app/icon.png", optimize=True)
print("drawn")
