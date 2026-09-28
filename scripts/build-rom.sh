#!/usr/bin/env bash
# Build an InniClassic ROM for the Innioasis Y1 (type a/b) or Y2 (type y2).
#
# Lives in this repository as:  scripts/build-rom.sh
#
# Usage: build-rom.sh <a|b|y2> [--launcher-apk PATH] [--launcher-tag TAG] [--launcher-apk-url URL] [output.zip]
#
# Type a|b — Y1 stock bases from rockbox-y1.
# Type y2  — base ROM from y2-test release (Solar Y2 pack), strip Solar
#            artefacts, install InniClassic launcher as /system/app/com.themoon.y1.apk, and
#            pin scripts/Y2.kl (wheel -> DPAD_LEFT/RIGHT).
#
# Optional override:
#   Y2_BASE_URL=https://.../rom_y2.zip ./scripts/build-rom.sh y2 out.zip
set -euo pipefail

TYPE=""
LAUNCHER_APK_PATH=""
LAUNCHER_TAG=""
LAUNCHER_APK_URL=""
OUTPUT=""
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
WORK_DIR=""
MOUNT_SYS=""
MOUNT_USER=""

LAUNCHER_REPO="${LAUNCHER_REPO:-JonaszPotoniec/inniclassic-y2}"

# Published Y2 base (Solar-built pack for stripping + InniClassic install).
Y2_BASE_URL="${Y2_BASE_URL:-https://github.com/ryan-specter/jj_auto/releases/download/y2-test/rom_y2.zip}"

usage() {
    cat >&2 <<EOF
usage: $0 <a|b|y2> [--launcher-apk PATH] [--launcher-tag TAG] [--launcher-apk-url URL] [output.zip]

  a|b                   Type A (firmware 2.0.0+) or Type B (firmware before 2.0.0) — Y1
  y2                    Y2 ROM from y2-test base (Solar stripped, InniClassic + Y2.kl)
  --launcher-apk        Path to local APK to bundle (e.g. app-release.apk)
  --launcher-tag        Release tag (default: latest)
  --launcher-apk-url    Direct APK download URL (skips HTML/API lookup)
  output.zip            Output archive path
EOF
    exit 1
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        a|b|y2)
            TYPE="$1"
            shift
            ;;
        --launcher-apk)
            LAUNCHER_APK_PATH="${2:-}"
            [ -n "$LAUNCHER_APK_PATH" ] || usage
            shift 2
            ;;
        --launcher-tag)
            LAUNCHER_TAG="${2:-}"
            [ -n "$LAUNCHER_TAG" ] || usage
            shift 2
            ;;
        --launcher-apk-url)
            LAUNCHER_APK_URL="${2:-}"
            [ -n "$LAUNCHER_APK_URL" ] || usage
            shift 2
            ;;
        -h|--help)
            usage
            ;;
        *)
            if [ -z "$OUTPUT" ]; then
                OUTPUT="$1"
                shift
            else
                usage
            fi
            ;;
    esac
done

[ -n "$TYPE" ] || usage

cleanup() {
    if [ -n "$MOUNT_SYS" ] && mountpoint -q "$MOUNT_SYS" 2>/dev/null; then
        sudo umount "$MOUNT_SYS" || true
    fi
    if [ -n "$MOUNT_USER" ] && mountpoint -q "$MOUNT_USER" 2>/dev/null; then
        sudo umount "$MOUNT_USER" || true
    fi
    if [ -n "$WORK_DIR" ] && [ -d "$WORK_DIR" ]; then
        rm -rf "$WORK_DIR"
    fi
}
trap cleanup EXIT

die() {
    echo "error: $*" >&2
    exit 1
}

require_cmd() {
    command -v "$1" >/dev/null 2>&1 || die "missing required command: $1"
}

case "$TYPE" in
    a)
        BASE_URL="https://github.com/rockbox-y1/rockbox/releases/download/type-a-base/rom.zip"
        OUTPUT="${OUTPUT:-$REPO_ROOT/rom.zip}"
        ;;
    b)
        BASE_URL="https://github.com/rockbox-y1/rockbox/releases/download/type-b-base/rom.zip"
        OUTPUT="${OUTPUT:-$REPO_ROOT/rom_type_b.zip}"
        ;;
    y2)
        BASE_URL="$Y2_BASE_URL"
        OUTPUT="${OUTPUT:-$REPO_ROOT/rom_y2.zip}"
        ;;
esac

case "$OUTPUT" in
    /*) ;;
    *) OUTPUT="$PWD/$OUTPUT" ;;
esac

require_cmd curl
require_cmd unzip
require_cmd zip
require_cmd cmp
require_cmd sudo

resolve_latest_launcher_tag() {
    local release_url tag

    release_url="$(
        curl -fsSIL -A 'inniclassic-rom-build/1.0' \
            "https://github.com/${LAUNCHER_REPO}/releases/latest" \
        | awk -F': ' 'tolower($1) ~ /^location$/ { print $2 }' \
        | tr -d '\r' \
        | tail -1
    )"
    [ -n "$release_url" ] || die "could not resolve latest ${LAUNCHER_REPO} release URL"
    tag="${release_url##*/}"
    [ -n "$tag" ] || die "could not parse latest ${LAUNCHER_REPO} release tag"
    printf '%s' "$tag"
}

download_launcher_apk() {
    local dest="$1"
    local tag="${2:-}"
    local apk_url="${3:-}"

    if [ -n "$apk_url" ]; then
        echo "Downloading $(basename "$apk_url")"
        curl -fsSL -o "$dest" "$apk_url"
        return
    fi

    if [ -z "$tag" ]; then
        tag="$(resolve_latest_launcher_tag)"
    fi

    apk_url="$(
        curl -fsSL -A 'inniclassic-rom-build/1.0' \
            "https://github.com/${LAUNCHER_REPO}/releases/expanded_assets/${tag}" \
        | grep -Eo "href=\"/${LAUNCHER_REPO}/releases/download/[^\"]+(InniClassic|app-release)[^\"]*\.apk\"" \
        | head -1 \
        | sed 's/^href="//; s/"$//'
    )"
    [ -n "$apk_url" ] || die "could not find launcher APK for release ${tag} in ${LAUNCHER_REPO}"

    apk_url="https://github.com${apk_url}"
    echo "Downloading $(basename "$apk_url") from release ${tag}"
    curl -fsSL -o "$dest" "$apk_url"
}

# Remove Solar packages / helpers / config (used when base is a Solar Y2 pack).
strip_solar_artefacts() {
    local sys_mount="$1"
    local user_mount="$2"

    echo "==> Stripping Solar artefacts (if present)"

    while IFS= read -r apk; do
        [ -n "$apk" ] || continue
        echo "  removing $apk"
        sudo rm -f "$apk"
    done < <(
        find "$sys_mount/app" "$sys_mount/priv-app" \
            \( -iname '*solar*' -o -iname 'com.solar.*' -o -iname 'Solar*.apk' \) \
            2>/dev/null || true
    )

    for name in \
        com.solar.launcher.apk \
        com.solar.updater.apk \
        SolarUpdater.apk \
        SolarGlobalContext.apk \
        SolarGlobalContextModal.apk \
        SolarHomeHelper.apk \
        SolarContextBridgeY1.apk \
        SolarContextBridgeY2.apk \
        SolarThemeFont.apk \
        SolarRockboxIme.apk \
        SolarVersions.apk \
        Y1Bridge.apk
    do
        sudo rm -f "$sys_mount/app/$name" "$sys_mount/priv-app/$name" 2>/dev/null || true
    done

    sudo rm -rf "$sys_mount/etc/solar" 2>/dev/null || true
    sudo rm -f "$sys_mount/etc/init.d/"*Solar* 2>/dev/null || true
    sudo rm -f "$sys_mount/etc/init.d/99SolarInit.sh" \
               "$sys_mount/etc/init.d/99SolarPrep.sh" 2>/dev/null || true
    sudo rm -f "$sys_mount/etc/init.d/"*Xposed* \
               "$sys_mount/etc/init.d/99XposedInit.sh" 2>/dev/null || true
    if [ -f "$sys_mount/bin/app_process.orig" ]; then
        echo "  restoring stock /system/bin/app_process from app_process.orig"
        sudo cp -f "$sys_mount/bin/app_process.orig" "$sys_mount/bin/app_process"
        sudo chmod 755 "$sys_mount/bin/app_process"
        sudo chown root:shell "$sys_mount/bin/app_process" 2>/dev/null || sudo chown root:root "$sys_mount/bin/app_process"
        sudo rm -f "$sys_mount/bin/app_process.orig"
    fi
    sudo rm -f "$sys_mount/framework/XposedBridge.jar" 2>/dev/null || true
    sudo rm -rf "$sys_mount/data/data/de.robv.android.xposed.installer" 2>/dev/null || true

    if [ -n "$user_mount" ] && [ -d "$user_mount" ]; then
        sudo rm -rf "$user_mount/data/com.solar.launcher" \
                    "$user_mount/data/com.solar.updater" \
                    "$user_mount/data/com.solar.launcher.homehelper" \
                    "$user_mount/data/com.solar.launcher.globalcontext" \
                    2>/dev/null || true
        sudo rm -f "$user_mount/com.solar.launcher.apk" 2>/dev/null || true
        while IFS= read -r f; do
            [ -n "$f" ] || continue
            echo "  removing $f"
            sudo rm -rf "$f"
        done < <(find "$user_mount" -maxdepth 3 \( -iname '*solar*' \) 2>/dev/null || true)
    fi
}

audit_rom_contents() {
    local base_dir="$1"
    local sys_mount="$2"
    local user_mount="$3"
    local rom_type="$4"
    local errors=0

    echo "==> Auditing ROM contents (type=${rom_type})"

    for required in boot.img lk.bin logo.bin recovery.img system.img userdata.img; do
        if [ ! -f "$base_dir/$required" ]; then
            echo "audit fail: missing $required in ROM archive" >&2
            errors=$((errors + 1))
        fi
    done

    # Y1 bases ship MT6572 scatter; Y2 bases ship MT6582.
    if [ ! -f "$base_dir/MT6572_Android_scatter.txt" ] \
        && [ ! -f "$base_dir/MT6582_Android_scatter.txt" ]; then
        echo "audit fail: missing MT6572/MT6582 Android scatter in ROM archive" >&2
        errors=$((errors + 1))
    fi

    if find "$sys_mount/app" "$sys_mount/priv-app" -iname '*innioasis*' 2>/dev/null | grep -q .; then
        echo "audit fail: stock launcher APK still present under /system/app" >&2
        find "$sys_mount/app" "$sys_mount/priv-app" -iname '*innioasis*' 2>/dev/null >&2 || true
        errors=$((errors + 1))
    fi

    if find "$sys_mount/app" "$sys_mount/priv-app" \( -iname '*solar*' -o -iname 'com.solar.*' \) 2>/dev/null | grep -q .; then
        echo "audit fail: Solar APK(s) still present under /system" >&2
        find "$sys_mount/app" "$sys_mount/priv-app" \( -iname '*solar*' -o -iname 'com.solar.*' \) 2>/dev/null >&2 || true
        errors=$((errors + 1))
    fi

    if [ -d "$sys_mount/etc/solar" ]; then
        echo "audit fail: /system/etc/solar still present" >&2
        errors=$((errors + 1))
    fi

    if [ ! -f "$sys_mount/app/com.themoon.y1.apk" ]; then
        echo "audit fail: com.themoon.y1.apk missing from /system/app" >&2
        errors=$((errors + 1))
    fi

    if [ -f "$sys_mount/etc/init.d/99Y1ButtonScript" ] || [ -f "$sys_mount/etc/init.d/99Y1LauncherInit.sh" ]; then
        echo "audit fail: legacy init.d scripts still present" >&2
        errors=$((errors + 1))
    fi

    if [ -f "$sys_mount/app/org.rockbox.apk" ]; then
        echo "audit fail: org.rockbox.apk still present" >&2
        errors=$((errors + 1))
    fi

    if [ ! -f "$sys_mount/etc/install-recovery.sh" ]; then
        echo "audit fail: /system/etc/install-recovery.sh missing" >&2
        errors=$((errors + 1))
    fi

    # Y1: Generic.kl == Stock.kl (scripts/Stock.kl).
    # Y2: pin scripts/Y2.kl onto the device input names launcher needs.
    if [ "$rom_type" = "y2" ]; then
        if [ ! -f "$sys_mount/lib/libaudiocustparam.so" ]; then
            echo "audit fail: /system/lib/libaudiocustparam.so missing" >&2
            errors=$((errors + 1))
        elif ! python3 "$REPO_ROOT/tools/patch_y2_audio.py" --check "$sys_mount/lib/libaudiocustparam.so" >/dev/null 2>&1; then
            echo "audit fail: libaudiocustparam.so does not have low-gain headset curve" >&2
            errors=$((errors + 1))
        fi

        local y2_kl=""
        for y2_kl in Generic.kl Stock.kl mtk-tpd-kpd.kl mtk-kpd.kl Rockbox.kl Y2-Rockbox.kl; do
            if [ ! -f "$sys_mount/usr/keylayout/$y2_kl" ]; then
                echo "audit fail: keylayout $y2_kl missing" >&2
                errors=$((errors + 1))
            elif ! cmp -s "$SCRIPT_DIR/Y2.kl" "$sys_mount/usr/keylayout/$y2_kl"; then
                echo "audit fail: $y2_kl is not identical to scripts/Y2.kl" >&2
                errors=$((errors + 1))
            fi
        done
        if ! grep -qE '^key[[:space:]]+103[[:space:]]+DPAD_LEFT' "$sys_mount/usr/keylayout/mtk-tpd-kpd.kl"; then
            echo "audit fail: mtk-tpd-kpd.kl wheel scancode 103 is not DPAD_LEFT" >&2
            errors=$((errors + 1))
        fi
        if ! grep -qE '^key[[:space:]]+108[[:space:]]+DPAD_RIGHT' "$sys_mount/usr/keylayout/mtk-tpd-kpd.kl"; then
            echo "audit fail: mtk-tpd-kpd.kl wheel scancode 108 is not DPAD_RIGHT" >&2
            errors=$((errors + 1))
        fi
    else
        if [ ! -f "$sys_mount/usr/keylayout/Generic.kl" ] || [ ! -f "$sys_mount/usr/keylayout/Stock.kl" ]; then
            echo "audit fail: keylayout files missing" >&2
            errors=$((errors + 1))
        elif ! cmp -s "$sys_mount/usr/keylayout/Generic.kl" "$sys_mount/usr/keylayout/Stock.kl"; then
            echo "audit fail: Generic.kl is not identical to Stock.kl" >&2
            errors=$((errors + 1))
        fi
    fi

    if [ -n "$user_mount" ] && [ -d "$user_mount" ]; then
        if [ -f "$user_mount/jj_launcher.apk" ]; then
            echo "audit fail: legacy /data/jj_launcher.apk still present" >&2
            errors=$((errors + 1))
        fi

        if [ -f "$user_mount/com.innioasis.y1.apk" ]; then
            echo "audit fail: com.innioasis.y1.apk present in userdata" >&2
            errors=$((errors + 1))
        fi

        if [ -d "$user_mount/org.rockbox" ]; then
            echo "audit fail: /data/org.rockbox still present" >&2
            errors=$((errors + 1))
        fi
    fi

    if [ "$errors" -ne 0 ]; then
        die "ROM audit failed with $errors error(s)"
    fi

    echo "==> ROM audit passed"
}

WORK_DIR="$(mktemp -d)"
BASE_DIR="$WORK_DIR/base"
MOUNT_SYS="$BASE_DIR/mount_sys"
MOUNT_USER="$BASE_DIR/mount_user"
LAUNCHER_APK="$WORK_DIR/launcher.apk"

mkdir -p "$BASE_DIR" "$MOUNT_SYS" "$MOUNT_USER"

if [ -n "$LAUNCHER_APK_PATH" ]; then
    [ -f "$LAUNCHER_APK_PATH" ] || die "launcher APK not found at $LAUNCHER_APK_PATH"
    echo "==> Using local launcher APK: $LAUNCHER_APK_PATH"
    cp "$LAUNCHER_APK_PATH" "$LAUNCHER_APK"
elif [ -n "$LAUNCHER_APK_URL" ]; then
    echo "==> Downloading launcher APK from direct URL"
    download_launcher_apk "$LAUNCHER_APK" "$LAUNCHER_TAG" "$LAUNCHER_APK_URL"
elif [ -n "$LAUNCHER_TAG" ]; then
    echo "==> Downloading launcher APK for tag ${LAUNCHER_TAG}"
    download_launcher_apk "$LAUNCHER_APK" "$LAUNCHER_TAG"
else
    # Check if a built APK exists in the repository
    LOCAL_BUILT_APK="$(find "$REPO_ROOT/app/build/outputs/apk" -name '*.apk' 2>/dev/null | head -1 || true)"
    if [ -n "$LOCAL_BUILT_APK" ] && [ -f "$LOCAL_BUILT_APK" ]; then
        echo "==> Using repository built APK: $LOCAL_BUILT_APK"
        cp "$LOCAL_BUILT_APK" "$LAUNCHER_APK"
    else
        echo "==> Downloading latest launcher APK from ${LAUNCHER_REPO}"
        download_launcher_apk "$LAUNCHER_APK"
    fi
fi

echo "==> Downloading type-${TYPE} base firmware"
echo "    $BASE_URL"
curl -fsSL -L -o "$BASE_DIR/rom.zip" "$BASE_URL"
unzip -q "$BASE_DIR/rom.zip" -d "$BASE_DIR"

# Some packs nest images one directory deep.
if [ ! -f "$BASE_DIR/system.img" ]; then
    nested="$(find "$BASE_DIR" -maxdepth 3 -name system.img 2>/dev/null | head -1)"
    if [ -n "$nested" ]; then
        nested_dir="$(dirname "$nested")"
        echo "==> Promoting nested ROM dir: $nested_dir"
        shopt -s dotglob nullglob
        for item in "$nested_dir"/*; do
            base="$(basename "$item")"
            case "$base" in
                mount_sys|mount_user|rom.zip) continue ;;
            esac
            mv -f "$item" "$BASE_DIR/" 2>/dev/null || true
        done
        shopt -u dotglob nullglob
    fi
fi

[ -f "$BASE_DIR/system.img" ] || die "system.img not found after extracting base ROM"
[ -f "$BASE_DIR/userdata.img" ] || die "userdata.img not found after extracting base ROM"

# Replace boot logo on Y1 builds if logo_inniclassic.bin is present
if [ "$TYPE" != "y2" ] && [ -f "$REPO_ROOT/tools/rom_assets/logo_inniclassic.bin" ]; then
    echo "==> Applying InniClassic boot logo (tools/rom_assets/logo_inniclassic.bin)"
    cp -f "$REPO_ROOT/tools/rom_assets/logo_inniclassic.bin" "$BASE_DIR/logo.bin"
fi

echo "==> Mounting system.img and userdata.img"
sudo mount -t ext4 -o loop,rw "$BASE_DIR/system.img" "$MOUNT_SYS"
sudo mount -t ext4 -o loop,rw "$BASE_DIR/userdata.img" "$MOUNT_USER"

echo "==> Patching system partition"
while IFS= read -r apk; do
    [ -n "$apk" ] || continue
    echo "  removing $apk"
    sudo rm -f "$apk"
done < <(find "$MOUNT_SYS/app" "$MOUNT_SYS/priv-app" -iname '*innioasis*' 2>/dev/null || true)

sudo rm -f "$MOUNT_SYS/app/org.rockbox.apk"
sudo rm -f "$MOUNT_SYS/lib/librockbox.so"
sudo rm -f "$MOUNT_SYS/etc/init.d/99Y1ButtonScript"
sudo rm -f "$MOUNT_SYS/etc/init.d/99Y1LauncherInit.sh"

echo "==> Configuring /system/etc/install-recovery.sh for SuperSU and FM radio"
sudo tee "$MOUNT_SYS/etc/install-recovery.sh" > /dev/null << 'EOF'
#!/system/bin/sh

# Launch SuperSU daemon if present (required under SELinux enforcing on KitKat)
if [ -f /system/xbin/daemonsu ]; then
    /system/xbin/daemonsu --auto-daemon &
elif [ -f /system/xbin/su ]; then
    /system/xbin/su --daemon &
fi

# Ensure FM radio hardware character device nodes exist and are accessible by all apps
[ ! -c /dev/fm ] && mknod /dev/fm c 193 0 2>/dev/null
chmod 666 /dev/fm 2>/dev/null
chmod 666 /dev/FM50AF 2>/dev/null
chown system:media /dev/fm 2>/dev/null

# Bypass Android safe media volume limit (>80% headphone cap)
setprop audio.safemedia.bypass true
settings put global audio_safe_volume_state 2 2>/dev/null
settings put system audio_safe_volume_state 2 2>/dev/null
settings put global safe_media_volume_enabled 0 2>/dev/null
settings put system safe_media_volume_enabled 0 2>/dev/null

# Reset cached NVRAM volume table once so the patched libaudiocustparam.so takes effect on existing installs
if [ ! -f /data/nvram/.audio_vol_lowgain_v1 ]; then
    rm -f /data/nvram/APCFG/APRDEB/Audio_Vol_custom
    mkdir -p /data/nvram 2>/dev/null
    touch /data/nvram/.audio_vol_lowgain_v1 2>/dev/null
    chmod 644 /data/nvram/.audio_vol_lowgain_v1 2>/dev/null
fi
EOF
sudo chmod 755 "$MOUNT_SYS/etc/install-recovery.sh"
sudo chown root:root "$MOUNT_SYS/etc/install-recovery.sh"

if [ -d "$MOUNT_SYS/bin" ]; then
    sudo rm -f "$MOUNT_SYS/bin/install-recovery.sh"
    sudo ln -sf /system/etc/install-recovery.sh "$MOUNT_SYS/bin/install-recovery.sh" 2>/dev/null || \
    sudo cp -f "$MOUNT_SYS/etc/install-recovery.sh" "$MOUNT_SYS/bin/install-recovery.sh"
fi

if [ -f "$MOUNT_SYS/build.prop" ]; then
    if ! grep -q "^audio.safemedia.bypass" "$MOUNT_SYS/build.prop"; then
        echo "==> Setting audio.safemedia.bypass=true in build.prop"
        echo "audio.safemedia.bypass=true" | sudo tee -a "$MOUNT_SYS/build.prop" > /dev/null
    fi
fi

if [ -f "$MOUNT_SYS/framework/framework-res.apk" ]; then
    echo "==> Patching framework-res.apk to disable Safe Media Volume"
    sudo python3 "$REPO_ROOT/tools/patch_safe_volume.py" "$MOUNT_SYS/framework/framework-res.apk"
    sudo chmod 644 "$MOUNT_SYS/framework/framework-res.apk"
    sudo chown root:root "$MOUNT_SYS/framework/framework-res.apk"
fi

# Y2 Solar base only: strip Solar before installing InniClassic. Type a|b skip this (Y1 path unchanged).
if [ "$TYPE" = "y2" ]; then
    strip_solar_artefacts "$MOUNT_SYS" "$MOUNT_USER"
    echo "==> Patching Y2 audio gain table for quiet headphone listening"
    sudo python3 "$REPO_ROOT/tools/patch_y2_audio.py" "$MOUNT_SYS/lib/libaudiocustparam.so"
    sudo chmod 644 "$MOUNT_SYS/lib/libaudiocustparam.so"
    sudo chown root:root "$MOUNT_SYS/lib/libaudiocustparam.so"
fi

sudo mkdir -p "$MOUNT_SYS/app" "$MOUNT_SYS/usr/keylayout"
sudo cp "$LAUNCHER_APK" "$MOUNT_SYS/app/com.themoon.y1.apk"
sudo chmod 644 "$MOUNT_SYS/app/com.themoon.y1.apk"
sudo chown root:root "$MOUNT_SYS/app/com.themoon.y1.apk"

echo "==> Patching userdata partition"
sudo rm -rf "$MOUNT_USER/org.rockbox"
sudo rm -f "$MOUNT_USER/com.innioasis.y1.apk"
sudo rm -f "$MOUNT_USER/data/com.innioasis.y1.apk"
sudo rm -f "$MOUNT_USER/jj_launcher.apk"
sudo rm -f "$MOUNT_USER/data/initialized"
sudo rm -f "$MOUNT_USER/data/jj_launcher_initialized"
if [ "$TYPE" = "y2" ]; then
    strip_solar_artefacts "$MOUNT_SYS" "$MOUNT_USER"
    sudo rm -f "$MOUNT_USER/data/nvram/APCFG/APRDEB/Audio_Vol_custom"
    sudo rm -f "$MOUNT_USER/data/nvram/.audio_vol_lowgain_v1"
fi

# Pin compatible keylayouts last on /system so first boot cannot
# inherit the Rockbox base map. Lives in system.img (not userdata).
# Y1: scripts/Stock.kl -> Generic.kl + Stock.kl.
# Y2: scripts/Y2.kl -> Generic.kl + device-named layouts (mtk-tpd-kpd carries the wheel).
if [ "$TYPE" = "y2" ]; then
    [ -f "$SCRIPT_DIR/Y2.kl" ] || die "missing $SCRIPT_DIR/Y2.kl"
    echo "==> Pinning Y2 keylayout (scripts/Y2.kl) for first boot"
    for kl_name in Generic.kl Stock.kl mtk-tpd-kpd.kl mtk-kpd.kl Rockbox.kl Y2-Rockbox.kl; do
        sudo cp "$SCRIPT_DIR/Y2.kl" "$MOUNT_SYS/usr/keylayout/$kl_name"
        sudo chmod 644 "$MOUNT_SYS/usr/keylayout/$kl_name"
        sudo chown root:root "$MOUNT_SYS/usr/keylayout/$kl_name"
        echo "  /system/usr/keylayout/$kl_name"
    done
else
    echo "==> Pinning Y1 keylayout (scripts/Stock.kl)"
    sudo cp "$SCRIPT_DIR/Stock.kl" "$MOUNT_SYS/usr/keylayout/Stock.kl"
    sudo cp "$SCRIPT_DIR/Stock.kl" "$MOUNT_SYS/usr/keylayout/Generic.kl"
    sudo chmod 644 "$MOUNT_SYS/usr/keylayout/Stock.kl" "$MOUNT_SYS/usr/keylayout/Generic.kl"
    sudo chown root:root "$MOUNT_SYS/usr/keylayout/Stock.kl" "$MOUNT_SYS/usr/keylayout/Generic.kl"
fi

audit_rom_contents "$BASE_DIR" "$MOUNT_SYS" "$MOUNT_USER" "$TYPE"

echo "==> Syncing and unmounting images"
sync
sudo umount "$MOUNT_SYS"
sudo umount "$MOUNT_USER"
MOUNT_SYS=""
MOUNT_USER=""

rm -f "$BASE_DIR/rom.zip"
rm -rf "$BASE_DIR/mount_sys" "$BASE_DIR/mount_user"

mkdir -p "$(dirname "$OUTPUT")"
echo "==> Creating $OUTPUT"
rm -f "$OUTPUT"
(
    cd "$BASE_DIR"
    # Pack base contents (scatter, images, SP Flash Tool helpers, etc.).
    zip -r -q "$OUTPUT" ./*
)

echo "==> Built $OUTPUT ($(du -h "$OUTPUT" | awk '{print $1}'))"
