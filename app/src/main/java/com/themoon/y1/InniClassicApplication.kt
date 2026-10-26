package com.themoon.y1

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.util.Log
import androidx.multidex.MultiDexApplication
import org.conscrypt.Conscrypt
import java.security.Security

/**
 * Application class for InniClassic.
 * Ensures modern TLS/SSL security providers (Conscrypt) are initialized on startup
 * before any networking calls or background services execute.
 */
class InniClassicApplication : MultiDexApplication() {

    override fun onCreate() {
        super.onCreate()
        installConscrypt()
        stopStaleProcesses()
    }

    /**
     * Stops older processes of this app. After `adb install -r` of the Home app, Android 4.4 can keep the old
     * process alive next to the new one (seen on the Y2, 2026-10-26). The old process still gets the screen-on
     * broadcast and starts its own backlight timer, which no key resets: the screen then turned off 60 s after
     * each wake, also during use. The app has one process, so any other process with our name and user is old.
     */
    private fun stopStaleProcesses() {
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
            val running = am.runningAppProcesses ?: return
            val stale = staleProcessIds(
                Process.myPid(), Process.myUid(), packageName,
                running.map { RunningProcess(it.pid, it.uid, it.processName) }
            )
            for (pid in stale) {
                Log.w(TAG, "Stopping an old process of this app: pid $pid")
                Process.killProcess(pid)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not check for old processes: ${t.message}")
        }
    }

    data class RunningProcess(val pid: Int, val uid: Int, val name: String?)

    companion object {
        private const val TAG = "InniClassicApplication"

        /** The other processes with our process name and user: older copies of this app. */
        fun staleProcessIds(myPid: Int, myUid: Int, processName: String, running: List<RunningProcess>): List<Int> =
            running.filter { it.pid != myPid && it.uid == myUid && it.name == processName }.map { it.pid }

        @JvmStatic
        fun installConscrypt() {
            try {
                if (Security.getProvider("Conscrypt") == null) {
                    Security.insertProviderAt(Conscrypt.newProvider(), 1)
                    Log.i(TAG, "Conscrypt provider installed successfully")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to install Conscrypt security provider: ${t.message}")
            }
        }
    }
}
