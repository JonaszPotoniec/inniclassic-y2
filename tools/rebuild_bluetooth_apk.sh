#!/usr/bin/env bash
# Rebuild and sign Bluetooth.apk for Innioasis Y2 (MT6582 / Android 4.4.2)
#
# Not used by the ROM build: tools/rom_assets now holds the original Innioasis
# Bluetooth.apk + Bluetooth.odex of the same build (see tools/rom_assets/README.md), which
# scripts/build-rom.sh installs in /system/app with their pinned SHA-256. They already match
# the stock MtkBt.apk signature and libbluetooth_jni.so, so no rebuild or re-signing is needed.
#
# Background:
# The stock y2-test base ROM stripped /system/app/Bluetooth.apk and Bluetooth.odex.
# Standard AOSP KitKat 4.4.4 Bluetooth.apk has several JNI divergences with MediaTek's
# closed-source libbluetooth_jni.so (Android 4.4.2 BlueAngel stack):
#   1. GattService expected onClientListen(II)V callback (renamed in 4.4.4 to onAdvertiseCallback).
#   2. GattService native method signatures: gattAdvertiseNative vs gattClientListenNative.
#   3. AdapterService expected adapterResetIndCallback()V callback.
#   4. Process must run under sharedUserId="android.uid.bluetooth" (UID 1002) to permit
#      protected broadcasts (DISCOVERY_STARTED, DISCOVERY_FINISHED, ACTION_FOUND, etc.)
#      without SecurityException from ActivityManagerService.
#   5. Both Bluetooth.apk and MtkBt.apk must share the same signature (platform_test.jks).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ASSETS_DIR="$SCRIPT_DIR/rom_assets"
KEYSTORE="$ASSETS_DIR/platform_test.jks"
KEYPASS="123456"
KEYALIAS="testkey"

if [ ! -f "$KEYSTORE" ]; then
    echo "Creating signing keystore: $KEYSTORE"
    keytool -genkeypair -v -keystore "$KEYSTORE" -alias "$KEYALIAS" \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass "$KEYPASS" -keypass "$KEYPASS" -dname "CN=Test"
fi

echo "==> Bluetooth assets present in $ASSETS_DIR:"
ls -lh "$ASSETS_DIR/Bluetooth.apk" "$ASSETS_DIR/MtkBt.apk" "$KEYSTORE"

echo "==> Done."
