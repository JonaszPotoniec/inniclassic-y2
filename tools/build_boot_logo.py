#!/usr/bin/env python3
"""Builds an MTK "LOGO" boot-splash partition (logo.bin) for the
Innioasis Y1 and Y2 devices.

The MTK LK (Little Kernel) bootloader expects a container holding 39 image streams:
  - Stream 0:  Primary boot logo (displayed during startup)
  - Stream 1-37: Battery charging animations, battery percentage numbers, low battery warnings
  - Stream 38: Secondary boot logo / fastboot splash

Container layout reverse-engineered from stock MTK firmware (tools/rom_assets/logo_stock_innioasis.bin):
  - Offset 0-3:   Magic 0x58881688 (88 16 88 58 in LE)
  - Offset 4-7:   uint32 LE total_size (partition data size read by mboot_common_load_logo)
  - Offset 8-11:  ASCII "LOGO"
  - Offset 12-511: Reserved MTK partition header padding (copied from stock reference)
  - Offset 512:   uint32 LE frame_count (39)
  - Offset 516:   uint32 LE total_size (cumulative size of table + all streams)
  - Offset 520..: frame_count x uint32 LE cumulative byte offsets measured from byte 512:
                  offset[0] = 520 + 4 * count - 512 = 164 (start of stream 0)
                  offset[i] = offset[i-1] + len(stream[i-1])
  - Offset 512 + offset[i]: zlib-compressed streams.
    Stream 0 and 38 are 480x360 RGB565 raw frames (345,600 bytes uncompressed).

Usage:
  python3 tools/build_boot_logo.py
  python3 tools/build_boot_logo.py docs/icons/icon.png tools/rom_assets/logo_classy.bin
  python3 tools/build_boot_logo.py "Classy" output/logo.bin
"""
import os
import struct
import sys
import zlib

from PIL import Image, ImageDraw, ImageFont
import numpy as np

W, H = 480, 360
FONT_PATH = "/usr/share/fonts/liberation/LiberationSans-Bold.ttf"
FONT_SIZE = 44

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(SCRIPT_DIR)

DEFAULT_REFERENCE_LOGO = os.path.join(SCRIPT_DIR, "rom_assets", "logo_stock_innioasis.bin")
DEFAULT_ICON_PATH = os.path.join(REPO_ROOT, "docs", "icons", "icon.png")
DEFAULT_OUT_PATH = os.path.join(SCRIPT_DIR, "rom_assets", "logo_classy.bin")


def render_logo_image(icon_path: str, size: int = 320) -> Image.Image:
    """Renders a logo image centered on a 480x360 black canvas.
    A target size of 320px yields a 230px wide wordmark from docs/icons/icon.png,
    matching the footprint of the previous boot logo while preserving optical centering.
    """
    icon = Image.open(icon_path).convert("RGBA")
    icon_resized = icon.resize((size, size), Image.Resampling.LANCZOS)

    canvas = Image.new("RGB", (W, H), (0, 0, 0))
    offset = ((W - size) // 2, (H - size) // 2)
    canvas.paste(icon_resized, offset, icon_resized)
    return canvas


def render_text_image(text: str) -> Image.Image:
    img = Image.new("RGB", (W, H), (0, 0, 0))
    draw = ImageDraw.Draw(img)
    font = ImageFont.truetype(FONT_PATH, FONT_SIZE)
    bbox = draw.textbbox((0, 0), text, font=font)
    tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
    x = (W - tw) / 2 - bbox[0]
    y = (H - th) / 2 - bbox[1]
    draw.text((x, y), text, font=font, fill=(255, 255, 255))
    return img


def to_rgb565_bytes(img: Image.Image) -> bytes:
    arr = np.asarray(img.convert("RGB")).astype(np.uint16)
    r, g, b = arr[:, :, 0], arr[:, :, 1], arr[:, :, 2]
    px565 = ((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3)
    return px565.astype("<u2").tobytes()


def build_logo_bin(img: Image.Image, reference_path: str) -> bytes:
    raw = to_rgb565_bytes(img)
    assert len(raw) == W * H * 2, "unexpected raw frame size"
    compressed = zlib.compress(raw, 9)

    with open(reference_path, "rb") as f:
        ref_data = f.read()

    ref_count, ref_total = struct.unpack_from("<II", ref_data, 512)
    ref_offsets = struct.unpack_from(f"<{ref_count}I", ref_data, 520)

    # Extract all streams from the stock reference
    streams = []
    for i in range(ref_count):
        start = 512 + ref_offsets[i]
        end = 512 + (ref_offsets[i + 1] if i + 1 < ref_count else ref_total)
        streams.append(ref_data[start:end])

    # Replace Stream 0 (boot logo) and Stream 38 (secondary boot logo) with our image
    streams[0] = compressed
    if ref_count > 38:
        streams[38] = compressed

    # Compute correct cumulative offsets from byte 512
    new_offsets = []
    curr_offset = 4 + 4 + 4 * ref_count  # 8 header bytes + table offsets array
    for s in streams:
        new_offsets.append(curr_offset)
        curr_offset += len(s)
    total_size = curr_offset

    header = bytearray(ref_data[:512])
    header[4:8] = struct.pack("<I", total_size)

    table = struct.pack("<II", ref_count, total_size) + struct.pack(f"<{ref_count}I", *new_offsets)
    return bytes(header) + table + b"".join(streams)


def verify(data: bytes) -> None:
    """Simulates the MediaTek LK check_logo_index_valid() routine and verifies
    that all 39 frames decompress cleanly with matching dimensions."""
    table_base = 512
    count, total = struct.unpack_from("<II", data, table_base)
    assert count == 39, f"expected 39 streams, got {count}"
    offsets = struct.unpack_from(f"<{count}I", data, table_base + 8)

    for idx in range(count):
        if idx < count - 1:
            stream_len = offsets[idx + 1] - offsets[idx]
        else:
            stream_len = total - offsets[idx]
        stream_addr = table_base + offsets[idx]
        stream_data = data[stream_addr : stream_addr + stream_len]
        decomp = zlib.decompress(stream_data)
        if idx in (0, 38):
            assert len(decomp) == W * H * 2, f"stream {idx} size {len(decomp)} != {W*H*2}"


if __name__ == "__main__":
    target = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_ICON_PATH
    out_path = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_OUT_PATH
    ref_path = DEFAULT_REFERENCE_LOGO

    if os.path.isfile(target):
        print(f"Rendering boot logo from image: {target}")
        img = render_logo_image(target, size=320)
    else:
        print(f"Rendering boot logo from text: {target!r}")
        img = render_text_image(target)

    data = build_logo_bin(img, ref_path)
    verify(data)

    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    with open(out_path, "wb") as f:
        f.write(data)
    print(f"Wrote {out_path} ({len(data)} bytes, partition limit is 3145728)")
