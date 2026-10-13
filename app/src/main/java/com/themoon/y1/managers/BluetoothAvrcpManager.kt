package com.themoon.y1.managers

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import java.lang.reflect.Method

/**
 * Manages Bluetooth A2DP profile connections, reflection calls to connect/disconnect/priority,
 * and AVRCP metadata configuration for the Innioasis Y2.
 */
class BluetoothAvrcpManager private constructor(private val context: Context) {

    private var a2dpProfile: BluetoothProfile? = null

    interface ConnectionCallback {
        fun onConnected(device: BluetoothDevice)
        fun onDisconnected(device: BluetoothDevice)
        fun onError(message: String)
    }

    fun init(onServiceConnected: (() -> Unit)? = null) {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.A2DP) {
                    a2dpProfile = proxy
                    onServiceConnected?.invoke()
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.A2DP) {
                    a2dpProfile = null
                }
            }
        }, BluetoothProfile.A2DP)
    }

    fun isA2dpReady(): Boolean = a2dpProfile != null

    fun connectDevice(targetDevice: BluetoothDevice, callback: ConnectionCallback? = null) {
        val a2dp = a2dpProfile
        if (a2dp == null) {
            init { connectDevice(targetDevice, callback) }
            return
        }

        try {
            // Restore priority so Android Bluetooth stack allows connection
            try {
                val setPriorityMethod = a2dp.javaClass.getMethod("setPriority", BluetoothDevice::class.java, Int::class.javaPrimitiveType)
                setPriorityMethod.invoke(a2dp, targetDevice, 100) // PRIORITY_ON
            } catch (ignored: Exception) {}

            // Disconnect other already connected devices first
            val connectedDevices = a2dp.connectedDevices
            if (connectedDevices != null) {
                for (connected in connectedDevices) {
                    if (connected.address != targetDevice.address) {
                        disconnectA2dpInternal(a2dp, connected)
                    }
                }
            }

            var connectMethod: Method? = null
            try {
                connectMethod = a2dp.javaClass.getDeclaredMethod("connect", BluetoothDevice::class.java)
            } catch (e: NoSuchMethodException) {
                for (m in a2dp.javaClass.methods) {
                    if (m.name == "connect" && m.parameterTypes.size == 1) {
                        connectMethod = m
                        break
                    }
                }
            }

            if (connectMethod == null) {
                callback?.onError("Connect method not found on A2DP profile")
                return
            }

            connectMethod.isAccessible = true
            val result = connectMethod.invoke(a2dp, targetDevice) as? Boolean ?: false
            if (result) {
                callback?.onConnected(targetDevice)
            } else {
                callback?.onError("Connection rejected by Bluetooth stack")
            }
        } catch (e: Exception) {
            callback?.onError(e.message ?: "Connection failed")
        }
    }

    fun disconnectDevice(targetDevice: BluetoothDevice, callback: ConnectionCallback? = null) {
        val a2dp = a2dpProfile ?: return
        try {
            // Set priority to PRIORITY_OFF (0) so incoming reconnect from earphones is rejected
            try {
                val setPriorityMethod = a2dp.javaClass.getMethod("setPriority", BluetoothDevice::class.java, Int::class.javaPrimitiveType)
                setPriorityMethod.invoke(a2dp, targetDevice, 0)
            } catch (ignored: Exception) {}

            disconnectA2dpInternal(a2dp, targetDevice)
            callback?.onDisconnected(targetDevice)
        } catch (e: Exception) {
            callback?.onError(e.message ?: "Disconnect failed")
        }
    }

    private fun disconnectA2dpInternal(a2dp: BluetoothProfile, targetDevice: BluetoothDevice) {
        var disconnectMethod: Method? = null
        try {
            disconnectMethod = a2dp.javaClass.getDeclaredMethod("disconnect", BluetoothDevice::class.java)
        } catch (e: NoSuchMethodException) {
            for (m in a2dp.javaClass.methods) {
                if (m.name == "disconnect" && m.parameterTypes.size == 1) {
                    disconnectMethod = m
                    break
                }
            }
        }
        if (disconnectMethod != null) {
            disconnectMethod.isAccessible = true
            disconnectMethod.invoke(a2dp, targetDevice)
        }
    }

    private var remoteControlClient: android.media.RemoteControlClient? = null
    private var mediaButtonReceiver: android.content.ComponentName? = null

    fun initRemoteControlClient() {
        if (remoteControlClient == null) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return

            mediaButtonReceiver = android.content.ComponentName(
                context.packageName,
                "${context.packageName}.MainActivity\$MediaBtnReceiver"
            )
            audioManager.registerMediaButtonEventReceiver(mediaButtonReceiver)

            val mediaButtonIntent = android.content.Intent(android.content.Intent.ACTION_MEDIA_BUTTON)
            mediaButtonIntent.component = mediaButtonReceiver

            var pendingIntentFlags = 0
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                pendingIntentFlags = android.app.PendingIntent.FLAG_IMMUTABLE
            }
            val mediaPendingIntent = android.app.PendingIntent.getBroadcast(
                context, 0, mediaButtonIntent, pendingIntentFlags
            )

            val rcc = android.media.RemoteControlClient(mediaPendingIntent)
            val flags = android.media.RemoteControlClient.FLAG_KEY_MEDIA_PLAY or
                    android.media.RemoteControlClient.FLAG_KEY_MEDIA_PAUSE or
                    android.media.RemoteControlClient.FLAG_KEY_MEDIA_PLAY_PAUSE or
                    android.media.RemoteControlClient.FLAG_KEY_MEDIA_NEXT or
                    android.media.RemoteControlClient.FLAG_KEY_MEDIA_PREVIOUS
            rcc.setTransportControlFlags(flags)

            audioManager.registerRemoteControlClient(rcc)
            remoteControlClient = rcc
        }
    }

    fun updateMetadata(title: String?, artist: String?, album: String?, albumArtBmp: android.graphics.Bitmap?) {
        val rcc = remoteControlClient ?: return
        val editor = rcc.editMetadata(true)
        editor.putString(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE, title ?: "Unknown Title")
        editor.putString(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST, artist ?: "Unknown Artist")
        editor.putString(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM, album ?: "Unknown Album")
        if (albumArtBmp != null) {
            editor.putBitmap(android.media.RemoteControlClient.MetadataEditor.BITMAP_KEY_ARTWORK, albumArtBmp)
        }
        editor.apply()
    }

    fun updatePlaybackState(isPlaying: Boolean) {
        val rcc = remoteControlClient ?: return
        val state = if (isPlaying) {
            android.media.RemoteControlClient.PLAYSTATE_PLAYING
        } else {
            android.media.RemoteControlClient.PLAYSTATE_PAUSED
        }
        rcc.setPlaybackState(state)
    }

    companion object {
        @Volatile
        private var instance: BluetoothAvrcpManager? = null

        @JvmStatic
        fun getInstance(context: Context): BluetoothAvrcpManager {
            return instance ?: synchronized(this) {
                instance ?: BluetoothAvrcpManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
