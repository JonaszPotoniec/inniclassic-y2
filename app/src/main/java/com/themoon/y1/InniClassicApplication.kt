package com.themoon.y1

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
    }

    companion object {
        private const val TAG = "InniClassicApplication"

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
