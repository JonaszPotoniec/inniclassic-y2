package com.themoon.y1.managers

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.themoon.y1.managers.BluetoothPairingPolicy.Action
import com.themoon.y1.managers.BluetoothPairingPolicy.FailureOutcome

/**
 * Answers Bluetooth pairing requests for the Y2, which has no touch screen.
 *
 * The stack sends ACTION_PAIRING_REQUEST as an ordered broadcast (permission BLUETOOTH_ADMIN).
 * This receiver has a higher priority than the Settings receiver, answers the request
 * (setPin / setPairingConfirmation need only BLUETOOTH_ADMIN in the Y2 Bluetooth.apk), and
 * aborts the broadcast, so the Settings pairing dialog does not open.
 *
 * While a pairing runs, and for a short time after it, [scanAllowed] is false: a device scan at
 * that time makes the pairing (or the first profile connections of a car) fail.
 */
class BluetoothPairingAgent(
    private val activity: Activity,
    private val localize: (String) -> String
) {
    private val handler = Handler(Looper.getMainLooper())
    private var registered = false
    private var codeDialog: AlertDialog? = null

    /** Next PIN index to send, per device address (set for a retry). */
    private val nextPinIndex = HashMap<String, Int>()
    /** PIN index that we sent in the current bond attempt, per device address. */
    private val sentPinIndex = HashMap<String, Int>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                    quietFor(BluetoothPairingPolicy.QUIET_PAIRING_MS)
                    stopScan()
                    onPairingRequest(intent)
                    if (isOrderedBroadcast) abortBroadcast()
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> onBondStateChanged(intent)
            }
        }
    }

    fun register() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            // Before com.android.settings.bluetooth.BluetoothPairingRequest (priority 0).
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY - 1
        }
        activity.registerReceiver(receiver, filter)
        registered = true
    }

    fun unregister() {
        if (!registered) return
        try { activity.unregisterReceiver(receiver) } catch (_: Exception) {}
        registered = false
        dismissCode()
        handler.removeCallbacksAndMessages(null)
    }

    @SuppressLint("MissingPermission")
    private fun onPairingRequest(intent: Intent) {
        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
        val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
        val key = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_KEY, -1)
        val address = device.address
        val action = BluetoothPairingPolicy.actionFor(variant)
        Log.w(TAG, "Pairing request from $address variant=$variant -> $action")

        try {
            when (action) {
                Action.SEND_PIN -> {
                    val index = nextPinIndex[address] ?: 0
                    val pin = BluetoothPairingPolicy.pinForAttempt(index) ?: BluetoothPairingPolicy.PINS[0]
                    sentPinIndex[address] = index
                    val ok = device.setPin(pin.toByteArray())
                    Log.w(TAG, "setPin($pin) for $address: $ok")
                }
                Action.CONFIRM -> {
                    val ok = device.setPairingConfirmation(true)
                    Log.w(TAG, "setPairingConfirmation(true) for $address: $ok")
                }
                Action.SHOW_CODE_AND_CONFIRM -> {
                    // Numeric comparison: the Y2 cannot compare, so it accepts at once and shows the
                    // code; the user compares it on the other device (car), which also asks to confirm.
                    showCode(device, variant, BluetoothPairingPolicy.formatCode(variant, key))
                    val ok = device.setPairingConfirmation(true)
                    Log.w(TAG, "setPairingConfirmation(true) for $address: $ok")
                }
                Action.SHOW_CODE_AND_SEND_PIN -> {
                    val code = BluetoothPairingPolicy.formatCode(variant, key)
                    showCode(device, variant, code)
                    if (code != null) {
                        val ok = device.setPin(code.toByteArray())
                        Log.w(TAG, "setPin(shown code) for $address: $ok")
                    }
                }
                Action.CANCEL -> {
                    toast(deviceName(device) + ": " + localize("asks for a code that the Y2 cannot type. Pairing stopped."))
                    val ok = device.javaClass.getMethod("cancelBondProcess").invoke(device)
                    Log.w(TAG, "cancelBondProcess for $address (variant $variant): $ok")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Pairing answer failed for $address (variant $variant)", e)
            toast(localize("Pairing error: ") + (e.cause?.message ?: e.message ?: e.javaClass.simpleName))
        }
    }

    @SuppressLint("MissingPermission")
    private fun onBondStateChanged(intent: Intent) {
        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
        val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
        val previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
        val address = device.address
        if (state == BluetoothDevice.BOND_BONDING) {
            quietFor(BluetoothPairingPolicy.QUIET_PAIRING_MS)
            stopScan()
            return
        }
        dismissCode()
        // After a bond, a car connects its profiles: no scan for some seconds. After a failure: none.
        quietFor(if (state == BluetoothDevice.BOND_BONDED) BluetoothPairingPolicy.QUIET_AFTER_BOND_MS else 0L)
        val sent = sentPinIndex.remove(address)

        if (state == BluetoothDevice.BOND_BONDED) {
            nextPinIndex.remove(address)
            if (sent != null) Log.w(TAG, "Bonded with $address using PIN ${BluetoothPairingPolicy.PINS[sent]}")
            return
        }
        if (state != BluetoothDevice.BOND_NONE || previous != BluetoothDevice.BOND_BONDING) return

        val reason = intent.getIntExtra(EXTRA_REASON, -1)
        Log.w(TAG, "Bond failed with $address: reason=$reason sentPin=$sent")
        when (val outcome = BluetoothPairingPolicy.onBondFailed(reason, sent)) {
            is FailureOutcome.Retry -> {
                nextPinIndex[address] = outcome.pinIndex
                val pin = BluetoothPairingPolicy.PINS[outcome.pinIndex]
                toast(localize("PIN not accepted. Trying ") + pin + "...")
                handler.postDelayed({
                    try {
                        // The Bluetooth screen starts a scan after each bond change; a scan makes pairing fail.
                        BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
                        device.createBond()
                    } catch (e: Exception) {
                        Log.e(TAG, "createBond retry failed for $address", e)
                        nextPinIndex.remove(address)
                    }
                }, RETRY_DELAY_MS)
            }
            is FailureOutcome.Report -> {
                nextPinIndex.remove(address)
                outcome.message?.let { toast(deviceName(device) + ": " + localize(it)) }
            }
        }
    }

    private fun showCode(device: BluetoothDevice, variant: Int, code: String?) {
        if (code == null) return
        dismissCode()
        val message = localize(BluetoothPairingPolicy.codePrompt(variant)) + " " + deviceName(device) + ":\n\n" + code
        try {
            codeDialog = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(localize("Bluetooth Pairing"))
                .setMessage(message)
                .setPositiveButton(localize("OK"), null)
                .show()
            // The bond normally ends before this and closes the dialog.
            handler.postDelayed({ dismissCode() }, CODE_DIALOG_MAX_MS)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot show pairing code dialog", e)
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun dismissCode() {
        try { codeDialog?.dismiss() } catch (_: Exception) {}
        codeDialog = null
    }

    @SuppressLint("MissingPermission")
    private fun deviceName(device: BluetoothDevice): String =
        device.name?.takeIf { it.isNotBlank() } ?: device.address

    private fun stopScan() {
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter != null && adapter.isDiscovering) adapter.cancelDiscovery()
        } catch (_: Exception) {}
    }

    private fun toast(text: String) {
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "BtPairing"
        private const val EXTRA_REASON = "android.bluetooth.device.extra.REASON"
        private const val RETRY_DELAY_MS = 1500L
        private const val CODE_DIALOG_MAX_MS = 60_000L

        @Volatile
        private var quietUntil = 0L

        private fun quietFor(ms: Long) {
            quietUntil = SystemClock.elapsedRealtime() + ms
        }

        /** False while a pairing runs and shortly after a bond: do not start a device scan then. */
        fun scanAllowed(): Boolean = SystemClock.elapsedRealtime() >= quietUntil
    }
}
