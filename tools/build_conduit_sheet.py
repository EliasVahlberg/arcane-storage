#!/usr/bin/env python3
"""Assemble the conduit's 16-frame sheet from 6 authored tiles.

There are only six conduit shapes -- stub, cap, straight, elbow, tee, cross -- but sixteen
states, because a cap has four orientations, an elbow four, a tee four and a straight two.
The other ten frames are rotations, so they are generated rather than drawn.

Rotating here rather than at draw time is deliberate: rotated textures show dark edges (which
is why the build has a preAntialiasTextures task), vanilla objects use frames rather than
runtime rotation, and rotating once, offline, at exact 90 degree steps on a square tile is
lossless and costs nothing at runtime.

An earlier version of this docstring also claimed offline rotation avoids directional shading
ending up lit from the wrong side. That was wrong, and the correction is worth keeping because
it is a real constraint on the art rather than on this script: a 90 degree rotation moves a
highlight regardless of when it happens. What actually makes every orientation correct is that
the authored cross-section is *symmetric about the channel axis* -- core, darker rings, then a
1px outline, mirrored -- and the junction node is four-fold symmetric. A symmetric profile is
identical under rotation, so no orientation can be lit wrongly.

So: if anyone adds a directional highlight to the six tiles, these rotations break, and the
selftest below will not catch it -- it checks which edges are reached, not how they are shaded.

Frame index IS the neighbour bitmask the game computes: north 1, east 2, south 4, west 8.

Usage:
    build_conduit_sheet.py <dir with the 6 tiles> <output.png>
    build_conduit_sheet.py --selftest
"""

import sys
from pathlib import Path

try:
    from PIL import Image
except ImportError:
    sys.exit("needs Pillow: pip install --user Pillow")

N, E, S, W = 1, 2, 4, 8

# Each authored tile, the state it is drawn as, and the states derived by rotating it
# clockwise. Clockwise maps north to east, east to south, south to west, west to north.
SHAPES = {
    "stub":     [0],
    "cap":      [N,      E,      S,      W],
    "straight": [N | S,  E | W],
    "elbow":    [N | E,  E | S,  S | W,  W | N],
    "tee":      [N | E | S, E | S | W, S | W | N, W | N | E],
    "cross":    [N | E | S | W],
}

TILE = 32


def rotate_cw(image, quarters):
    for _ in range(quarters % 4):
        image = image.transpose(Image.Transpose.ROTATE_270)  # 270 CCW == 90 CW
    return image


def build(tiles):
    sheet = Image.new("RGBA", (TILE * 16, TILE), (0, 0, 0, 0))
    filled = set()

    for name, states in SHAPES.items():
        tile = tiles[name]
        if tile.size != (TILE, TILE):
            sys.exit(f"{name} is {tile.size[0]}x{tile.size[1]}, expected {TILE}x{TILE}")
        for quarters, state in enumerate(states):
            sheet.paste(rotate_cw(tile, quarters), (state * TILE, 0))
            filled.add(state)

    missing = set(range(16)) - filled
    if missing:
        sys.exit(f"frames not covered: {sorted(missing)}")
    return sheet


def load(directory):
    tiles = {}
    for name in SHAPES:
        path = Path(directory) / f"{name}.png"
        if not path.exists():
            sys.exit(f"missing {path}. Needed: {', '.join(n + '.png' for n in SHAPES)}")
        tiles[name] = Image.open(path).convert("RGBA")
    return tiles


def selftest():
    """Prove the rotation mapping matches the bitmask, with no art involved.

    Each synthetic tile marks only the edges its shape reaches. If a frame ends up in the
    wrong slot, or rotated the wrong way, the marked edges stop matching the frame's own
    bitmask -- which is the one thing about this file that could be silently wrong.
    """
    # Mark whole edges rather than a midpoint pixel. A single probe at index 16 is not
    # rotation-invariant -- rotation maps index 16 to 31-16 = 15 -- so the first version of this
    # test failed against a builder that was correct. A whole edge maps to a whole edge exactly.
    # The interior of each edge, excluding both corners: a corner pixel belongs to two edges at
    # once, so including it makes marking one edge look like marking its neighbours too.
    span = range(1, TILE - 1)

    def edge_pixels(bit):
        if bit == N:
            return [(x, 0) for x in span]
        if bit == S:
            return [(x, TILE - 1) for x in span]
        if bit == W:
            return [(0, y) for y in span]
        return [(TILE - 1, y) for y in span]

    tiles = {}
    for name, states in SHAPES.items():
        tile = Image.new("RGBA", (TILE, TILE), (0, 0, 0, 0))
        for bit in (N, E, S, W):
            if states[0] & bit:
                for point in edge_pixels(bit):
                    tile.putpixel(point, (255, 255, 255, 255))
        tiles[name] = tile

    sheet = build(tiles)

    failures = []
    for state in range(16):
        frame = sheet.crop((state * TILE, 0, (state + 1) * TILE, TILE))
        for bit in (N, E, S, W):
            lit = any(frame.getpixel(point)[3] > 0 for point in edge_pixels(bit))
            if lit != bool(state & bit):
                side = {N: "north", E: "east", S: "south", W: "west"}[bit]
                failures.append(
                    f"  frame {state:2d}: {side} edge is {'set' if lit else 'clear'}, "
                    f"expected {'set' if state & bit else 'clear'}"
                )

    if failures:
        print("SELFTEST FAILED")
        print("\n".join(failures))
        return 1

    print("selftest passed: all 16 frames reach exactly the edges their bitmask claims")
    return 0


def main():
    if len(sys.argv) == 2 and sys.argv[1] == "--selftest":
        return selftest()

    if len(sys.argv) != 3:
        return print(__doc__) or 2

    build(load(sys.argv[1])).save(sys.argv[2])
    print(f"wrote {sys.argv[2]} (512x32, 16 frames)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
