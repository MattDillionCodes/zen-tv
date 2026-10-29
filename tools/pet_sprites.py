#!/usr/bin/env python3
"""
Generates Zen's 8-bit side-profile pets (50x32, facing left) from simple shapes, then writes:
  app/src/main/java/com/zen/tv/PetSprites.kt   (one character per pixel; generated)
  screenshots/pet-sprites.png                  (5x preview on a dark background)

  python3 -I tools/pet_sprites.py

Each part gets its own 1px outline plus top-left light / bottom-right shade, the way hand-made
pixel art separates parts that share a colour. Far-side legs and ears use the shade colour so the
animal reads as having depth. Eyes are left out: Pet.kt draws them so they can blink and squint.

Poses per pet: STAND, WALK, LOOK (head turned to the viewer), SIT, SIT_LOOK, GROOM, STRETCH,
YAWN, SLEEP, plus POUNCE for the cat. Toys: BUTTERFLY and BALL.
"""
import math
import pathlib
import struct
import zlib

GW, GH = 50, 32
XOFF = 2                     # logical x starts at -2 so whiskers and nose can poke past x=0
ROOT = pathlib.Path(__file__).resolve().parent.parent

# Must match PetPalette in Pet.kt (PetSpritesTest checks every character is in it).
PALETTE = {
    "O": 0x3A3A44, "K": 0x16161C, "H": 0xFFFFFF,
    "W": 0xDCDCE2, "L": 0xF2F2F6, "G": 0xB0B0BA, "S": 0x8E8E9A,   # cat: tabby greys
    "C": 0xE8DFD0, "D": 0xC9BCA8, "B": 0x9C7F63, "E": 0x7A6250,   # dog: cream + brown
    "P": 0xE8909E, "A": 0xEFAAB4,                                 # nose / tongue, blush
    "R": 0xC2453A, "U": 0x4F7FC9, "Y": 0xE8C15A,                  # collars, bell / tag, toys
}


# ---------- shapes ----------

def ell(cx, cy, rx, ry):
    return lambda x, y: ((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2 <= 1


def rell(cx, cy, rx, ry, deg):
    """Ellipse rotated by deg (negative tips the top/left end down-left on screen)."""
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)

    def inside(x, y):
        dx, dy = x - cx, y - cy
        u, v = dx * c + dy * s, -dx * s + dy * c
        return (u / rx) ** 2 + (v / ry) ** 2 <= 1
    return inside


def seg(p0, p1, r):
    """Thick line segment (a limb)."""
    (x0, y0), (x1, y1) = p0, p1
    L2 = (x1 - x0) ** 2 + (y1 - y0) ** 2 or 1e-9

    def inside(x, y):
        t = max(0, min(1, ((x - x0) * (x1 - x0) + (y - y0) * (y1 - y0)) / L2))
        return (x - x0 - t * (x1 - x0)) ** 2 + (y - y0 - t * (y1 - y0)) ** 2 <= r * r
    return inside


def poly(points):
    def inside(x, y):
        hit = False
        for (x1, y1), (x2, y2) in zip(points, points[1:] + points[:1]):
            if (y1 > y) != (y2 > y) and x < x1 + (y - y1) * (x2 - x1) / (y2 - y1):
                hit = not hit
        return hit
    return inside


def shift(points, dx, dy):
    return [(x + dx, y + dy) for (x, y) in points]


def mirror_poly(points, axis):
    return [(2 * axis - x, y) for (x, y) in points]


def curve(p0, p1, p2, p3, r):
    """Thick cubic bezier; returns (inside, param) where param(x, y) is the nearest t."""
    samples = []
    for i in range(101):
        t = i / 100
        a, b, c, d = (1 - t) ** 3, 3 * t * (1 - t) ** 2, 3 * t * t * (1 - t), t ** 3
        samples.append((t, a * p0[0] + b * p1[0] + c * p2[0] + d * p3[0], a * p0[1] + b * p1[1] + c * p2[1] + d * p3[1]))

    def nearest(x, y):
        return min(samples, key=lambda s: (s[1] - x) ** 2 + (s[2] - y) ** 2)

    def inside(x, y):
        _, sx, sy = nearest(x, y)
        return (sx - x) ** 2 + (sy - y) ** 2 <= r * r

    return inside, lambda x, y: nearest(x, y)[0]


def scaled(points, k=1.5):
    return tuple((x * k, y * k) for (x, y) in points)


class Sprite:
    XS = range(-XOFF, GW - XOFF)

    def __init__(self):
        self.px = {}

    def ok(self, x, y):
        return -XOFF <= x < GW - XOFF and 0 <= y < GH

    def fill(self, pts, fill, shade=None, light=None, ring=True):
        pts = {p for p in pts if self.ok(*p)}
        for (x, y) in pts:
            if shade and (x + 1, y + 2) not in pts:
                self.px[(x, y)] = shade
            elif light and (x - 1, y - 1) not in pts:
                self.px[(x, y)] = light
            else:
                self.px[(x, y)] = fill
        if ring:
            for (x, y) in pts:
                for q in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
                    if q not in pts and self.ok(*q):
                        self.px[q] = "O"
        return pts

    def shape(self, inside, fill, shade=None, light=None, ring=True, within=None):
        pts = {(x, y) for y in range(GH) for x in self.XS
               if inside(x + 0.5, y + 0.5) and (within is None or (x, y) in within)}
        return self.fill(pts, fill, shade, light, ring)

    def dots(self, ch, *xy, dx=0, dy=0):
        for x, y in xy:
            if self.ok(x + dx, y + dy):
                self.px[(x + dx, y + dy)] = ch

    def recolor(self, cols, rows, ch):
        for x in cols:
            for y in rows:
                if (x, y) in self.px and self.px[(x, y)] != "O":
                    self.px[(x, y)] = ch

    def rows(self):
        return ["".join(self.px.get((x, y), ".") for x in self.XS) for y in range(GH)]


def leg(s, hip_x, foot_dx, fur, shade, paw, lift=0, top=21, bottom=29, width=3):
    """A slanted 3px leg from the hip down to a paw; lift raises the paw mid-stride."""
    pts = set()
    for y in range(top, bottom + 1 - lift):
        f = (y - top) / max(1, bottom - top)
        x = round(hip_x + foot_dx * f)
        for k in range(width):
            pts.add((x + k, y))
    s.fill(pts, fur, shade)
    foot, py = round(hip_x + foot_dx), bottom - lift
    s.dots(paw, *[(foot + k, py) for k in range(-1, width)])
    s.dots("O", (foot - 2, py), (foot + width, py), *[(foot + k, py + 1) for k in range(-1, width)])
    s.dots(shade, (foot, py))                                   # toe line


def limb(s, p0, p1, fur, shade, paw, r=1.6):
    """A leg at any angle, ending in a paw."""
    s.shape(seg(p0, p1, r), fur, shade)
    s.shape(ell(p1[0], p1[1], 2.2, 1.5), paw)


def back_stripes(s, body, xs, length=4):
    for x in xs:
        col = [y for (bx, y) in body if bx == x]
        if col:
            s.dots("S", *[(xx, min(col) + 1 + k) for xx in (x, x + 1) for k in range(length)])


def tail(s, pts, r, fur, shade, light, stripes=None, tip=None):
    inside, param = curve(*pts, r=r)
    t = s.shape(inside, fur, shade, light)
    for p in t:
        k = param(p[0] + 0.5, p[1] + 0.5)
        if stripes and 0.25 < k and (k * 6) % 1 < 0.3:
            s.dots(stripes, p)
        if tip and k > 0.8:
            s.dots(tip, p)


# Walk cycle: (near front, far front, near back, far back) as (foot dx, lift).
STRIDE = [((-3, 0), (3, 0), (3, 0), (-3, 0)),
          ((0, 2), (0, 0), (0, 0), (0, 2)),
          ((3, 0), (-3, 0), (-3, 0), (3, 0)),
          ((0, 0), (0, 2), (0, 2), (0, 0))]
STAND = ((0, 0), (0, 0), (0, 0), (0, 0))


# ---------- species ----------

class Cat:
    fur, shade, light, belly, far, far_shade = "W", "G", "L", "L", "G", "S"
    collar = "R"
    tails = [scaled(((25, 11), (29, 10.5), (30.5, 5), (28, 1.5))), scaled(((25, 11), (30, 11.5), (31.5, 6), (30.5, 1.8)))]
    walk_tail = scaled(((25, 11), (29.5, 10), (31, 6), (30, 3)))
    tail_r, tail_stripes, tail_tip = 1.9, "S", None

    @staticmethod
    def markings(s, body):
        back_stripes(s, body, (21, 25, 29, 33, 36))

    @staticmethod
    def side_head(s, dx=0, dy=0, mouth="closed"):
        s.shape(poly(shift([(12.6, 5.4), (14.7, -0.3), (17.4, 6.6)], dx, dy)), "G")          # far ear
        head = s.shape(ell(10.2 + dx, 11.1 + dy, 7.3, 6.2), "W", "G", "L")
        s.shape(poly(shift([(5.4, 6.9), (6.9, 0.3), (12.3, 5.4)], dx, dy)), "W", light="L")  # near ear
        s.shape(poly(shift([(6.8, 6.0), (7.4, 2.4), (10.2, 5.4)], dx, dy)), "P", ring=False)
        s.shape(ell(4.8 + dx, 13.6 + dy, 3.3, 2.5), "L", ring=False, within=head)          # muzzle
        s.dots("S", (10, 6), (10, 7), (13, 6), (13, 7), (12, 8), (15, 9), (15, 10), (14, 13), (15, 13), (15, 15), dx=dx, dy=dy)
        s.dots("P", (1, 12), (2, 12), (3, 12), (2, 13), dx=dx, dy=dy)                       # nose
        if mouth == "yawn":
            s.dots("K", (1, 14), (2, 14), (3, 14), (4, 14), (1, 15), (2, 15), (3, 15), (4, 15), (5, 15),
                   (2, 16), (3, 16), (4, 16), dx=dx, dy=dy)
            s.dots("P", (2, 16), (3, 16), dx=dx, dy=dy)
            s.dots("H", (1, 14), (4, 14), dx=dx, dy=dy)                                     # little fangs
        else:
            s.dots("K", (2, 15), (3, 15), (4, 15), (5, 14), dx=dx, dy=dy)                   # mouth
        s.dots("A", (8, 14), (9, 14), (10, 14), dx=dx, dy=dy)                               # blush
        s.dots("G", (-1, 13), (-2, 13), (0, 14), (-1, 16), (-2, 17), (0, 15), dx=dx, dy=dy)  # whiskers

    @staticmethod
    def front_head(s, dx=0, dy=0):
        ax = 10.2 + dx
        left_ear = shift([(4.4, 8.4), (5.2, 1.0), (9.6, 5.2)], dx, dy)
        inner = shift([(5.6, 6.6), (6.0, 3.0), (8.6, 5.4)], dx, dy)
        for ear, inn in ((left_ear, inner), (mirror_poly(left_ear, ax), mirror_poly(inner, ax))):
            s.shape(poly(ear), "W", light="L")
            s.shape(poly(inn), "P", ring=False)
        head = s.shape(ell(ax, 11.6 + dy, 6.9, 6.0), "W", "G", "L")
        s.shape(ell(ax, 14.7 + dy, 3.4, 2.1), "L", ring=False, within=head)
        s.dots("S", (10, 6), (10, 7), (8, 7), (12, 7), (4, 11), (16, 11), (4, 13), (16, 13), dx=dx, dy=dy)
        s.dots("P", (9, 13), (10, 13), (11, 13), (10, 14), dx=dx, dy=dy)
        s.dots("K", (10, 15), (9, 16), (11, 16), (8, 15), (12, 15), dx=dx, dy=dy)
        s.dots("A", (5, 14), (6, 14), (14, 14), (15, 14), dx=dx, dy=dy)
        s.dots("G", (1, 13), (2, 14), (1, 16), (2, 15), (19, 13), (18, 14), (19, 16), (18, 15), dx=dx, dy=dy)

    @staticmethod
    def collar_on(s, dx=0, dy=0):
        s.recolor(range(15 + dx, 18 + dx), range(13 + dy, 18 + dy), "R")
        s.dots("Y", (15, 18), (16, 18), (17, 18), (15, 19), (17, 19), dx=dx, dy=dy)      # bell
        s.dots("K", (16, 19), dx=dx, dy=dy)
        s.dots("O", (15, 20), (16, 20), (17, 20), dx=dx, dy=dy)


class Dog:
    fur, shade, light, belly, far, far_shade = "C", "D", "H", "H", "D", "E"
    collar = "U"
    tails = [scaled(((26, 10), (28.5, 8.5), (29.5, 6), (29, 3.5))), scaled(((26, 10), (29.5, 9.5), (31, 7.5), (31, 5)))]
    walk_tail = scaled(((26, 10), (29, 8.6), (30.5, 6.5), (30.2, 4.2)))
    tail_r, tail_stripes, tail_tip = 2.1, None, "H"

    @staticmethod
    def markings(s, body):
        s.shape(ell(29.2, 13.8, 7.2, 3.9), "B", "E", ring=False, within=body)              # brown saddle

    @staticmethod
    def side_head(s, dx=0, dy=0, mouth="closed"):
        s.shape(ell(11.1 + dx, 11.1 + dy, 7.0, 6.2), "C", "D", "H")
        s.shape(poly(shift([(-0.5, 11.4), (7.8, 10.8), (9.0, 15.6), (1.5, 16.8)], dx, dy)), "H", "C")  # snout
        s.dots("K", (-1, 11), (0, 11), (1, 11), (-1, 12), (0, 12), (1, 12), (0, 10), dx=dx, dy=dy)     # nose
        s.dots("H", (-1, 11), dx=dx, dy=dy)
        if mouth == "yawn":
            s.dots("K", (1, 14), (2, 14), (3, 14), (4, 14), (5, 14), (1, 15), (2, 15), (3, 15), (4, 15), (5, 15),
                   (2, 16), (3, 16), (4, 16), (5, 16), dx=dx, dy=dy)
            s.dots("P", (2, 16), (3, 16), (4, 16), dx=dx, dy=dy)
        else:
            s.dots("K", (2, 15), (3, 15), (4, 15), (5, 15), (6, 14), dx=dx, dy=dy)                     # mouth
        s.dots("E", (6, 7), (7, 7), (8, 7), dx=dx, dy=dy)                                              # eyebrow
        s.shape(poly(shift([(12.6, 4.8), (17.1, 5.7), (18.3, 15.9), (15.9, 17.7), (13.8, 12.3)], dx, dy)),
                "B", "E")                                                                              # floppy ear

    @staticmethod
    def front_head(s, dx=0, dy=0):
        ax = 11.1 + dx
        s.shape(ell(ax, 11.6 + dy, 6.8, 6.0), "C", "D", "H")
        s.shape(ell(ax, 15.3 + dy, 3.9, 2.6), "H", "C", ring=False)
        s.dots("K", (10, 13), (11, 13), (12, 13), (10, 14), (11, 14), (12, 14), dx=dx, dy=dy)
        s.dots("H", (10, 13), dx=dx, dy=dy)
        s.dots("K", (11, 15), (10, 16), (12, 16), (9, 16), (13, 16), dx=dx, dy=dy)
        s.dots("E", (7, 8), (8, 8), (14, 8), (15, 8), dx=dx, dy=dy)
        left_ear = shift([(4.6, 5.4), (8.0, 4.8), (8.0, 14.6), (5.6, 17.2), (3.2, 13.2)], dx, dy)
        for ear in (left_ear, mirror_poly(left_ear, ax)):
            s.shape(poly(ear), "B", "E")

    @staticmethod
    def collar_on(s, dx=0, dy=0):
        s.recolor(range(16 + dx, 19 + dx), range(13 + dy, 19 + dy), "U")
        s.dots("Y", (16, 19), (17, 19), (18, 19), (17, 20), dx=dx, dy=dy)                 # tag
        s.dots("O", (16, 20), (18, 20), (17, 21), dx=dx, dy=dy)


# ---------- poses ----------

def standing(sp, tail_pts, legs=STAND, head="side", mouth="closed"):
    s = Sprite()
    (nf, ff, nb, fb) = legs
    leg(s, 18, ff[0], sp.far, sp.far_shade, sp.far, ff[1])      # far legs first, in shade
    leg(s, 35, fb[0], sp.far, sp.far_shade, sp.far, fb[1])
    tail(s, tail_pts, sp.tail_r, sp.fur, sp.shade, sp.light, sp.tail_stripes, sp.tail_tip)
    body = s.shape(ell(26.6, 17.4, 12.9, 6.1), sp.fur, sp.shade, sp.light)
    sp.markings(s, body)
    s.shape(ell(26.2, 22.0, 9.7, 2.4), sp.belly, ring=False, within=body)
    leg(s, 15, nf[0], sp.fur, sp.shade, sp.belly, nf[1])        # near legs over the body
    leg(s, 32, nb[0], sp.fur, sp.shade, sp.belly, nb[1])
    s.shape(ell(16.0, 14.2, 4.9, 5.5), sp.fur, sp.shade, sp.light)  # neck / chest
    s.dots(sp.belly, (13, 17), (14, 18), (13, 19))
    sp.collar_on(s)                                             # before the head, so it sits under the chin
    (sp.front_head(s) if head == "front" else sp.side_head(s, mouth=mouth))
    return s.rows()


SIT_DX = 4  # head and collar shift right when sitting upright


def sitting(sp, tail_tip=0.0, head="side", groom=None):
    """Sitting upright, tail wrapped round the paws. groom: None, or a frame index (0/1)."""
    s = Sprite()
    if not (groom is not None and sp is Cat):
        s.shape(seg((21, 20), (21, 28.6), 1.5), sp.far, sp.far_shade)          # far front leg
        s.shape(ell(21, 29.2, 2.2, 1.3), sp.far)
    torso = s.shape(rell(24, 19.6, 6.8, 9.4, -24), sp.fur, sp.shade, sp.light)
    s.shape(ell(28.2, 24.6, 7.0, 5.2), sp.fur, sp.shade, sp.light)            # haunch
    if sp is Dog:
        s.shape(rell(27, 16, 4.2, 6.0, -24), "B", "E", ring=False, within=torso)
    else:
        s.dots("S", *[(x, y) for (x, y) in torso if (x + y) % 5 == 0 and x >= 27])
    s.shape(rell(20.6, 21, 3.0, 5.2, -24), sp.belly, ring=False, within=torso)
    scratching = groom is not None and sp is Dog
    if not scratching:
        s.shape(ell(24.2, 29.2, 4.2, 1.4), sp.fur, light=sp.light)            # hind foot
    if not (groom is not None and sp is Cat):
        s.shape(seg((18, 20), (18, 28.6), 1.6), sp.fur, sp.shade)              # near front leg
        s.shape(ell(17.8, 29.2, 2.3, 1.3), sp.belly)
    tail(s, ((33, 27.5), (38, 30.4 + tail_tip), (28, 30.6), (19.5, 30.2 - tail_tip)),
         1.7 if sp is Cat else 1.9, sp.fur, sp.shade, sp.light, sp.tail_stripes, sp.tail_tip)
    s.shape(ell(19.0 + 0.4, 14.4, 4.8, 5.4), sp.fur, sp.shade, sp.light)      # chest
    sp.collar_on(s, dx=SIT_DX)
    if head == "front":
        sp.front_head(s, dx=SIT_DX)
    else:
        sp.side_head(s, dx=SIT_DX)
    if groom is not None and sp is Cat:                          # paw raised to the mouth, tongue out
        s.shape(seg((21, 20), (21, 28.6), 1.5), sp.far, sp.far_shade)
        s.shape(ell(21, 29.2, 2.2, 1.3), sp.far)
        paw = (10.5, 15.0) if groom == 0 else (11.5, 16.4)
        limb(s, (18, 20), (14, 17.5), sp.fur, sp.shade, sp.fur)
        limb(s, (14, 17.5), paw, sp.fur, sp.shade, sp.belly, r=1.5)
        if groom == 0:
            s.dots("P", (7, 16), (8, 16))                        # tongue on the paw
    if scratching:                                               # back leg up behind the ear
        foot = (24.5, 12.2) if groom == 0 else (25.5, 14.2)
        limb(s, (28, 24), (27, 18), sp.fur, sp.shade, sp.fur, r=1.8)
        limb(s, (27, 18), foot, sp.fur, sp.shade, sp.belly, r=1.5)
    return s.rows()


def stretching(sp):
    """Play-bow stretch: front legs out along the floor, rear up, tail high."""
    s = Sprite()
    limb(s, (37.5, 21), (38, 28.8), sp.far, sp.far_shade, sp.far)              # far back leg
    tail_pts = ((39, 14), (42, 10), (44, 6), (42, 2)) if sp is Cat else ((39, 14), (42, 11), (44, 8), (44, 5))
    tail(s, tail_pts, sp.tail_r, sp.fur, sp.shade, sp.light, sp.tail_stripes, sp.tail_tip)
    body = s.shape(rell(27, 18.6, 13, 5.6, -14), sp.fur, sp.shade, sp.light)
    sp.markings(s, body) if sp is Dog else back_stripes(s, body, (23, 27, 31, 35), length=3)
    limb(s, (34.5, 21), (35, 28.8), sp.fur, sp.shade, sp.belly)               # near back leg
    limb(s, (17, 23), (5, 28.8), sp.far, sp.far_shade, sp.far)                 # far front leg
    limb(s, (15, 23.5), (2.5, 29.0), sp.fur, sp.shade, sp.belly)              # near front leg
    s.shape(ell(15.6, 20.2, 4.8, 4.4), sp.fur, sp.shade, sp.light)            # chest
    sp.collar_on(s, dy=5)
    sp.side_head(s, dy=6)
    return s.rows()


def sleeping(sp, breath):
    s = Sprite()
    pts = scaled(((25, 16.5), (29.5, 19.6), (19, 20.6), (9, 19.8))) if sp is Cat \
        else scaled(((26, 16.5), (29.5, 19.6), (22, 20.6), (16, 20.0)))
    body = s.shape(ell(25.8, 23.4 - breath, 13.2, 5.7 + breath), sp.fur, sp.shade, sp.light)
    if sp is Cat:
        back_stripes(s, body, (21, 25, 29, 33, 36), length=3)
    else:
        s.shape(ell(28.5, 20.1, 7.2, 3.3), "B", "E", ring=False, within=body)
    tail(s, pts, 1.8 if sp is Cat else 2.0, sp.fur, sp.shade, sp.light, sp.tail_stripes, sp.tail_tip)
    s.shape(ell(6.9, 27.9, 3.7, 1.8), sp.belly)                                 # front paws
    sp.side_head(s, dy=10)
    return s.rows()


def pouncing():
    """Cat mid-leap: front paws reaching, back legs pushing off, tail streaming."""
    sp, s = Cat, Sprite()
    limb(s, (36, 18.5), (45, 23), sp.far, sp.far_shade, sp.far)
    limb(s, (17, 19), (8, 24.5), sp.far, sp.far_shade, sp.far)
    tail(s, ((38, 14), (42, 12.5), (45, 12), (47.4, 10.6)), 1.8, sp.fur, sp.shade, sp.light, sp.tail_stripes)
    body = s.shape(ell(25, 16, 13.5, 5.2), sp.fur, sp.shade, sp.light)
    back_stripes(s, body, (21, 25, 29, 33), length=3)
    limb(s, (33.5, 19), (42, 24.5), sp.fur, sp.shade, sp.belly)
    limb(s, (15, 19), (4.5, 23.5), sp.fur, sp.shade, sp.belly)
    s.shape(ell(15, 15, 4.8, 5.0), sp.fur, sp.shade, sp.light)
    sp.collar_on(s, dx=-1, dy=1)
    sp.side_head(s, dx=-1, dy=1)
    return s.rows()


BUTTERFLY = [["YY...YY", "YYYKYYY", ".YYKYY.", "..YKY..", "...K..."],      # wings up
             [".......", "..YKY..", ".YYKYY.", "YYYKYYY", "...K..."]]      # wings down
BALL = [["..OOO..", ".ORRHO.", "ORRRHRO", "ORRRRRO", "OHRRRRO", ".OHRRO.", "..OOO.."],   # 7x7, two roll frames
        ["..OOO..", ".OHRRO.", "OHRRRRO", "ORRRRRO", "ORRRRHO", ".ORRHO.", "..OOO.."]]


# Top-left of each 3x3 eye Pet.kt draws (grid coordinates, i.e. logical x + XOFF).
def g(points):
    return [(x + XOFF, y) for (x, y) in points]


EYES = {
    "SIDE": g([(6, 9)]),
    "SIT": g([(6 + SIT_DX, 9)]),
    "STRETCH": g([(6, 15)]),
    "POUNCE": g([(5, 10)]),
    "SLEEP": g([(6, 19)]),
    "CAT_LOOK": g([(6, 10), (12, 10)]),
    "DOG_LOOK": g([(7, 10), (13, 10)]),
    "CAT_SIT_LOOK": g([(6 + SIT_DX, 10), (12 + SIT_DX, 10)]),
    "DOG_SIT_LOOK": g([(7 + SIT_DX, 10), (13 + SIT_DX, 10)]),
}
DOG_TONGUE = (11 + XOFF, 17)   # front-facing face, while happy


def ground_span(rows):
    cols = [x for line in rows[-4:] for x, ch in enumerate(line) if ch != "."]
    return min(cols), max(cols) + 1


# ---------- output ----------

def kotlin_frames(frames):
    def one(rows):
        return "listOf(\n" + "".join(f'            "{r}",\n' for r in rows) + "        )"
    return "listOf(\n" + "".join(f"        {one(f)},\n" for f in frames) + "    )"


def kotlin_points(points):
    return "listOf(" + ", ".join(f"{x} to {y}" for x, y in points) + ")"


def png(path, tiles, scale=5, pad=2):
    cols = 7
    nrows = (len(tiles) + cols - 1) // cols
    w = cols * (GW + pad) + pad
    h = nrows * (GH + pad) + pad
    bg = 0x14161A
    img = [[bg] * w for _ in range(h)]
    for i, rows in enumerate(tiles):
        ox, oy = pad + (i % cols) * (GW + pad), pad + (i // cols) * (GH + pad)
        for y, line in enumerate(rows):
            for x, ch in enumerate(line):
                if ch in PALETTE:
                    img[oy + y][ox + x] = PALETTE[ch]
    raw = b"".join(b"\x00" + b"".join(struct.pack(">I", img[y // scale][x // scale])[1:] for x in range(w * scale))
                   for y in range(h * scale))

    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w * scale, h * scale, 8, 2, 0, 0, 0))
                     + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


EYE_ART = {"open": ["HKK", "KKK", "KKK"], "happy": [".K.", "K.K", "..."], "shut": ["...", "K.K", ".K."]}


def stamp(rows, points, art, at=None):
    grid = [list(r) for r in rows]
    for (x, y) in points:
        for dy, line in enumerate(art):
            for dx, ch in enumerate(line):
                if ch != "." and 0 <= y + dy < GH and 0 <= x + dx < GW:
                    grid[y + dy][x + dx] = ch
    return ["".join(r) for r in grid]


if __name__ == "__main__":
    sprites = {}
    for name, sp in (("CAT", Cat), ("DOG", Dog)):
        sprites[f"{name}_STAND"] = [standing(sp, t) for t in sp.tails]
        sprites[f"{name}_WALK"] = [standing(sp, sp.walk_tail, legs) for legs in STRIDE]
        sprites[f"{name}_LOOK"] = [standing(sp, t, head="front") for t in sp.tails]
        sprites[f"{name}_YAWN"] = [standing(sp, sp.tails[0], mouth="yawn")]
        sprites[f"{name}_SIT"] = [sitting(sp, 0.0), sitting(sp, 0.8)]
        sprites[f"{name}_SIT_LOOK"] = [sitting(sp, 0.0, head="front"), sitting(sp, 0.8, head="front")]
        sprites[f"{name}_GROOM"] = [sitting(sp, groom=0), sitting(sp, groom=1)]
        sprites[f"{name}_STRETCH"] = [stretching(sp)]
        sprites[f"{name}_SLEEP"] = [sleeping(sp, 0), sleeping(sp, 0.6)]
    sprites["CAT_POUNCE"] = [pouncing()]

    spans = {k: ground_span(sprites[f"CAT_{k}"][0]) for k in ("STAND", "SIT", "STRETCH", "SLEEP")}
    out = ["// Generated by tools/pet_sprites.py. Edit the generator, then re-run it.",
           "package com.zen.tv", "",
           f"/** {GW}x{GH} side-profile sprites facing left, one character per pixel ('.' = transparent). */",
           "internal object PetSprites {",
           f"    const val WIDTH = {GW}", f"    const val HEIGHT = {GH}", "",
           "    /** Top-left of each 3x3 eye that Pet.kt draws on top, per pose. */"]
    for name, pts in EYES.items():
        out.append(f"    val EYES_{name} = {kotlin_points(pts)}")
    out += [f"    val DOG_TONGUE = {DOG_TONGUE[0]} to {DOG_TONGUE[1]}",
            "    /** Columns the ground shadow spans (from, until), per pose. */"]
    out += [f"    val SHADOW_{k} = {a} to {b}" for k, (a, b) in spans.items()]
    out.append("")
    for name, frames in sprites.items():
        out.append(f"    val {name} = {kotlin_frames(frames)}\n")
    out.append(f"    val BUTTERFLY = {kotlin_frames(BUTTERFLY)}\n")
    out.append(f"    val BALL = {kotlin_frames(BALL)}\n")
    out.append("}")
    kt = ROOT / "app/src/main/java/com/zen/tv/PetSprites.kt"
    kt.write_text("\n".join(out) + "\n")
    print("wrote", kt.relative_to(ROOT))

    preview = []
    for pet in ("CAT", "DOG"):
        look = EYES[f"{pet}_LOOK"]
        preview += [
            stamp(sprites[f"{pet}_STAND"][0], EYES["SIDE"], EYE_ART["open"]),
            stamp(sprites[f"{pet}_LOOK"][0], look, EYE_ART["open"]),
            stamp(sprites[f"{pet}_SIT"][0], EYES["SIT"], EYE_ART["open"]),
            stamp(sprites[f"{pet}_SIT_LOOK"][0], EYES[f"{pet}_SIT_LOOK"], EYE_ART["open"]),
            stamp(sprites[f"{pet}_GROOM"][0], EYES["SIT"], EYE_ART["shut"]),
            stamp(sprites[f"{pet}_STRETCH"][0], EYES["STRETCH"], EYE_ART["shut"]),
            stamp(sprites[f"{pet}_YAWN"][0], EYES["SIDE"], EYE_ART["shut"]),
        ]
    preview.append(stamp(sprites["CAT_POUNCE"][0], EYES["POUNCE"], EYE_ART["open"]))
    toys = Sprite()
    preview.append(stamp(stamp(toys.rows(), [(10, 10)], BUTTERFLY[0]), [(30, 23)], BALL[0]))
    sheet = ROOT / "screenshots/pet-sprites.png"
    png(sheet, preview)
    print("wrote", sheet.relative_to(ROOT))
