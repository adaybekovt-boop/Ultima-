#!/usr/bin/env python3
"""Print small JPEG thumbnails of the given images as base64 lines, to inspect them from a CI log.

usage: dump-thumbnails.py IMAGE...
Each image becomes two lines: "THUMB <path>" and the base64 of a 256x144 JPEG.
"""
import base64
import io
import sys

from PIL import Image

for path in sys.argv[1:]:
    with Image.open(path) as image:
        buffer = io.BytesIO()
        image.convert("RGB").resize((256, 144)).save(buffer, "JPEG", quality=55)
    print(f"THUMB {path}")
    print(base64.b64encode(buffer.getvalue()).decode("ascii"))
