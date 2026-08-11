#!/usr/bin/env python3
"""Recolour one of this mod's own sprites, for placeholders.

Derived from our own art, never from the game's -- see ../docs/ASSETS.md for why that distinction
matters. Pure standard library: PIL is not installed and PEP 668 blocks pip on this machine.

    tools/tint_sprite.py in.png out.png 0.6 1.15 0.7

The three numbers scale red, green and blue. Alpha is untouched, so the silhouette survives.
"""

from __future__ import annotations

import struct
import sys
import zlib


def read_png(path: str) -> tuple[int, int, bytearray]:
    """Returns width, height and raw RGBA rows, for 8-bit RGBA files only."""
    data = open(path, "rb").read()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise SystemExit(f"{path}: not a PNG")

    pos = 8
    width = height = 0
    idat = bytearray()
    while pos < len(data):
        length, kind = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + length]
        if kind == b"IHDR":
            width, height, depth, colour = struct.unpack(">IIBB", body[:10])
            if (depth, colour) != (8, 6):
                raise SystemExit(f"{path}: expected 8-bit RGBA, got depth {depth} colour type {colour}")
        elif kind == b"IDAT":
            idat += body
        elif kind == b"IEND":
            break

        pos += 12 + length

    raw = zlib.decompress(bytes(idat))
    stride = width * 4
    out = bytearray()
    previous = bytearray(stride)
    at = 0
    for _ in range(height):
        filter_type = raw[at]
        line = bytearray(raw[at + 1:at + 1 + stride])
        at += 1 + stride
        if filter_type == 1:
            for i in range(4, stride):
                line[i] = (line[i] + line[i - 4]) & 0xFF
        elif filter_type == 2:
            for i in range(stride):
                line[i] = (line[i] + previous[i]) & 0xFF
        elif filter_type == 3:
            for i in range(stride):
                left = line[i - 4] if i >= 4 else 0
                line[i] = (line[i] + ((left + previous[i]) >> 1)) & 0xFF
        elif filter_type == 4:
            for i in range(stride):
                a = line[i - 4] if i >= 4 else 0
                b = previous[i]
                c = previous[i - 4] if i >= 4 else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[i] = (line[i] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 0xFF
        elif filter_type != 0:
            raise SystemExit(f"{path}: unsupported filter {filter_type}")

        out += line
        previous = line

    return width, height, out


def write_png(path: str, width: int, height: int, pixels: bytearray) -> None:
    stride = width * 4
    raw = bytearray()
    for y in range(height):
        raw.append(0)  # no filtering: these are 32x32 sprites, and it keeps this readable
        raw += pixels[y * stride:(y + 1) * stride]

    def chunk(kind: bytes, body: bytes) -> bytes:
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body))

    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n")
        f.write(chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)))
        f.write(chunk(b"IDAT", zlib.compress(bytes(raw), 9)))
        f.write(chunk(b"IEND", b""))


def main() -> None:
    if len(sys.argv) != 6:
        raise SystemExit(__doc__)

    source, target = sys.argv[1], sys.argv[2]
    scales = [float(v) for v in sys.argv[3:6]]
    width, height, pixels = read_png(source)
    for i in range(0, len(pixels), 4):
        for channel in range(3):
            pixels[i + channel] = max(0, min(255, round(pixels[i + channel] * scales[channel])))

    write_png(target, width, height, pixels)
    print(f"{target}: {width}x{height} from {source}")


if __name__ == "__main__":
    main()
