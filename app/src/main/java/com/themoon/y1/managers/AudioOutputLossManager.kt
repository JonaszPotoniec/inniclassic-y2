package com.themoon.y1.managers

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.SimpleExoPlayer
import com.themoon.y1.MainActivity

/**
 * Pauses the music when the Bluetooth audio device disconnects or the headphones are unplugged, and plays it again
 * when the same Bluetooth device connects again (rules: [AudioOutputLossLogic]).
 *
 * Android 4.4 sends ACTION_AUDIO_BECOMING_NOISY for both; the A2DP connection broadcast is also used, because it
 * names the device (and in case a ROM does not send the noisy broadcast). Each decision goes to the log with
 * tag [TAG] and a reason (`why: bt-disconnect`, `why: bt-reconnect-resume`, ...).
 */
object AudioOutputLossManager {
    private const val TAG = "BtAudioResume"
    private const val A2DP_STATE_CHANGED = "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED"

    private val logic = AudioOutputLossLogic()
    private val handler = Handler(Looper.getMainLooper())
    private var registered = false
    private var attachedPlayer: SimpleExoPlayer? = null
    private var connectedAddress: String? = null

    private val resume = Runnable {
        val d = logic.onResumeDue(connectedAddress, SystemClock.elapsedRealtime(),
            connected = connectedAddress != null, playing = playing(), track = track())
        log(d, connectedAddress)
        if (d.action == AudioOutputLossLogic.Action.RESUME_NOW) AudioPlayerManager.getInstance().playOrPauseMusic()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                    val d = logic.onNoisy(SystemClock.elapsedRealtime(), playing(), track())
                    log(d, null)
                    if (d.action == AudioOutputLossLogic.Action.PAUSE) pause()
                }
                A2DP_STATE_CHANGED -> onA2dpState(intent)
            }
        }
    }

    /** Call once at app start. */
    fun init(context: Context) {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            addAction(A2DP_STATE_CHANGED)
        }
        context.applicationContext.registerReceiver(receiver, filter)
        registered = true
    }

    /**
     * Called by [AudioPlayerManager.playOrPauseMusic] before a pause. True: do not pause (it is the car's own Play
     * just after our resume, see [AudioOutputLossLogic.ignoresPause]).
     */
    fun ignoresPause(): Boolean {
        if (!logic.ignoresPause(SystemClock.elapsedRealtime())) return false
        Log.w(TAG, "play/pause key ignored, why: bt-reconnect-duplicate-play (music started by the reconnect)")
        return true
    }

    @SuppressLint("MissingPermission")
    private fun onA2dpState(intent: Intent) {
        val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
        val prev = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1)
        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        val address = device?.address
        val name = try { device?.name } catch (_: Exception) { null }
        val now = SystemClock.elapsedRealtime()
        when {
            state == BluetoothProfile.STATE_CONNECTED -> {
                connectedAddress = address
                val d = logic.onA2dpConnected(address, now, playing(), track())
                log(d, name ?: address)
                if (d.action == AudioOutputLossLogic.Action.RESUME_LATER) {
                    handler.removeCallbacks(resume)
                    handler.postDelayed(resume, AudioOutputLossLogic.RESUME_DELAY_MS)
                }
            }
            state == BluetoothProfile.STATE_DISCONNECTED &&
                (prev == BluetoothProfile.STATE_CONNECTED || prev == BluetoothProfile.STATE_DISCONNECTING) -> {
                if (connectedAddress == address) connectedAddress = null
                handler.removeCallbacks(resume)
                val d = logic.onA2dpDisconnected(address, name, now, playing(), track())
                log(d, name ?: address)
                if (d.action == AudioOutputLossLogic.Action.PAUSE) pause()
            }
        }
    }

    private fun pause() {
        val am = AudioPlayerManager.getInstance()
        if (am.isPlaying()) am.playOrPauseMusic()
        attachPlayer()
    }

    /** Watches the player while a pause is pending: a start of the music that is not our resume cancels it. */
    private fun attachPlayer() {
        val exo = AudioPlayerManager.getInstance().exoPlayer ?: return
        if (exo === attachedPlayer) return
        attachedPlayer = exo
        exo.addListener(object : Player.EventListener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady || logic.pending == null) return
                handler.removeCallbacks(resume)
                log(logic.onPlayStarted(), null)
            }
        })
    }

    private fun playing(): Boolean = try { AudioPlayerManager.getInstance().isPlaying() } catch (_: Exception) { false }

    private fun track(): String? {
        val main = MainActivity.instance ?: return null
        return main.currentPlaylist.getOrNull(main.currentIndex)?.absolutePath
    }

    private fun log(d: AudioOutputLossLogic.Decision, device: String?) {
        val what = when (d.action) {
            AudioOutputLossLogic.Action.PAUSE -> "pause"
            AudioOutputLossLogic.Action.RESUME_LATER -> "resume in ${AudioOutputLossLogic.RESUME_DELAY_MS} ms"
            AudioOutputLossLogic.Action.RESUME_NOW -> "resume"
            AudioOutputLossLogic.Action.DROP -> "no resume"
            AudioOutputLossLogic.Action.NONE -> "no action"
        }
        Log.w(TAG, "$what, why: ${d.why}" + (device?.let { " ($it)" } ?: ""))
    }
}
