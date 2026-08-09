#!/usr/bin/env python3
"""Draw the six conduit tiles from the spec in docs/SPRITES.md.

Procedural rather than hand- or AI-drawn, because every requirement is geometric:
a channel exactly 12 px across, reaching the exact tile edge on each side it is drawn
for, hard alpha, nine fixed colours, and a bright core that stays continuous through
bends and junctions. A distance field makes all of those true by construction instead
of by inspection.

Each tile is described only as a set of centre-line segments. The channel is then the
set of pixels within 5.5 px of that path, coloured by distance from it. Consequences
that matter:

  * A segment running past the tile boundary fills the edge row exactly 12 px wide, so
    runs join with no seam.
  * A segment ending inside the tile gets a rounded, outlined cap for free -- which is
    why only `stub` and `cap` have caps. Through-edges cannot accidentally get one,
    so the 2 px bar the earlier horizontal tile suffered from cannot come back.
  * Unioning segments keeps the core continuous, so an elbow bends and a tee reads as a
    run with a branch rather than three stubs meeting.

The cross-section is deliberately SYMMETRIC about the channel axis. build_conduit_sheet.py
rotates these tiles to make the other ten frames, and rotation moves any directional
highlight with it -- a top-lit tube would end up lit from the side or bottom in three of
its four orientations. A symmetric profile looks identical under every rotation, so the
lighting cannot be wrong. (Rotating offline rather than at draw time does not avoid this
on its own; symmetry is what avoids it.)

Usage:  make_conduit_tiles.py <output dir>
"""

import sys
from pathlib import Path

from PIL import Image

TILE = 32
C = 16.0          # channel centre, so the 12 px span is x=10..21 inclusive
HALF = 5.5        # channel half-width

# Rings by distance from the centre line, brightest core outward to a 1 px black outline.
# Six rings each side = the 12 px width the spec requires.
PROFILE = [
    (0.5, "#e8dcff"),
    (1.5, "#9d86c9"),
    (2.5, "#604a8c"),   # the object's map colour, required to appear
    (3.5, "#46356b"),
    (4.5, "#2e2247"),
    (5.5, "#000000"),
]

# A collar clamp. Wider than the tube so it reads as a raised ring, and DARKER, so it
# contrasts against the glow rather than washing into it -- a metal band around a lit tube.
# The core still shines through the middle, keeping the run continuous.
COLLAR = [
    (0.5, "#c7b0e8"),
    (1.5, "#7a5fa8"),
    (2.5, "#604a8c"),
    (3.5, "#46356b"),
    (4.5, "#2e2247"),
    (5.5, "#1a1420"),
    (6.5, "#000000"),
]

PALETTE = {
    "#000000", "#1a1420", "#2e2247", "#46356b", "#604a8c",
    "#7a5fa8", "#9d86c9", "#c7b0e8", "#e8dcff",
}


def rgb(h):
    return (int(h[1:3], 16), int(h[3:5], 16), int(h[5:7], 16), 255)


def dist_to_segment(px, py, ax, ay, bx, by):
    vx, vy = bx - ax, by - ay
    wx, wy = px - ax, py - ay
    length = vx * vx + vy * vy
    t = 0.0 if length == 0 else max(0.0, min(1.0, (wx * vx + wy * vy) / length))
    dx, dy = ax + t * vx - px, ay + t * vy - py
    return (dx * dx + dy * dy) ** 0.5


# Each shape: the centre-line segments, and where to put its centre feature.
# Segments ending at -1 or 33 run past the boundary so the edge row fills completely.
#: The inventory icon is not one of the sixteen placed states -- it is a loose length of pipe.
#: Both ends stop short of the tile edge, which is what earns them the rounded outlined cap, and
#: the gem carries the same junction motif the placed tiles use so the icon and the world object
#: read as the same object. Generated here rather than hand-drawn so it cannot drift from the six.
ICON = ([(6.5, C, 25.5, C)], "gem")

SHAPES = {
    #                                                            feature at centre
    "stub":     ([(C, 12.5, C, 19.5)],                            "gem"),
    "cap":      ([(C, -1, C, 19.5)],                              "collar_north"),
    "straight": ([(C, -1, C, 33)],                                "collar"),
    "elbow":    ([(C, -1, C, C), (C, C, 33, C)],                  "gem"),
    "tee":      ([(C, -1, C, 33), (C, C, 33, C)],                 "gem"),
    "cross":    ([(C, -1, C, 33), (-1, C, 33, C)],                "gem"),
}


def draw(segments, feature):
    img = Image.new("RGBA", (TILE, TILE), (0, 0, 0, 0))
    px = img.load()

    # Where the collar sits: across the channel at the tile centre, or just before a cap.
    if feature == "collar":
        collar_rows = range(14, 18)
    elif feature == "collar_north":
        collar_rows = range(13, 17)
    else:
        collar_rows = ()

    for y in range(TILE):
        for x in range(TILE):
            cx, cy = x + 0.5, y + 0.5
            d = min(dist_to_segment(cx, cy, *s) for s in segments)

            rings = COLLAR if y in collar_rows else PROFILE
            for limit, colour in rings:
                if d <= limit:
                    px[x, y] = rgb(colour)
                    break

    # A bright node where a run turns or splits, so junctions read as deliberate.
    # Four-fold symmetric, so it survives every rotation unchanged.
    if feature == "gem":
        for y in range(TILE):
            for x in range(TILE):
                if px[x, y][3] == 0:
                    continue
                m = abs(x + 0.5 - C) + abs(y + 0.5 - C)
                if m <= 1.5:
                    px[x, y] = rgb("#e8dcff")
                elif m <= 3.5:
                    px[x, y] = rgb("#c7b0e8")
                elif m <= 5.0:
                    px[x, y] = rgb("#7a5fa8")

    # Bolt heads on the clamp. Placed INSIDE the dark band rather than on the black
    # outline -- on the outline they read as stray dirt pixels rather than hardware.
    if collar_rows:
        rows = list(collar_rows)
        mid = rows[len(rows) // 2 - 1], rows[len(rows) // 2]
        for y in mid:
            for x in (10, 21):
                if px[x, y][3] > 0:
                    px[x, y] = rgb("#9d86c9")

    return img


def main():
    if len(sys.argv) != 2:
        return print(__doc__) or 2
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)

    for name, (segments, feature) in SHAPES.items():
        img = draw(segments, feature)
        img.save(out / f"{name}.png")

        colours = {"#%02x%02x%02x" % img.getpixel((x, y))[:3]
                   for y in range(TILE) for x in range(TILE)
                   if img.getpixel((x, y))[3] == 255}
        assert colours <= PALETTE, f"{name} off-palette: {colours - PALETTE}"
        alphas = {img.getpixel((x, y))[3] for y in range(TILE) for x in range(TILE)}
        assert alphas <= {0, 255}, f"{name} has partial alpha: {alphas}"
        print(f"  {name:9s} {len(colours)} colours, hard alpha")

    icon = draw(*ICON)
    icon.save(out / "icon.png")
    print(f"  {'icon':9s} inventory icon, not a placed state")

    print(f"\n  wrote 6 tiles and the icon to {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
