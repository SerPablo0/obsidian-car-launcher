"""Renders the Obsidian boot logo for the NWD head unit.

The bootloader shows /data/bootloader/bootlogo.bmp on the panel's native landscape
1024x768 framebuffer, before Android rotates the display to portrait. So the artwork
is designed at 768x1024 and turned 90 degrees counter-clockwise, matching the stock
logo, then saved as a 32-bit uncompressed BMP exactly like the original.

Usage: python tools/make_bootlogo.py <out_dir>
"""
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

BG = (11, 12, 14)
ACCENT = (46, 230, 166)
TEXT = (242, 243, 245)
MUTED = (110, 116, 125)
SCALE = 4  # supersampling for smooth edges


def render_portrait() -> Image.Image:
    w, h = 768 * SCALE, 1024 * SCALE
    img = Image.new("RGB", (w, h), BG)
    draw = ImageDraw.Draw(img)

    cx, cy = w // 2, int(h * 0.44)
    ring_r, ring_w = 92 * SCALE, 9 * SCALE
    draw.ellipse((cx - ring_r, cy - ring_r, cx + ring_r, cy + ring_r), outline=ACCENT, width=ring_w)
    dot_r = 30 * SCALE
    draw.ellipse((cx - dot_r, cy - dot_r, cx + dot_r, cy + dot_r), fill=TEXT)

    font = ImageFont.truetype("C:/Windows/Fonts/segoeuil.ttf", 54 * SCALE)
    word = "OBSIDIAN"
    spacing = 22 * SCALE
    widths = [draw.textlength(c, font=font) for c in word]
    total = sum(widths) + spacing * (len(word) - 1)
    x, y = cx - total / 2, cy + ring_r + 90 * SCALE
    for c, cw in zip(word, widths):
        draw.text((x, y), c, font=font, fill=TEXT)
        x += cw + spacing

    small = ImageFont.truetype("C:/Windows/Fonts/segoeui.ttf", 22 * SCALE)
    tag = "KIA SPORTAGE"
    tw = draw.textlength(tag, font=small)
    draw.text((cx - tw / 2, y + 100 * SCALE), tag, font=small, fill=MUTED)

    return img.resize((768, 1024), Image.LANCZOS)


def main(out_dir: str) -> None:
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    portrait = render_portrait()
    portrait.save(out / "bootlogo_preview.png")
    # Same orientation as the stock logo: portrait content turned 90 degrees counter-clockwise.
    landscape = portrait.rotate(90, expand=True).convert("RGBA")
    assert landscape.size == (1024, 768)
    landscape.save(out / "bootlogo.bmp", format="BMP")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else ".")
