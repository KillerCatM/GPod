"""Redraws Shuffle.png procedurally at any resolution.

Geometry is measured from the 202px original (tile = superellipse n=3, wheel R=0.84,
center button r=0.515, icons on radius 0.68); shading matches sampled colours.
Usage: python3 draw_shuffle.py OUT.png SIZE
"""
import math
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

OUT = sys.argv[1]
S = int(sys.argv[2])
SS = 4                      # supersampling factor for crisp masks
UNIT = S * 99 / 202         # 1.0 in shape units, in output pixels (matches original margins)
C = S / 2


def mask(draw_fn):
    """Draw a shape at SS x resolution with PIL and downsample to an anti-aliased mask."""
    big = Image.new("L", (S * SS, S * SS), 0)
    d = ImageDraw.Draw(big)
    draw_fn(d, lambda x, y: (C * SS + x * UNIT * SS, C * SS + y * UNIT * SS), UNIT * SS)
    return np.asarray(big.resize((S, S), Image.BOX), dtype=np.float32) / 255.0


def superellipse(d, P, u, n=3.0, k=1.0):
    pts = []
    for i in range(720):
        t = 2 * math.pi * i / 720
        c, s = math.cos(t), math.sin(t)
        pts.append(P(k * math.copysign(abs(c) ** (2 / n), c), k * math.copysign(abs(s) ** (2 / n), s)))
    d.polygon(pts, fill=255)


def rounded_square(d, P, u, rc=0.72):
    x0, y0 = P(-1, -1)
    x1, y1 = P(1, 1)
    d.rounded_rectangle([x0, y0, x1, y1], radius=rc * u, fill=255)


def soften(m, sigma):
    """Gaussian-blur a mask by sigma shape-units (soft edges like the original)."""
    img = Image.fromarray((m * 255).astype(np.uint8))
    return np.asarray(img.filter(ImageFilter.GaussianBlur(sigma * UNIT)), dtype=np.float32) / 255.0


def circle(r, cx=0.0, cy=0.0):
    def f(d, P, u):
        x0, y0 = P(cx - r, cy - r)
        x1, y1 = P(cx + r, cy + r)
        d.ellipse([x0, y0, x1, y1], fill=255)
    return f


def polys(*shapes):
    def f(d, P, u):
        for pts in shapes:
            d.polygon([P(x, y) for x, y in pts], fill=255)
    return f


def rect(x0, y0, x1, y1):
    return [(x0, y0), (x1, y0), (x1, y1), (x0, y1)]


def tri(x_base, x_tip, cy, hh):
    return [(x_base, cy - hh), (x_tip, cy), (x_base, cy + hh)]


# ---------------------------------------------------------------- coordinates
yy, xx = np.mgrid[0:S, 0:S].astype(np.float32)
X = (xx + 0.5 - C) / UNIT
Y = (yy + 0.5 - C) / UNIT
R = np.sqrt(X * X + Y * Y)
DX = X / np.maximum(R, 1e-6)
DY = Y / np.maximum(R, 1e-6)


def smooth(e0, e1, v):
    t = np.clip((v - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


def tint(v):
    """Grey with the original's slight cool tint: (v, v-1, v+5)."""
    return np.stack([v, v - 1, v + 5], axis=-1)


R_OUT, R_BTN = 0.84, 0.515

# ---------------------------------------------------------------- bezel
bez = 79 + 31 * (1 - smooth(-1.0, -0.35, Y))       # bright top -> even body
bez += 5 * (-X).clip(-1, 1)                          # light from the left
bez -= 4 * smooth(0.2, 1.0, Y)                       # slightly darker towards the bottom

# ---------------------------------------------------------------- wheel ring
L1 = np.array([-0.2, -0.98]); L1 /= np.linalg.norm(L1)
L2 = np.array([0.1, 1.0]); L2 /= np.linalg.norm(L2)
up = np.clip(DX * L1[0] + DY * L1[1], 0, 1)
down = np.clip(DX * L2[0] + DY * L2[1], 0, 1)
ring = np.full_like(X, 31.0)
ring += 58 * up ** 2 * smooth(0.70, 0.83, R)          # outer lip catches the light (top / upper-left)
ring += 40 * down ** 1.5 * (1 - smooth(R_BTN + 0.005, 0.67, R))  # inner bevel lit at the bottom
ring += 6 * smooth(0.60, 0.80, R) * (1 - smooth(0.80, R_OUT, R))
ring -= 8 * (1 - smooth(R_BTN, R_BTN + 0.03, R)) * np.clip(-DY, 0, 1)  # shadow under the button's top edge

# ---------------------------------------------------------------- center button
btn = 98 - 24 * smooth(-R_BTN, R_BTN, Y)
rim = smooth(R_BTN - 0.03, R_BTN - 0.005, R) * (1 - smooth(R_BTN - 0.005, R_BTN, R))
btn += 22 * rim * np.clip(DY, 0, 1) ** 1.5             # lit lower rim
btn -= 14 * rim * np.clip(-DY, 0, 1) ** 1.5            # dark upper rim

# ---------------------------------------------------------------- compose
m_tile = mask(rounded_square)
m_ring = soften(mask(circle(R_OUT)), 0.005)
m_btn = soften(mask(circle(R_BTN)), 0.006)

col = tint(bez)
col = col * (1 - m_ring[..., None]) + tint(ring) * m_ring[..., None]
col = col * (1 - m_btn[..., None]) + tint(btn) * m_btn[..., None]

# ---------------------------------------------------------------- glyphs
LIGHT = np.array([214, 218, 221], np.float32)
DARK = np.array([26, 26, 24], np.float32)
ICON_R = 0.68
arm, th = 0.068, 0.017
plus = polys(rect(-arm, -ICON_R - th, arm, -ICON_R + th), rect(-th, -ICON_R - arm, th, -ICON_R + arm))
minus = polys(rect(-arm, ICON_R - th, arm, ICON_R + th))
hh = 0.047


def skip(cx, s):
    # two triangles + bar, s=+1 forward, -1 back
    return [
        tri(cx - s * 0.095, cx - s * 0.025, 0, hh),
        tri(cx - s * 0.025, cx + s * 0.045, 0, hh),
        rect(min(cx + s * 0.055, cx + s * 0.08), -hh, max(cx + s * 0.055, cx + s * 0.08), hh),
    ]


skips = polys(*skip(ICON_R, 1), *skip(-ICON_R, -1))
ph = 0.068
playpause = polys(tri(-0.125, 0.0, 0, ph), rect(0.055, -ph, 0.092, ph), rect(0.125, -ph, 0.162, ph))

m_light = np.clip(mask(plus) + mask(minus) + mask(skips), 0, 1)
m_dark = mask(playpause)
# faint emboss: a darker copy just above the light glyphs, lighter just below the dark one
shift = max(1, round(UNIT * 0.008))
m_light_sh = np.roll(m_light, -shift, axis=0) * (1 - m_light)
m_dark_hl = np.roll(m_dark, shift, axis=0) * (1 - m_dark)
col = col * (1 - 0.35 * m_light_sh[..., None])
col = col + (np.array([255, 255, 255]) - col) * (0.10 * m_dark_hl[..., None])
col = col * (1 - m_light[..., None]) + LIGHT * m_light[..., None]
col = col * (1 - m_dark[..., None]) + DARK * m_dark[..., None]

# subtle noise to avoid gradient banding
rng = np.random.default_rng(1)
col += rng.normal(0, 0.6, col.shape[:2])[..., None]

rgba = np.dstack([np.clip(col, 0, 255), m_tile * 255]).round().astype(np.uint8)
Image.fromarray(rgba, "RGBA").save(OUT, optimize=True)
print("wrote", OUT, S)
