#!/usr/bin/env python3
"""Print a coarse text picture of each given image, to inspect a screenshot from a CI log.

usage: dump-thumbnails.py IMAGE...
Every 64x24 cell shows the dominant colour of its block: r, g or b for a saturated red, green or
blue, y for yellow, and a brightness ramp (" .:-=+*#%@") for grey.
"""
import sys

import numpy as np
from PIL import Image

COLUMNS, ROWS = 64, 24
RAMP = " .:-=+*#%@"


def glyph(red: float, green: float, blue: float) -> str:
    high, low = max(red, green, blue), min(red, green, blue)
    if high - low < 28:
        return RAMP[min(len(RAMP) - 1, int(high / 256 * len(RAMP)))]
    if red > 150 and green > 150 and blue < 110:
        return "y"
    if red >= green and red >= blue:
        return "r"
    return "g" if green >= blue else "b"


for path in sys.argv[1:]:
    with Image.open(path) as image:
        pixels = np.asarray(image.convert("RGB"), dtype=float)
    height, width, _ = pixels.shape
    print(f"PICTURE {path} {width}x{height}")
    for row in range(ROWS):
        top, bottom = row * height // ROWS, (row + 1) * height // ROWS
        line = []
        for column in range(COLUMNS):
            left, right = column * width // COLUMNS, (column + 1) * width // COLUMNS
            red, green, blue = pixels[top:bottom, left:right].reshape(-1, 3).mean(axis=0)
            line.append(glyph(red, green, blue))
        print("|" + "".join(line) + "|")
