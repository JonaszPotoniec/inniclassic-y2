#!/usr/bin/env python3
"""
patch_low_battery_warning.py

Turns off the SystemUI low-battery dialog (and its sound) in framework-res.apk of the Y2.

On Android 4.4, SystemUI (PowerUI) shows a "battery is low" dialog, which dims the screen behind it and plays
the low-battery sound, when the level goes to config_lowBatteryWarningLevel (15) and again at
config_criticalBatteryWarningLevel (4). The sound plays only together with the dialog. The launcher shows its
own warnings, so this sets both levels to -1: PowerUI shows the dialog only at a level <= the warning level,
and the level is never below 0. (0 would still show the dialog at 0 %, just before the shutdown.)
The same two values are read only by BatteryService, which then sends no ACTION_BATTERY_LOW broadcast and
uses no low-battery LED colour. The shutdown at 0 % does not use them.

The patch changes only the 8 data bytes of the two values in resources.arsc and its CRC-32. resources.arsc must
be stored (not compressed), as in the stock file; all other bytes of the APK stay the same, so the alignment and
the other entries do not change. Android reads the signature of a system APK only from AndroidManifest.xml.

Usage: patch_low_battery_warning.py [--check] <path_to_framework-res.apk>
  --check  exit 0 if the APK has the patched values, else 1 (no change).
"""

import os
import struct
import sys
import tempfile
import zlib

TARGET = {
    "config_lowBatteryWarningLevel": -1,
    "config_criticalBatteryWarningLevel": -1,
}

RES_STRING_POOL_TYPE = 0x0001
RES_TABLE_TYPE = 0x0002
RES_TABLE_PACKAGE_TYPE = 0x0200
RES_TABLE_TYPE_TYPE = 0x0201
TYPE_INT_DEC = 0x10
TYPE_INT_HEX = 0x11
FLAG_COMPLEX = 0x0001
NO_ENTRY = 0xFFFFFFFF


def read_string_pool(data, pos):
    """Returns the strings of the string pool chunk at pos."""
    _, header_size, _ = struct.unpack_from("<HHI", data, pos)
    count, _, flags, strings_start, _ = struct.unpack_from("<IIIII", data, pos + 8)
    utf8 = bool(flags & (1 << 8))
    offsets = struct.unpack_from("<%dI" % count, data, pos + header_size)
    base = pos + strings_start
    out = []
    for off in offsets:
        p = base + off
        if utf8:
            # UTF-16 length, then UTF-8 length; each 1 byte, or 2 bytes when the high bit is set.
            p += 2 if data[p] & 0x80 else 1
            n = data[p]
            if n & 0x80:
                n = ((n & 0x7F) << 8) | data[p + 1]
                p += 2
            else:
                p += 1
            out.append(bytes(data[p:p + n]).decode("utf-8", errors="replace"))
        else:
            n = struct.unpack_from("<H", data, p)[0]
            p += 2
            if n & 0x8000:
                n = ((n & 0x7FFF) << 16) | struct.unpack_from("<H", data, p)[0]
                p += 2
            out.append(bytes(data[p:p + 2 * n]).decode("utf-16le", errors="replace"))
    return out


def find_values(data):
    """Returns {key name: [file offset of the Res_value data]} for the keys in TARGET (simple integer entries)."""
    found = {name: [] for name in TARGET}
    t, header_size, size = struct.unpack_from("<HHI", data, 0)
    if t != RES_TABLE_TYPE:
        raise ValueError("resources.arsc: not a resource table")
    pos = header_size
    while pos < size:
        ctype, _, csize = struct.unpack_from("<HHI", data, pos)
        if ctype == RES_TABLE_PACKAGE_TYPE:
            scan_package(data, pos, found)
        pos += csize
    return found


def scan_package(data, pkg, found):
    _, header_size, size = struct.unpack_from("<HHI", data, pkg)
    key_strings_off = struct.unpack_from("<I", data, pkg + 8 + 4 + 256 + 8)[0]
    keys = read_string_pool(data, pkg + key_strings_off)
    wanted = {i: k for i, k in enumerate(keys) if k in TARGET}
    if not wanted:
        return
    pos = pkg + header_size
    end = pkg + size
    while pos < end:
        ctype, cheader, csize = struct.unpack_from("<HHI", data, pos)
        if csize <= 0:
            break
        if ctype == RES_TABLE_TYPE_TYPE:
            entry_count, entries_start = struct.unpack_from("<II", data, pos + 12)
            offsets = struct.unpack_from("<%dI" % entry_count, data, pos + cheader)
            for off in offsets:
                if off == NO_ENTRY:
                    continue
                e = pos + entries_start + off
                esize, eflags, ekey = struct.unpack_from("<HHI", data, e)
                if ekey not in wanted or eflags & FLAG_COMPLEX:
                    continue
                vtype = data[e + esize + 3]
                if vtype not in (TYPE_INT_DEC, TYPE_INT_HEX):
                    raise ValueError("%s: not an integer (type 0x%02x)" % (wanted[ekey], vtype))
                found[wanted[ekey]].append(e + esize + 4)
        pos += csize


def locate_arsc(apk):
    """Returns (data offset, size, local header offset, central directory entry offset, flags) of resources.arsc."""
    eocd = apk.rfind(b"PK\x05\x06")
    if eocd < 0:
        raise ValueError("not a zip file")
    cd_count = struct.unpack_from("<H", apk, eocd + 10)[0]
    cd_off = struct.unpack_from("<I", apk, eocd + 16)[0]
    p = cd_off
    for _ in range(cd_count):
        if apk[p:p + 4] != b"PK\x01\x02":
            raise ValueError("bad central directory")
        flags, method = struct.unpack_from("<HH", apk, p + 8)
        csize, usize = struct.unpack_from("<II", apk, p + 20)
        nlen, xlen, clen = struct.unpack_from("<HHH", apk, p + 28)
        local = struct.unpack_from("<I", apk, p + 42)[0]
        name = apk[p + 46:p + 46 + nlen]
        if name == b"resources.arsc":
            if method != 0 or csize != usize:
                raise ValueError("resources.arsc is compressed; this patch needs it stored")
            if apk[local:local + 4] != b"PK\x03\x04":
                raise ValueError("bad local header")
            lnlen, lxlen = struct.unpack_from("<HH", apk, local + 26)
            return local + 30 + lnlen + lxlen, usize, local, p, flags
        p += 46 + nlen + xlen + clen
    raise ValueError("no resources.arsc in the APK")


def values(apk):
    start, size, _, _, _ = locate_arsc(apk)
    arsc = apk[start:start + size]
    found = find_values(arsc)
    return {k: [struct.unpack_from("<i", arsc, off)[0] for off in v] for k, v in found.items()}, found, start, size


def check(path):
    with open(path, "rb") as f:
        apk = f.read()
    current, _, _, _ = values(apk)
    ok = True
    for key, target in TARGET.items():
        vals = current[key]
        print("%s: %s" % (key, vals if vals else "not found"))
        if not vals or any(v != target for v in vals):
            ok = False
    return ok


def patch(path):
    with open(path, "rb") as f:
        apk = bytearray(f.read())
    start, size, local, central, flags = locate_arsc(apk)
    arsc = apk[start:start + size]
    found = find_values(arsc)
    for key, offs in found.items():
        if not offs:
            raise ValueError("%s not found in resources.arsc" % key)
        for off in offs:
            old = struct.unpack_from("<i", arsc, off)[0]
            struct.pack_into("<i", arsc, off, TARGET[key])
            print("%s: %d -> %d" % (key, old, TARGET[key]))
    crc = zlib.crc32(bytes(arsc)) & 0xFFFFFFFF
    apk[start:start + size] = arsc
    struct.pack_into("<I", apk, local + 14, crc)
    struct.pack_into("<I", apk, central + 16, crc)
    if flags & 0x0008:
        # Data descriptor after the data: optional signature, then the CRC-32.
        d = start + size
        struct.pack_into("<I", apk, d + 4 if apk[d:d + 4] == b"PK\x07\x08" else d, crc)
    directory = os.path.dirname(os.path.abspath(path))
    fd, tmp = tempfile.mkstemp(dir=directory, suffix=".tmp")
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(apk)
        os.replace(tmp, path)
    except BaseException:
        if os.path.exists(tmp):
            os.unlink(tmp)
        raise


def main():
    args = [a for a in sys.argv[1:] if a != "--check"]
    if len(args) != 1:
        print("Usage: patch_low_battery_warning.py [--check] <path_to_framework-res.apk>", file=sys.stderr)
        return 1
    try:
        if "--check" in sys.argv[1:]:
            return 0 if check(args[0]) else 1
        patch(args[0])
        return 0 if check(args[0]) else 1
    except (OSError, ValueError, struct.error) as e:
        print("Error: %s" % e, file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
