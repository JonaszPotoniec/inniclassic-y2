#!/usr/bin/env python3
"""
patch_bluetooth_profiles.py

Turns off the phone profiles of Bluetooth.apk of the Y2: hands-free (HFP/HSP), phone book (PBAP) and messages (MAP).

The Y2 is a music player without a phone. With these profiles on, a car sees the Y2 as a phone and asks for its
phone book and messages; Settings then shows a dialog that the click wheel cannot answer. AdapterService starts only
the profile services whose profile_supported_* bool is true (Config.init). The manifest also sets android:enabled of
HeadsetService, BluetoothMapService and the PBAP activity, service and receiver to these bools, so Android disables
these components (PBAP is not in Config: its receiver starts it). An explicit `pm enable` of a component (kept in
/data/system/users/0/package-restrictions.xml) overrides the manifest value. A2DP and AVRCP (music and remote
control) do not change.

The patch changes only the data bytes of the three bools in resources.arsc and its CRC-32, as
patch_low_battery_warning.py does for framework-res.apk. resources.arsc must be stored (not compressed).

Usage: patch_bluetooth_profiles.py [--check] <path_to_Bluetooth.apk>
  --check  exit 0 if the APK has the patched values, else 1 (no change).
"""

import os
import struct
import sys
import tempfile
import zlib

from patch_low_battery_warning import (FLAG_COMPLEX, NO_ENTRY, RES_TABLE_PACKAGE_TYPE, RES_TABLE_TYPE,
                                       RES_TABLE_TYPE_TYPE, locate_arsc, read_string_pool)

TARGET = {
    "profile_supported_hs_hfp": False,
    "profile_supported_pbap": False,
    "profile_supported_map": False,
}

TYPE_INT_BOOLEAN = 0x12


def find_values(data):
    """Returns {key name: [file offset of the Res_value data]} for the bools in TARGET."""
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
                if vtype != TYPE_INT_BOOLEAN:
                    raise ValueError("%s: not a bool (type 0x%02x)" % (wanted[ekey], vtype))
                found[wanted[ekey]].append(e + esize + 4)
        pos += csize


def values(apk):
    start, size, _, _, _ = locate_arsc(apk)
    arsc = apk[start:start + size]
    found = find_values(arsc)
    return {k: [struct.unpack_from("<I", arsc, off)[0] != 0 for off in v] for k, v in found.items()}


def check(path):
    with open(path, "rb") as f:
        apk = f.read()
    current = values(apk)
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
            old = struct.unpack_from("<I", arsc, off)[0] != 0
            # Android writes true as 0xFFFFFFFF and false as 0
            struct.pack_into("<I", arsc, off, 0xFFFFFFFF if TARGET[key] else 0)
            print("%s: %s -> %s" % (key, old, TARGET[key]))
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
        print("Usage: patch_bluetooth_profiles.py [--check] <path_to_Bluetooth.apk>", file=sys.stderr)
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
