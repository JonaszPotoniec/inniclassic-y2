#!/usr/bin/env python3
"""
patch_safe_volume.py

Patches Android's framework-res.apk to permanently disable Safe Media Volume:
1. Sets default config_safe_media_volume_enabled = false (disables warning & blocking).
2. Sets default config_safe_media_volume_index = 15 (max volume index 100%).
"""

import sys
import os
import zipfile
import struct
import tempfile
import shutil

def patch_arsc(data: bytearray) -> bool:
    # 1. Parse header
    pos = 0
    header_type, header_size, chunk_size = struct.unpack_from("<HHI", data, pos)
    if header_type != 0x0002: # RES_TABLE_TYPE
        raise ValueError(f"Invalid ARSC header: {hex(header_type)}")
    pos += header_size

    # Global string pool
    sp_type, sp_header_size, sp_chunk_size = struct.unpack_from("<HHI", data, pos)
    pos += sp_chunk_size

    # Package header
    pkg_type, pkg_header_size, pkg_chunk_size = struct.unpack_from("<HHI", data, pos)
    if pkg_type != 0x0200: # RES_TABLE_PACKAGE_TYPE
        raise ValueError(f"Invalid package header: {hex(pkg_type)}")

    # Type strings pool
    pos += pkg_header_size
    tsp_type, tsp_header_size, tsp_chunk_size = struct.unpack_from("<HHI", data, pos)
    pos += tsp_chunk_size

    # Key strings pool
    ksp_pos = pos
    ksp_type, ksp_header_size, ksp_chunk_size = struct.unpack_from("<HHI", data, pos)
    k_string_count = struct.unpack_from("<I", data, ksp_pos + 8)[0]
    k_flags = struct.unpack_from("<I", data, ksp_pos + 16)[0]
    k_strings_start = struct.unpack_from("<I", data, ksp_pos + 20)[0]
    is_utf8 = bool(k_flags & (1 << 8))

    key_offsets = [struct.unpack_from("<I", data, ksp_pos + 28 + i*4)[0] for i in range(k_string_count)]
    keys = []
    strings_data_pos = ksp_pos + k_strings_start
    for i, off in enumerate(key_offsets):
        p = strings_data_pos + off
        if is_utf8:
            u8len = data[p+1]
            keys.append(data[p+2:p+2+u8len].decode("utf-8", errors="ignore"))
        else:
            u16len = struct.unpack_from("<H", data, p)[0]
            keys.append(data[p+2:p+2+u16len*2].decode("utf-16le", errors="ignore"))

    if "config_safe_media_volume_enabled" not in keys:
        print("Warning: config_safe_media_volume_enabled not found in key strings", file=sys.stderr)
        return False
    if "config_safe_media_volume_index" not in keys:
        print("Warning: config_safe_media_volume_index not found in key strings", file=sys.stderr)
        return False

    idx_enabled = keys.index("config_safe_media_volume_enabled")
    idx_index = keys.index("config_safe_media_volume_index")

    patched_enabled = 0
    patched_index = 0

    # Patch config_safe_media_volume_enabled: true (0xffffffff) -> false (0)
    pat_enabled = struct.pack("<HHI", 8, 0, idx_enabled)
    p = 0
    while True:
        p = data.find(pat_enabled, p)
        if p == -1: break
        rv_type, rv_data = struct.unpack_from("<BI", data, p + 11)
        if rv_type == 0x12 and rv_data == 0xffffffff:
            struct.pack_into("<I", data, p + 12, 0)
            patched_enabled += 1
        p += 1

    # Patch config_safe_media_volume_index: 12 (0xc) -> 15 (0xf)
    pat_index = struct.pack("<HHI", 8, 0, idx_index)
    p = 0
    while True:
        p = data.find(pat_index, p)
        if p == -1: break
        rv_type, rv_data = struct.unpack_from("<BI", data, p + 11)
        if rv_type == 0x10 and rv_data < 15:
            struct.pack_into("<I", data, p + 12, 15)
            patched_index += 1
        p += 1

    print(f"Patched config_safe_media_volume_enabled: {patched_enabled} entry(ies)")
    print(f"Patched config_safe_media_volume_index: {patched_index} entry(ies)")
    return (patched_enabled > 0 or patched_index > 0)

def patch_apk(apk_path: str) -> bool:
    if not os.path.isfile(apk_path):
        print(f"Error: APK not found: {apk_path}", file=sys.stderr)
        return False

    tmp_dir = tempfile.mkdtemp()
    try:
        tmp_apk = os.path.join(tmp_dir, "patched.apk")
        with zipfile.ZipFile(apk_path, "r") as zin:
            with zipfile.ZipFile(tmp_apk, "w") as zout:
                for item in zin.infolist():
                    content = zin.read(item.filename)
                    if item.filename == "resources.arsc":
                        barr = bytearray(content)
                        if patch_arsc(barr):
                            content = bytes(barr)
                        zout.writestr(item, content, compress_type=zipfile.ZIP_STORED)
                    else:
                        zout.writestr(item, content, compress_type=item.compress_type)
        shutil.move(tmp_apk, apk_path)
        return True
    finally:
        shutil.rmtree(tmp_dir, ignore_errors=True)

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: patch_safe_volume.py <path_to_framework-res.apk>", file=sys.stderr)
        sys.exit(1)
    target_apk = sys.argv[1]
    success = patch_apk(target_apk)
    sys.exit(0 if success else 1)
