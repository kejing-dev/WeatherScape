"""WeatherScape icon (design appIcon): a brass ring holding a small stone house, a grey-white cloud with three rain
streaks above it, on a fog-blue #5B7A99 disc. Flat with a little volume; no text, no company name.

  python3 tools/build_icon.py [--icon512 out.png] [--preview out.png]

Writes res/drawable/icon_3d_layer_{0,1,2}.png + icon_3d_sdf_{0,1,2}.png (PICO layered icon), mipmap-xxxhdpi/
ic_spatial_launcher.png and the adaptive foreground vector res/drawable/ic_launcher_fg.xml (same polygons).
"""
import math, os, sys
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
S, SS = 1024, 4
FOG = (0x5B, 0x7A, 0x99)
BRASS = (0xC9, 0x9A, 0x3E); BRASS_D = (0x8E, 0x68, 0x22)
STONE = (0xB9, 0xB2, 0xA6); STONE_D = (0x8F, 0x88, 0x7C)
ROOF = (0x3E, 0x40, 0x45); CHIM = (0xA4, 0x4A, 0x32)
WIN = (0xFF, 0xC4, 0x6B)
CLOUD = (0xF1, 0xF3, 0xF5); CLOUD_D = (0xC9, 0xCF, 0xD6)
RAIN = (0xDD, 0xE8, 0xF2)


def ellipse(cx, cy, rx, ry, n=64, a0=0.0, a1=2 * math.pi):
    return [(cx + rx * math.cos(a0 + (a1 - a0) * i / n), cy + ry * math.sin(a0 + (a1 - a0) * i / n)) for i in range(n + 1)]


def ring_band(cx, cy, rx, ry, w, front):
    """Half of a flat elliptical band (front = lower half drawn in front of the house, back = upper half behind)."""
    a0, a1 = (0, math.pi) if front else (math.pi, 2 * math.pi)
    outer = ellipse(cx, cy, rx, ry, 48, a0, a1)
    inner = ellipse(cx, cy, rx - w, ry - w * 0.42, 48, a0, a1)
    return outer + inner[::-1]


def house():
    out = []
    out.append(([(39, 50), (39, 67), (63, 67), (63, 50)], STONE))      # wall
    out.append(([(63, 50), (63, 67), (69, 64), (69, 48)], STONE_D))    # side wall
    out.append(([(35, 51), (51, 37), (67, 51)], ROOF))                 # gable roof
    out.append(([(51, 37), (67, 51), (72, 48), (57, 35)], (0x2E, 0x30, 0x34)))
    out.append(([(56, 42), (56, 33), (61, 33), (61, 46)], CHIM))       # chimney
    out.append(([(44, 55), (44, 61), (49, 61), (49, 55)], WIN))
    out.append(([(53, 55), (53, 61), (58, 61), (58, 55)], WIN))
    return out


def cloud():
    out = []
    for (cx, cy, r, col) in [(40, 27, 8, CLOUD_D), (50, 23, 10, CLOUD), (61, 27, 8, CLOUD), (46, 30, 7, CLOUD), (56, 30, 7, CLOUD)]:
        out.append((ellipse(cx, cy, r, r * 0.85, 40), col))
    out.append(([(34, 30), (68, 30), (68, 35), (34, 35)], CLOUD))
    return out


def rain():
    out = []
    for x in (42, 50, 58):
        out.append(([(x, 39), (x + 2, 39), (x - 1, 47), (x - 3, 47)], RAIN))
    return out


def big(polys):
    im = Image.new("RGBA", (S * SS, S * SS), (0, 0, 0, 0))
    dr = ImageDraw.Draw(im); k = S * SS / 108.0
    for pts, col in polys:
        dr.polygon([(x * k, y * k) for x, y in pts], fill=col + (255,))
    return im.resize((S, S), Image.LANCZOS)


RING_BACK = [(ring_band(54, 70, 33, 13, 7, False), BRASS_D)]
RING_FRONT = [(ring_band(54, 70, 33, 13, 7, True), BRASS), (ring_band(54, 72.5, 33, 13, 2.5, True), BRASS_D)]


def layer_bg():
    return big([(ellipse(54, 54, 54, 54, 128), FOG)])


def layer_ring_house():
    return big(RING_BACK + [(ellipse(54, 70, 26, 9.5, 64), (0x7F, 0xA3, 0x5A))] + house() + RING_FRONT)


def layer_cloud():
    return big(rain() + cloud())


def sdf(layer):
    inside = layer.getchannel("A").point(lambda v: 255 if v >= 128 else 0)
    dist = Image.new("L", inside.size, 0)
    cur = inside
    for i in range(1, 33):
        dist.paste(min(255, i * 8), mask=cur)
        cur = cur.filter(ImageFilter.MinFilter(3) if i % 2 else ImageFilter.Kernel((3, 3), (0, 1, 0, 1, 1, 1, 0, 1, 0), 5))
        cur = cur.point(lambda v: 255 if v >= 255 else 0)
    dist = Image.composite(dist, Image.new("L", inside.size, 0), inside)
    return Image.merge("RGBA", (dist, dist, dist, dist))


def vector():
    hexc = lambda c: "#FF%02X%02X%02X" % c
    path = lambda pts: "M" + "L".join("%.2f,%.2f" % p for p in pts) + "Z"
    lines = ['<?xml version="1.0" encoding="utf-8"?>',
             '<!-- WeatherScape launcher foreground (tools/build_icon.py draws the same artwork for the PICO layered icon). -->',
             '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">']
    for pts, col in RING_BACK + [(ellipse(54, 70, 26, 9.5, 64), (0x7F, 0xA3, 0x5A))] + house() + RING_FRONT + rain() + cloud():
        lines.append('    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(col), path(pts)))
    lines.append('</vector>')
    open(os.path.join(RES, "drawable", "ic_launcher_fg.xml"), "w").write("\n".join(lines) + "\n")


def main():
    layers = [layer_bg(), layer_ring_house(), layer_cloud()]
    for i, layer in enumerate(layers):
        layer.save(os.path.join(RES, "drawable", f"icon_3d_layer_{i}.png"))
        sdf(layer).save(os.path.join(RES, "drawable", f"icon_3d_sdf_{i}.png"))
    flat = Image.new("RGBA", layers[0].size, (0, 0, 0, 0))
    for l in layers:
        flat.alpha_composite(l)
    flat.save(os.path.join(RES, "mipmap-xxxhdpi", "ic_spatial_launcher.png"))
    vector()
    if "--icon512" in sys.argv:
        flat.resize((512, 512), Image.LANCZOS).save(sys.argv[sys.argv.index("--icon512") + 1])
    if "--preview" in sys.argv:
        flat.resize((256, 256), Image.LANCZOS).save(sys.argv[sys.argv.index("--preview") + 1])
    print("ICON ok")


main()
