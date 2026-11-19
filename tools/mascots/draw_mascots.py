#!/usr/bin/env python3
"""
Draws the mascots of Merks as 32 x 32 pixel art and writes them into the app's source
(app/src/main/java/de/silvertax/merks/MascotArt.kt), one letter a pixel. Also writes a preview picture.

Each animal is drawn from simple shapes without smoothing; afterwards every pixel at the edge of the
figure gets a dark outline. Three faces each: idle, blink and happy.

    python3 tools/mascots/draw_mascots.py [preview.png]
"""
import os
import sys

from PIL import Image, ImageDraw

N = 32
CLEAR = (0, 0, 0, 0)


def canvas():
    img = Image.new("RGBA", (N, N), CLEAR)
    return img, ImageDraw.Draw(img)


def outline(img, color):
    """Every clear pixel next to the figure becomes the outline."""
    px = img.load()
    edge = []
    for y in range(N):
        for x in range(N):
            if px[x, y][3] != 0:
                continue
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = x + dx, y + dy
                if 0 <= nx < N and 0 <= ny < N and px[nx, ny][3] != 0 and px[nx, ny] != color:
                    edge.append((x, y))
                    break
    for p in edge:
        px[p] = color


def eyes(d, face, boxes, dark, shine=(255, 255, 255, 255), size_happy=1):
    for (x0, y0, x1, y1) in boxes:
        xm = (x0 + x1) // 2
        ym = (y0 + y1) // 2
        if face == "idle":
            d.ellipse((x0, y0, x1, y1), fill=dark)
            d.point((x0 + 1, y0 + 1), fill=shine)
        elif face == "blink":
            d.line((x0, ym + 1, x1, ym + 1), fill=dark)
            d.point((x0, ym), fill=dark)
            d.point((x1, ym), fill=dark)
        else:  # happy: closed, smiling eyes
            d.line((x0, ym + 1, xm, ym - 1), fill=dark)
            d.line((xm, ym - 1, x1, ym + 1), fill=dark)
            if size_happy > 1:
                d.line((x0, ym + 2, xm, ym), fill=dark)
                d.line((xm, ym, x1, ym + 2), fill=dark)


def mouth(d, face, cx, y, dark, tongue):
    if face == "happy":
        d.rectangle((cx - 2, y, cx + 1, y + 2), fill=dark)
        d.rectangle((cx - 1, y + 2, cx, y + 2), fill=tongue)
    else:
        d.point((cx - 2, y), fill=dark)
        d.point((cx - 1, y + 1), fill=dark)
        d.point((cx, y + 1), fill=dark)
        d.point((cx + 1, y), fill=dark)


def cat(face):
    O = (58, 46, 42, 255); F = (242, 162, 58, 255); D = (214, 122, 30, 255)
    W = (255, 243, 222, 255); P = (244, 137, 155, 255)
    img, d = canvas()
    # Tail, curled up on the right
    d.ellipse((20, 20, 31, 31), fill=F)
    d.ellipse((22, 22, 29, 29), fill=CLEAR)
    d.rectangle((20, 20, 24, 31), fill=CLEAR)
    # Body
    d.ellipse((7, 18, 25, 32), fill=F, outline=O)
    d.ellipse((11, 21, 21, 32), fill=W)
    # Ears
    d.polygon([(4, 14), (5, 1), (14, 7)], fill=F, outline=O)
    d.polygon([(28, 14), (27, 1), (18, 7)], fill=F, outline=O)
    d.polygon([(6, 10), (7, 4), (11, 8)], fill=P)
    d.polygon([(26, 10), (25, 4), (21, 8)], fill=P)
    # Head
    d.ellipse((3, 4, 29, 25), fill=F, outline=O)
    # Stripes on the forehead
    for x in (13, 16, 19):
        d.line((x, 5, x, 8), fill=D)
    d.line((4, 14, 7, 14), fill=D)
    d.line((25, 14, 28, 14), fill=D)
    # Muzzle and cheeks
    d.ellipse((9, 15, 23, 23), fill=W)
    eyes(d, face, [(8, 11, 11, 15), (21, 11, 24, 15)], O)
    d.rectangle((15, 16, 17, 17), fill=P)
    d.point((16, 18), fill=O)
    mouth(d, face, 17, 19, O, P)
    # Whiskers
    d.line((1, 17, 6, 18), fill=O)
    d.line((1, 20, 6, 19), fill=O)
    d.line((26, 18, 31, 17), fill=O)
    d.line((26, 19, 31, 20), fill=O)
    # Paws
    d.ellipse((10, 28, 14, 31), fill=W, outline=O)
    d.ellipse((18, 28, 22, 31), fill=W, outline=O)
    outline(img, O)
    return img


def owl(face):
    O = (59, 38, 24, 255); B = (139, 94, 60, 255); D = (106, 68, 40, 255); L = (232, 201, 160, 255)
    W = (255, 255, 255, 255); E = (43, 43, 43, 255); Y = (242, 179, 61, 255)
    img, d = canvas()
    # Body and head in one
    d.ellipse((4, 4, 28, 31), fill=B, outline=O)
    # Ear tufts
    d.polygon([(5, 10), (5, 1), (12, 6)], fill=B, outline=O)
    d.polygon([(27, 10), (27, 1), (20, 6)], fill=B, outline=O)
    # Wings
    d.ellipse((2, 15, 9, 29), fill=D, outline=O)
    d.ellipse((23, 15, 30, 29), fill=D, outline=O)
    # Belly with feathers
    d.ellipse((10, 18, 22, 31), fill=L)
    for (x, y) in ((13, 22), (18, 22), (15, 25), (20, 26), (12, 27), (16, 29)):
        d.line((x - 1, y, x, y + 1), fill=D)
        d.line((x, y + 1, x + 1, y), fill=D)
    # Face: light discs and big eyes
    d.ellipse((6, 7, 16, 18), fill=L)
    d.ellipse((16, 7, 26, 18), fill=L)
    if face == "idle":
        d.ellipse((7, 8, 15, 16), fill=W, outline=O)
        d.ellipse((17, 8, 25, 16), fill=W, outline=O)
        d.ellipse((9, 10, 13, 14), fill=E)
        d.ellipse((19, 10, 23, 14), fill=E)
        d.point((10, 11), fill=W)
        d.point((20, 11), fill=W)
    else:
        eyes(d, face, [(8, 10, 14, 14), (18, 10, 24, 14)], O, size_happy=2)
    # Beak
    d.polygon([(14, 15), (18, 15), (16, 19)], fill=Y, outline=O)
    # Feet
    for x in (11, 13, 19, 21):
        d.line((x, 30, x, 31), fill=Y)
    outline(img, O)
    return img


def fox(face):
    O = (58, 36, 24, 255); F = (238, 122, 43, 255); D = (154, 62, 18, 255)
    W = (255, 246, 234, 255); E = (43, 26, 16, 255); P = (244, 137, 155, 255)
    img, d = canvas()
    # Tail with a white tip, behind on the right
    d.ellipse((19, 17, 31, 31), fill=F, outline=O)
    d.ellipse((25, 24, 31, 31), fill=W)
    # Body with a white chest
    d.ellipse((8, 19, 24, 32), fill=F, outline=O)
    d.ellipse((12, 21, 20, 32), fill=W)
    # Big ears with dark tips
    d.polygon([(3, 15), (4, 0), (14, 8)], fill=F, outline=O)
    d.polygon([(29, 15), (28, 0), (18, 8)], fill=F, outline=O)
    d.polygon([(5, 11), (5, 4), (10, 8)], fill=D)
    d.polygon([(27, 11), (27, 4), (22, 8)], fill=D)
    # Head
    d.ellipse((3, 5, 29, 24), fill=F, outline=O)
    # White cheeks and chin, an orange stripe down to the nose
    d.polygon([(4, 15), (16, 17), (28, 15), (24, 22), (16, 26), (8, 22)], fill=W)
    d.polygon([(13, 9), (19, 9), (17, 20), (15, 20)], fill=F)
    eyes(d, face, [(8, 11, 11, 15), (21, 11, 24, 15)], E)
    d.rectangle((15, 19, 17, 20), fill=E)
    mouth(d, face, 17, 22, E, P)
    # Paws
    d.ellipse((10, 28, 14, 31), fill=D, outline=O)
    d.ellipse((18, 28, 22, 31), fill=D, outline=O)
    outline(img, O)
    return img


def hedgehog(face):
    O = (59, 42, 32, 255); S = (122, 82, 54, 255); T = (165, 123, 85, 255)
    F = (242, 211, 168, 255); E = (43, 26, 16, 255); P = (244, 160, 160, 255)
    img, d = canvas()
    # Spikes all around the top: filled points, the outline comes at the end
    for i in range(8):
        x = 2 + i * 3.6
        d.polygon([(x, 13), (x + 3.5, 1 + (i % 2) * 2), (x + 7, 13)], fill=S)
    d.polygon([(1, 19), (1, 8), (8, 13)], fill=S)
    d.polygon([(30, 19), (30, 8), (23, 13)], fill=S)
    # Round body
    d.ellipse((1, 5, 30, 31), fill=S)
    # Lighter streaks along the spikes
    for (x, y) in ((6, 10), (10, 6), (15, 5), (20, 6), (24, 9), (4, 17), (27, 17)):
        d.line((x, y, x + 1, y - 2), fill=T)
    # Face
    d.ellipse((7, 12, 25, 30), fill=F, outline=O)
    eyes(d, face, [(11, 17, 13, 20), (19, 17, 21, 20)], E)
    d.ellipse((9, 22, 11, 24), fill=P)
    d.ellipse((21, 22, 23, 24), fill=P)
    d.ellipse((14, 21, 18, 24), fill=E)
    d.point((15, 22), fill=(255, 255, 255, 255))
    if face == "happy":
        d.rectangle((15, 26, 17, 27), fill=E)
    # Feet
    d.ellipse((7, 28, 12, 31), fill=F, outline=O)
    d.ellipse((20, 28, 25, 31), fill=F, outline=O)
    outline(img, O)
    return img


def seal(face):
    O = (44, 58, 70, 255); G = (150, 168, 182, 255); D = (112, 130, 146, 255)
    L = (222, 230, 236, 255); E = (26, 32, 40, 255); P = (244, 160, 170, 255)
    img, d = canvas()
    # Body, a soft drop, lighter at the front
    d.ellipse((6, 13, 26, 32), fill=G, outline=O)
    d.ellipse((10, 18, 22, 32), fill=L)
    # Small flippers at the sides
    d.ellipse((3, 23, 10, 28), fill=D, outline=O)
    d.ellipse((22, 23, 29, 28), fill=D, outline=O)
    # Round head with a few spots
    d.ellipse((5, 2, 27, 22), fill=G, outline=O)
    for (x, y) in ((9, 5), (21, 4), (24, 9)):
        d.point((x, y), fill=D)
        d.point((x + 1, y), fill=D)
    # Big dark eyes
    if face == "idle":
        d.ellipse((8, 8, 12, 12), fill=E)
        d.ellipse((20, 8, 24, 12), fill=E)
        for (x, y) in ((9, 9), (21, 9)):
            d.point((x, y), fill=(255, 255, 255, 255))
    else:
        eyes(d, face, [(8, 9, 12, 12), (20, 9, 24, 12)], E, size_happy=2)
    # Muzzle with whisker dots and a nose
    d.ellipse((10, 13, 22, 20), fill=L)
    d.rectangle((15, 13, 17, 15), fill=E)
    for (x, y) in ((12, 16), (13, 18), (19, 16), (20, 18)):
        d.point((x, y), fill=D)
    mouth(d, face, 17, 17, E, P)
    # Whiskers
    d.line((2, 15, 9, 16), fill=O)
    d.line((23, 16, 30, 15), fill=O)
    outline(img, O)
    return img


ANIMALS = [
    ("CAT", "cat", "mascot_cat", cat),
    ("OWL", "owl", "mascot_owl", owl),
    ("FOX", "fox", "mascot_fox", fox),
    ("HEDGEHOG", "hedgehog", "mascot_hedgehog", hedgehog),
    ("SEAL", "seal", "mascot_seal", seal),
]
FACES = ["idle", "blink", "happy"]
LETTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"


def encode(images):
    """One palette for the three faces of an animal; each pixel a letter, "." clear."""
    colors = []
    for img in images:
        for c in list(img.getdata()):
            if c[3] != 0 and c not in colors:
                colors.append(c)
    assert len(colors) <= len(LETTERS)
    palette = {LETTERS[i]: c for i, c in enumerate(colors)}
    back = {c: k for k, c in palette.items()}
    frames = []
    for img in images:
        px = img.load()
        frames.append(["".join("." if px[x, y][3] == 0 else back[px[x, y]] for x in range(N)) for y in range(N)])
    return palette, frames


def kotlin(out_path):
    parts = []
    for enum, key, label, draw in ANIMALS:
        palette, frames = encode([draw(f) for f in FACES])
        pal = ", ".join(f"'{k}' to 0xFF{c[0]:02X}{c[1]:02X}{c[2]:02X}.toInt()" for k, c in palette.items())
        faces = []
        for name, rows in zip(FACES, frames):
            body = "".join(f'            "{r}",\n' for r in rows)
            faces.append(f"        {name} = listOf(\n{body}        ),")
        parts.append(f'    {enum}(\n        "{key}",\n        R.string.{label},\n        mapOf({pal}),\n' + "\n".join(faces) + "\n    ),")
    code = (
        "package de.silvertax.merks\n\n"
        "// Generated by tools/mascots/draw_mascots.py: change the drawings there and run it again.\n\n"
        "/** The mascots' pictures: 32 x 32 pixels, one letter a pixel (\".\" is clear), three faces each. */\n"
        "enum class MascotArt(\n"
        "    val key: String,\n"
        "    val label: Int,\n"
        "    val palette: Map<Char, Int>,\n"
        "    val idle: List<String>,\n"
        "    val blink: List<String>,\n"
        "    val happy: List<String>,\n"
        ") {\n" + "\n".join(parts) + "\n}\n"
    )
    with open(out_path, "w") as f:
        f.write(code)


def preview(path, scale=8):
    gap = 16
    img = Image.new("RGB", (len(FACES) * (N * scale + gap) + gap, len(ANIMALS) * (N * scale + gap) + gap), (236, 236, 236))
    for r, (_, _, _, draw) in enumerate(ANIMALS):
        for c, face in enumerate(FACES):
            pic = draw(face).resize((N * scale, N * scale), Image.NEAREST)
            img.paste(pic, (gap + c * (N * scale + gap), gap + r * (N * scale + gap)), pic)
    img.save(path)


if __name__ == "__main__":
    root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    kotlin(os.path.join(root, "app/src/main/java/de/silvertax/merks/MascotArt.kt"))
    if len(sys.argv) > 1:
        preview(sys.argv[1])
