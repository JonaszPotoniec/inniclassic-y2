#!/usr/bin/env python3
"""
patch_y2_audio.py

Patches MediaTek libaudiocustparam.so on the Innioasis Y2 to lower the minimum
headphone output volume (Level 0 / Step 1) for sensitive headphones like Koss Porta Pro.

In the stock MediaTek MT6582 audio table:
  Mode: Headset (1), Stream: Music (3)
  Stock curve: [32, 48, 64, 80, 96, 112, 128, 144, 160, 176, 192, 208, 224, 255, 255]
  Level 0 is 32/255 (~ -18 dBFS), which is far too loud on 60 ohm / 101 dB SPL headphones.

Tuned low-gain curve:
  [4, 8, 14, 22, 32, 46, 64, 86, 112, 142, 176, 204, 228, 245, 255]
  - Step 1 (index 0) drops to 4/255 (~ -36 dBFS, ~18 dB quieter minimum volume).
  - Step 5 (index 4) reaches 32 (the old Step 1).
  - Step 15 (index 14) remains 255 (maximum volume 100% preserved).
"""

import sys
import os

STOCK_HEADSET_MUSIC_CURVE = bytes([32, 48, 64, 80, 96, 112, 128, 144, 160, 176, 192, 208, 224, 255, 255])
TUNED_HEADSET_MUSIC_CURVE = bytes([4, 8, 14, 22, 32, 46, 64, 86, 112, 142, 176, 204, 228, 245, 255])

# In AUDIO_VER1_CUSTOM_VOLUME_STRUCT:
# Headset (mode index 1), Stream Music (stream index 3) starts at offset:
# (1 * 9 streams + 3) * 15 levels = 180 bytes from start of audio_ver1_custom_default.
HEADSET_MUSIC_OFFSET_IN_STRUCT = 180

def locate_table_offset(data: bytearray) -> int:
    """Finds the file offset of the Headset Music curve in libaudiocustparam.so."""
    # Pattern of the first block of audio_ver1_custom_default (Normal Voice Call)
    first_block_pattern = bytes([32, 48, 64, 80, 96, 112, 128, 144, 160, 176, 192, 208, 224, 240, 255])
    idx = 0
    while True:
        pos = data.find(first_block_pattern, idx)
        if pos == -1:
            break
        headset_offset = pos + HEADSET_MUSIC_OFFSET_IN_STRUCT
        if headset_offset + 15 <= len(data):
            curr = bytes(data[headset_offset:headset_offset + 15])
            if curr == STOCK_HEADSET_MUSIC_CURVE or curr == TUNED_HEADSET_MUSIC_CURVE:
                return headset_offset
        idx = pos + 1
    return -1

def patch_file(path: str, check_only: bool = False) -> bool:
    if not os.path.isfile(path):
        print(f"Error: file not found: {path}", file=sys.stderr)
        return False

    with open(path, "rb") as f:
        data = bytearray(f.read())

    offset = locate_table_offset(data)
    if offset == -1:
        print(f"Error: could not locate Headset Music table in {path}", file=sys.stderr)
        return False

    current_curve = bytes(data[offset:offset + 15])
    if current_curve == TUNED_HEADSET_MUSIC_CURVE:
        print(f"Verified: {path} already has tuned low-gain curve at offset {hex(offset)}")
        return True

    if check_only:
        print(f"File {path} does NOT have tuned curve (found: {list(current_curve)})")
        return False

    data[offset:offset + 15] = TUNED_HEADSET_MUSIC_CURVE
    with open(path, "wb") as f:
        f.write(data)

    print(f"Successfully patched {path} at offset {hex(offset)}")
    print(f"  Old curve: {list(current_curve)}")
    print(f"  New curve: {list(TUNED_HEADSET_MUSIC_CURVE)}")
    return True

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: patch_y2_audio.py [--check] <path_to_libaudiocustparam.so>", file=sys.stderr)
        sys.exit(1)

    check_mode = "--check" in sys.argv
    file_args = [arg for arg in sys.argv[1:] if arg != "--check"]
    if not file_args:
        print("Error: missing target file argument", file=sys.stderr)
        sys.exit(1)

    target_path = file_args[0]
    success = patch_file(target_path, check_only=check_mode)
    sys.exit(0 if success else 1)
