# InniClassic Release History & Style Examples

## Findings from the 2026-10 Release Audit

1. **Tag Chronology & Versioning**:
   - Releases on `JonaszPotoniec/inniclassic-y2` use `YYYYMMDD` tags (and `YYYYMMDD-vN` or `YYYYMMDD-build.N` for same-day revisions).
   - `v1.5.0` (`97775e3`) was the initial internal tag before the CI naming switch; `20260927` (`ef9d2fc`) is the first published GitHub Release and covers `v1.5.0...20260927` (initial Innioasis Y2 support on top of upstream `1.4.3`).
   - `20260928` was followed on the same day by `20260928-build.6`, which was then followed by `20260929`. Therefore `20260929` compares against `20260928-build.6` (`20260928-build.6...20260929`).
2. **CI Release Workflow Behavior**:
   - `.github/workflows/release.yml` automatically builds `InniClassic-<TAG>.apk`, `update.json`, `rom_y2.zip`, `rom_y1_type_a.zip` (`rom.zip`), `rom_y1_type_b.zip` (`rom_type_b.zip`), and `SHA256SUMS.txt` on every push to `main`.
   - Note that `20260927` predates OTA `update.json` (which was introduced in `20260928` via commit `40d1141`). All releases from `20260928` onward include `update.json`.
   - The `Prepare Release Notes` step in `.github/workflows/release.yml` writes a static `release_body.md` and sets `generate_release_notes: true`. When updating a release with `gh release edit <TAG> --notes-file ...`, replace the entire body using the clean template in `SKILL.md`.

## Reference Examples (Humanized Style)

### Example 1: `20261012` (Feature + Multi-Part Bug Fix)

```markdown
- Added Jellyfin sync so you can download music and videos directly from your Jellyfin server over Wi-Fi. You can sign in using QuickConnect (with an on-screen pairing code), username and password, or an API token, then sync all libraries or browse and pick specific folders. Sync options let you download album artwork, transcode videos for playback on the player, and remove local files that were deleted on the server, followed by an automatic library rescan when downloads finish.
- Fixed four Bluetooth issues on the Y2:
  - Toggling Bluetooth on now starts the MediaTek Bluetooth daemon (`mtkbt`) automatically (and starts it at boot in the ROM), fixing the bug where Bluetooth refused to turn back on or reconnect to paired headphones after being turned off.
  - Turning Bluetooth off now stays off across reboots instead of turning itself back on at startup.
  - "Delete Device" now verifies that unpairing succeeded before showing a confirmation message and refreshes the paired list cleanly.
  - "Connect Audio" and "Delete Device" options are now visually indented beneath the selected device row.

#### Release assets

| File | Target device | Notes |
| :--- | :--- | :--- |
| `InniClassic-20261012.apk` | All devices (Y1 / Y2) | Standalone launcher APK (sideload or install) |
| `update.json` | All devices | OTA update metadata for in-app updater |
| `rom_y2.zip` | Innioasis Y2 (MT6582) | Complete flashable ROM with dual-storage, watchdog & clickwheel keylayout |
| `rom_y1_type_a.zip` (`rom.zip`) | Innioasis Y1 (Type A) | Flashable ROM for Y1 players with original firmware 2.0.0 or higher |
| `rom_y1_type_b.zip` (`rom_type_b.zip`) | Innioasis Y1 (Type B) | Flashable ROM for Y1 players with original firmware below 2.0.0 |
| `SHA256SUMS.txt` | All files | SHA-256 integrity checksums |

Full changelog: https://github.com/JonaszPotoniec/inniclassic-y2/compare/20261011...20261012
```

### Example 2: `20261011` (Multi-Feature Release with Varied Openings)

```markdown
- The About screen now uses a scrollable two-column iPod Classic layout showing song, video, album, and artist counts, software version, model number, and separate capacity and free space rows for both internal flash and the microSD card.
- A new "Battery Percentage" setting draws the exact percentage and a compact charging bolt inside the battery icon, with contrasting text colors across the filled and empty parts of the indicator.
- Genre browsing now follows the classic iPod hierarchy (`Genre -> Artists / All Songs -> Albums / All Songs -> Songs`), and song lists have a Shuffle banner with a song counter at the top. Returning from Now Playing also highlights the playing track, fast scrolling no longer crashes on lists with header rows, and the wheel no longer gets stuck at list edges.
- FM Radio now remembers your last tuned frequency and saved station presets across restarts, tunes smoothly with the wheel without screen flicker, supports hardware station seek, and routes audio directly to the 3.5mm headphone jack after pausing any playing music.
- Factory OEM demo tracks in `custom_media` (such as *Despacito* and *Panama*) are no longer scanned, and an automatic cleanup migration removes them from existing libraries while stripping them from newly built ROMs.
- Both button presses and touch events now reset the screen backlight sleep timer, and the screen stays awake automatically during library scans, radio scans, podcast downloads, video playback, and USB storage mode.

#### Release assets

| File | Target device | Notes |
| :--- | :--- | :--- |
| `InniClassic-20261011.apk` | All devices (Y1 / Y2) | Standalone launcher APK (sideload or install) |
| `update.json` | All devices | OTA update metadata for in-app updater |
| `rom_y2.zip` | Innioasis Y2 (MT6582) | Complete flashable ROM with dual-storage, watchdog & clickwheel keylayout |
| `rom_y1_type_a.zip` (`rom.zip`) | Innioasis Y1 (Type A) | Flashable ROM for Y1 players with original firmware 2.0.0 or higher |
| `rom_y1_type_b.zip` (`rom_type_b.zip`) | Innioasis Y1 (Type B) | Flashable ROM for Y1 players with original firmware below 2.0.0 |
| `SHA256SUMS.txt` | All files | SHA-256 integrity checksums |

Full changelog: https://github.com/JonaszPotoniec/inniclassic-y2/compare/20261008...20261011
```

## Published Release Tag Index

| Tag | Previous Tag | Summary of Changes |
| :--- | :--- | :--- |
| `20260927` | `v1.5.0` | Initial Y2 support, dual storage (`sdcard0` + `sdcard1`), microSD watchdog, Y2 click wheel keymap, Y2 FM radio permissions/routing |
| `20260928` | `20260927` | Y2 quiet headphone volume curve (`libaudiocustparam.so`), OkHttp GitHub Releases OTA (`update.json`), Music Quiz cover sizing/ALAC art fix |
| `20260928-build.6` | `20260928` | Safe Media Volume cap bypass (100% volume + touch slider), Polish translation |
| `20260929` | `20260928-build.6` | Mute wheel clicks, touch sounds, and vibration when screen is off and Screen Off Control is disabled |
| `20261001` | `20260929` | 30-step virtual volume + sub-steps + iPod HUD, QWERTY keyboard + punctuation, buffered Cover Flow + `.y1_thumbs_cache`, Hold key lock, Now Playing/video handoff, UMS theme persistence |
| `20261006` | `20261001` | USB Mass Storage connection prompt & eject screen, 7-language localization pass |
| `20261007` | `20261006` | Fix missing songs with matching titles across albums, versioned upgrade migration system |
| `20261008` | `20261007` | Redesigned OTA update UI + Wi-Fi wait + reinstall option, `/system/app` reboot install fix, Tabler Wi-Fi icon + light theme status bar contrast |
| `20261011` | `20261008` | Two-column iPod Classic About screen, Battery Percentage icon setting, Genre hierarchy + Shuffle banner + scroll fixes, FM Radio persistence & 3.5mm routing, OEM demo track purge (`custom_media`), backlight timer fixes |
| `20261012` | `20261011` | Jellyfin library sync (QuickConnect/credentials/token, folder picker, transcode, auto-scan), Y2 Bluetooth fixes (`mtkbt` startup, power-off persistence, delete verification, option indentation) |
