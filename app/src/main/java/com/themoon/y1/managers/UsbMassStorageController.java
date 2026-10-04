package com.themoon.y1.managers;

/**
 * Y2-only USB Mass Storage control via MountService binder calls as root.
 * IMountService (KitKat) txn codes, verified on Y2:
 * 3 = isUsbMassStorageConnected(), 4 = setUsbMassStorageEnabled(boolean), 5 = isUsbMassStorageEnabled().
 */
public final class UsbMassStorageController {
    private static final String TAG = "Y1Ums";

    private UsbMassStorageController() {}

    public static boolean isConnected() {
        return parseBool(call("service call mount 3"));
    }

    public static boolean isEnabled() {
        return parseBool(call("service call mount 5"));
    }

    /** Returns the resulting enabled state (polls up to ~6 s for vold to settle). */
    public static boolean setEnabled(boolean enable) {
        call("service call mount 4 i32 " + (enable ? 1 : 0));
        for (int i = 0; i < 30; i++) {
            boolean now = isEnabled();
            if (now == enable) return now;
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                break;
            }
        }
        return isEnabled();
    }

    private static String call(String cmd) {
        String out = ExternalSdMountMonitor.runSuTimed(cmd);
        android.util.Log.i(TAG, cmd + " -> " + out);
        return out;
    }

    /** "Result: Parcel(00000000 00000001   '........')" -> second word == 1 */
    static boolean parseBool(String out) {
        if (out == null) return false;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("Parcel\\(\\s*[0-9a-fA-F]{8}\\s+([0-9a-fA-F]{8})").matcher(out);
        return m.find() && Integer.parseInt(m.group(1), 16) != 0;
    }
}
