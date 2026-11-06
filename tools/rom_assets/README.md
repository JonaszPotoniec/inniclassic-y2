Binary assets for the flashable `rom.zip`, kept in the repo so ROM builds are reproducible without depending on a local Innioasis Updater install.

## `logo_classy.bin` — the one to actually use

**Classy**, white logo centered on black, 480×360 (the Y1's real panel resolution). This is what goes into new `rom.zip` builds as `logo.bin` (picked up automatically by `scripts/build-rom.sh`). Generated from `docs/icons/icon.png` by [`tools/build_boot_logo.py`](../build_boot_logo.py):

```bash
python3 tools/build_boot_logo.py docs/icons/icon.png tools/rom_assets/logo_classy.bin
```

Requires Pillow and numpy (`pip install Pillow numpy`).

## `logo_inniclassic.bin` — previous branding asset

The previous **InniClassic** bold text splash, kept for historical reference. Also buildable with `build_boot_logo.py`:

```bash
python3 tools/build_boot_logo.py "InniClassic" tools/rom_assets/logo_inniclassic.bin
```

## `logo_stock_innioasis.bin` — reference sample, not shipped

The `LOGO` partition (boot splash, 3 MB, `MT6572_Android_scatter.txt` partition `SYS13`) extracted from a genuine Innioasis Y1 stock firmware ZIP (`y1-community_y1-stock-rom_ADB-2.1.9`, matches the "System Software 2.1.9" the standalone APK install path is verified against). Kept around as the header template `build_boot_logo.py` copies bytes 0–511 from (see format notes below) — not meant to be flashed itself.

The `system.img`/`rom.zip` builds through v1.3.2 shipped a **Rockbox**-branded `logo.bin` instead of either of the above — a leftover from the source device the original 12-partition set was captured from (its history: stock firmware → Rockbox → this launcher). It was a valid, correctly-structured `LOGO` partition, so it flashed and displayed fine, just with Rockbox's splash instead of anything InniClassic- or Innioasis-branded.

## `Bluetooth.apk` + `Bluetooth.odex` — Y2 Bluetooth service

The Y2 base ROM (`y2-test`) has no `Bluetooth.apk`, so Bluetooth cannot turn on (#21). These are the original `/system/app/Bluetooth.apk` + `Bluetooth.odex` of the same Innioasis build as the base (fingerprint `Y2/Y2/Y2:4.4.2/KOT49H/1779084676:user/test-keys`), taken from `original_system.img` in the y1-community [`y2-ata`](https://github.com/y1-community/y2-ata-rom/releases/tag/y2-ata) ROM (`rom_y2.zip`). They are signed with the same Innioasis key as the stock `MtkBt.apk`, and the odex matches the base framework, so they need no patch or re-signing.

| File | Size | SHA-256 |
|---|---|---|
| `Bluetooth.apk` | 442,761 | `064855fd299e54d30e114b4d441e1f14657e62b4325cfed5e5c7b9a652e81b37` |
| `Bluetooth.odex` | 966,200 | `f543c2842589e70f6d4582e8992e2ccf1f2e0e1fa19b454fabf039b5f8e08fc2` |

`scripts/build-rom.sh y2` checks both hashes, installs the files in `/system/app` (root:root 644), then runs [`tools/patch_bluetooth_profiles.py`](../patch_bluetooth_profiles.py) on the installed `Bluetooth.apk` (hands-free, phone book and message profiles off; only `resources.arsc` changes, as with the `framework-res.apk` patches, so the odex still matches). The files here stay stock. The audit fails if the files are missing, if the odex is different, or if the installed APK is not the patched stock APK (SHA-256 `99d8d244e6dd083266985ef473bbe86fe51026d11ab724f9a78ca677cc388013`). Keep the stock `MtkBt.apk`.

## Format notes

MTK `LOGO` partition, fully reverse-engineered from stock firmware and `libshowlogo.so` (`check_logo_index_valid`). The container holds 39 image streams (stream 0 = primary boot logo, stream 38 = secondary splash, streams 1–37 = battery charging animations, percentages, and alerts):

- Bytes 0–3: constant `88 16 88 58` (`0x58881688` LE `PART_MAGIC`).
- Bytes 4–7: `uint32` LE `dsize`, partition payload size read by `mboot_common_load_logo` (equals the table's `total_size` field at byte 516).
- Bytes 8–11: ASCII `LOGO` tag.
- Bytes 12–511: reserved MTK partition header padding (copied verbatim from stock reference).
- Byte 512: `uint32` LE `frame_count` (39 in stock firmware).
- Byte 516: `uint32` LE `total_size` (cumulative size of table + all streams, matching bytes 4–7).
- Byte 520 onward: `frame_count` × `uint32` LE cumulative byte offsets measured from byte 512:
  - `offset[0] = 520 + 4 * frame_count - 512 = 164` (the start of stream 0 relative to byte 512).
  - `offset[i] = offset[i-1] + len(stream[i-1])`.
  - For stream $i$, LK calculates length as `offset[i+1] - offset[i]` (or `total_size - offset[i]` for the last stream) and payload address as `table_base + offset[i]`.
- From there: individual `zlib`-compressed streams. Streams 0 and 38 are raw RGB565 (`(R>>3)<<11 | (G>>2)<<5 | B>>3`, little-endian `uint16` per pixel, row-major, 480×360), while other streams contain charging animations and UI elements.


When rebuilding `rom.zip`, use `logo_classy.bin` in place of `logo.bin` from the reference partition set (everything else — `EBR1`, `MBR`, `boot.img`, `cache.img`, `lk.bin`, `preloader_g368_nyx.bin`, `recovery.img`, `secro.img`, `userdata.img`, `MT6572_Android_scatter.txt` — stays whatever the previous release's `rom.zip` shipped, only `system.img`'s APK and the logo change).
