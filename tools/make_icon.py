"""Draw the TTR Mobile app icon into build/app-res (python tools/extract_ttr_assets.py first).

TTR's own icon (build/ttr-art/icon.png, extracted from the official launcher by
tools/extract_ttr_assets.py) on a blue gradient, with a red "UNOFFICIAL" stamp tilted across it so the app
is never mistaken for an official TTR release. Writes legacy square/round launcher PNGs for each
density plus adaptive-icon foreground/background layers (Android 8+).
Run: python tools/make_icon.py
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / 'build/app-res'
LOGO = ROOT / 'build/ttr-art/icon.png'
S = 1024
DENSITIES = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}
TOP, BOTTOM = (64, 150, 222), (16, 52, 98)
RIBBON, RIBBON_EDGE, INK = (214, 40, 40), (120, 12, 16), (255, 255, 255)
TILT = 12  # degrees, rising to the right


def font(size):
    for name in ('C:/Windows/Fonts/arialbd.ttf', '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf', '/System/Library/Fonts/Supplemental/Arial Bold.ttf'):
        try: return ImageFont.truetype(name, size)
        except OSError: pass
    return ImageFont.load_default()


def background(size):
    img = Image.new('RGB', (size, size)); d = ImageDraw.Draw(img)
    for y in range(size):
        t = y / (size - 1)
        d.line([(0, y), (size, y)], fill=tuple(round(a + (b - a) * t) for a, b in zip(TOP, BOTTOM)))
    glow = Image.new('L', (size, size), 0)
    ImageDraw.Draw(glow).ellipse([-size * .3, -size * .4, size * .8, size * .6], fill=60)
    glow = glow.filter(ImageFilter.GaussianBlur(size * .12))
    return Image.composite(Image.new('RGB', (size, size), (150, 205, 255)), img, glow)


def foreground(size, scale):
    """TTR logo plus ribbon, together spanning about `scale` of the canvas, centred."""
    img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    logo = Image.open(LOGO).convert('RGBA')
    box = logo.getbbox(); logo = logo.crop(box)  # trim transparent margins
    side = int(size * scale * .86)
    k = side / max(logo.size); logo = logo.resize((round(logo.width * k), round(logo.height * k)), Image.LANCZOS)
    # soft shadow under the logo
    shadow = Image.new('RGBA', logo.size, (0, 0, 0, 0)); shadow.putalpha(logo.getchannel('A').point(lambda a: a * .45))
    shadow = shadow.filter(ImageFilter.GaussianBlur(size * .012))
    lx, ly = (size - logo.width) // 2, int(size / 2 - size * scale / 2)
    img.alpha_composite(shadow, (lx + int(size * .008), ly + int(size * .016))); img.alpha_composite(logo, (lx, ly))
    # red stamp across the lower part, tilted
    rw, rh = size * scale * .92, size * scale * .2
    rx, ry = (size - rw) / 2, size / 2 - rh / 2 + size * scale * .1  # across the middle of the logo
    big = Image.new('RGBA', (size * 2, size * 2), (0, 0, 0, 0)); d = ImageDraw.Draw(big)
    edge = rh * .09
    d.rounded_rectangle([2 * (rx - edge), 2 * (ry - edge), 2 * (rx + rw + edge), 2 * (ry + rh + edge)], radius=2 * (rh / 2 + edge), fill=RIBBON_EDGE)
    d.rounded_rectangle([2 * rx, 2 * ry, 2 * (rx + rw), 2 * (ry + rh)], radius=2 * rh / 2, fill=RIBBON)
    f = font(int(2 * rh * .62)); text = 'UNOFFICIAL'
    while d.textlength(text, font=f) > 2 * rw * .86: f = font(int(f.size * .95))
    d.text((2 * (rx + rw / 2), 2 * (ry + rh / 2)), text, font=f, fill=INK, anchor='mm')
    big = big.rotate(TILT, resample=Image.BICUBIC, center=(2 * (rx + rw / 2), 2 * (ry + rh / 2)))
    img.alpha_composite(big.resize((size, size), Image.LANCZOS))
    return img


def save(img, density, name, px):
    folder = RES / f'mipmap-{density}'; folder.mkdir(parents=True, exist_ok=True)
    img.resize((px, px), Image.LANCZOS).save(folder / name, optimize=True)


def main():
    bg, fg_adaptive, fg_legacy = background(S), foreground(S, .62), foreground(S, .84)
    legacy = bg.convert('RGBA'); legacy.alpha_composite(fg_legacy)
    mask = Image.new('L', (S * 2, S * 2), 0); ImageDraw.Draw(mask).rounded_rectangle([0, 0, S * 2 - 1, S * 2 - 1], radius=S * .44, fill=255)
    square = Image.new('RGBA', (S, S), (0, 0, 0, 0)); square.paste(legacy, (0, 0), mask.resize((S, S), Image.LANCZOS))
    circle = Image.new('L', (S * 2, S * 2), 0); ImageDraw.Draw(circle).ellipse([0, 0, S * 2 - 1, S * 2 - 1], fill=255)
    round_icon = Image.new('RGBA', (S, S), (0, 0, 0, 0)); round_icon.paste(legacy, (0, 0), circle.resize((S, S), Image.LANCZOS))
    for density, k in DENSITIES.items():
        save(square, density, 'ic_launcher.png', round(48 * k))
        save(round_icon, density, 'ic_launcher_round.png', round(48 * k))
        save(fg_adaptive, density, 'ic_launcher_foreground.png', round(108 * k))
        save(bg, density, 'ic_launcher_background.png', round(108 * k))
    anydpi = RES / 'mipmap-anydpi-v26'; anydpi.mkdir(parents=True, exist_ok=True)
    xml = ('<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
           '    <background android:drawable="@mipmap/ic_launcher_background" />\n    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
           '</adaptive-icon>\n')
    for name in ('ic_launcher.xml', 'ic_launcher_round.xml'): (anydpi / name).write_bytes(xml.encode())
    (ROOT / 'build').mkdir(exist_ok=True)
    legacy.resize((512, 512), Image.LANCZOS).save(ROOT / 'build/icon-preview.png')
    adaptive = bg.convert('RGBA'); adaptive.alpha_composite(fg_adaptive)
    shown = Image.new('RGBA', (S, S), (255, 255, 255, 255)); shown.paste(adaptive, (0, 0), circle.resize((S, S), Image.LANCZOS))
    shown.resize((432, 432), Image.LANCZOS).save(ROOT / 'build/icon-adaptive-preview.png')
    print('Icons written to', RES)


if __name__ == '__main__': main()
