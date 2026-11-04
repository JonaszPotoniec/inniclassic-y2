package com.themoon.y1.managers

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Makes the Y2 discoverable for a limited time, so that a car can find it in its own list.
 *
 * Uses the hidden BluetoothAdapter.setScanMode by reflection: in the Y2 Bluetooth.apk it needs only the
 * BLUETOOTH permission (ACTION_REQUEST_DISCOVERABLE opens a system dialog that needs a touch screen).
 * The timer is ours: the stack does not always end discoverable mode by itself (Settings uses an alarm too).
 *
 * It also gives the adapter the name [BluetoothDiscoverableLogic.DEVICE_NAME] at app start and when Bluetooth
 * turns on.
 *
 * The Y2 is discoverable ONLY after a press on the Discoverable row. If it becomes discoverable in another way
 * (for example: the Y2 Bluetooth.apk starts in discoverable mode with no time limit when the stored discoverable
 * timeout is 0, see AdapterProperties.onBluetoothReady), this class sets it back to connectable at once.
 */
object BluetoothDiscoverableManager {
    private const val TAG = "BtDiscoverable"

    private val handler = Handler(Looper.getMainLooper())
    private var endAtMs = 0L
    private var registered = false

    /** Called on the main thread when the scan mode, the Bluetooth state or the timer changes. */
    var listener: (() -> Unit)? = null

    private val turnOff = Runnable {
        Log.w(TAG, "Discoverable time ended")
        setDiscoverable(false)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Read the current mode, not the extra: an old broadcast must not stop a new timer.
            enforce()
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED &&
                intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) == BluetoothAdapter.STATE_ON
            ) setDeviceName()
            listener?.invoke()
        }
    }

    /** Call once at app start. */
    fun init(context: Context) {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        context.applicationContext.registerReceiver(receiver, filter)
        registered = true
        enforce()
        setDeviceName()
    }

    /** Gives the adapter the name [BluetoothDiscoverableLogic.DEVICE_NAME] if it has another name and is on. */
    @SuppressLint("MissingPermission")
    private fun setDeviceName() {
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
            if (adapter.state != BluetoothAdapter.STATE_ON) return
            val current = adapter.name
            val name = BluetoothDiscoverableLogic.nameToSet(current) ?: return
            Log.w(TAG, "Bluetooth name \"$current\" -> \"$name\": ${adapter.setName(name)}")
        } catch (e: Exception) {
            Log.e(TAG, "Cannot set the Bluetooth name", e)
        }
    }

    /** Stops our timer when the Y2 is not discoverable, and stops discoverable mode that our timer did not start. */
    private fun enforce() {
        val discoverable = isDiscoverable()
        if (!discoverable) {
            cancelTimer()
        } else if (BluetoothDiscoverableLogic.mustTurnOff(discoverable, endAtMs > 0L)) {
            Log.w(TAG, "Discoverable without a press on the row: turning it off")
            setDiscoverable(false)
        }
    }

    @SuppressLint("MissingPermission")
    fun isDiscoverable(): Boolean = try {
        BluetoothAdapter.getDefaultAdapter()?.scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE
    } catch (e: Exception) {
        false
    }

    /** Time left of our timer, or null if no timer runs. */
    fun remainingMs(): Long? = BluetoothDiscoverableLogic.remainingMs(endAtMs, SystemClock.elapsedRealtime())

    /** Turns discoverable mode on (for [BluetoothDiscoverableLogic.DURATION_S]) or off. Returns false on failure. */
    fun setDiscoverable(on: Boolean): Boolean {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        val durationS = BluetoothDiscoverableLogic.DURATION_S
        cancelTimer()
        // Start the timer before the call: the SCAN_MODE_CHANGED broadcast must see it.
        if (on) endAtMs = SystemClock.elapsedRealtime() + durationS * 1000L
        val mode = if (on) BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE else BluetoothAdapter.SCAN_MODE_CONNECTABLE
        val ok = try {
            // Always give a non-zero timeout: the stack stores it, and with 0 it starts discoverable at the next power on.
            adapter.javaClass.getMethod("setScanMode", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(adapter, mode, durationS) as? Boolean ?: false
        } catch (e: Exception) {
            Log.e(TAG, "setScanMode failed (on=$on)", e.cause ?: e)
            false
        }
        Log.w(TAG, "setScanMode(on=$on, ${durationS}s): $ok")
        if (on && ok) handler.postDelayed(turnOff, durationS * 1000L) else cancelTimer()
        listener?.invoke()
        return ok
    }

    private fun cancelTimer() {
        handler.removeCallbacks(turnOff)
        endAtMs = 0L
    }
}
