package com.themoon.y1

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.themoon.y1.managers.AudioPlayerManager

class ShutdownReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (Intent.ACTION_SHUTDOWN == action ||
            "android.intent.action.QUICKBOOT_POWEROFF" == action ||
            "com.htc.intent.action.QUICKBOOT_POWEROFF" == action
        ) {
            AudioPlayerManager.getInstance().savePlaybackState(sync = true)
        }
    }
}
