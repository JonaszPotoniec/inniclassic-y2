package com.themoon.y1.managers

import java.util.Locale

/**
 * Pure rules for Bluetooth pairing without a touch screen: what to answer for each pairing
 * variant, which PIN to try, and what to tell the user when a bond fails.
 */
object BluetoothPairingPolicy {

    // BluetoothDevice.PAIRING_VARIANT_* (some are hidden on API 19).
    const val VARIANT_PIN = 0
    const val VARIANT_PASSKEY = 1
    const val VARIANT_PASSKEY_CONFIRMATION = 2
    const val VARIANT_CONSENT = 3
    const val VARIANT_DISPLAY_PASSKEY = 4
    const val VARIANT_DISPLAY_PIN = 5
    const val VARIANT_OOB_CONSENT = 6

    // BluetoothDevice.UNBOND_REASON_* (hidden).
    const val REASON_AUTH_FAILED = 1
    const val REASON_AUTH_REJECTED = 2
    const val REASON_AUTH_CANCELED = 3
    const val REASON_REMOTE_DEVICE_DOWN = 4
    const val REASON_DISCOVERY_IN_PROGRESS = 5
    const val REASON_AUTH_TIMEOUT = 6
    const val REASON_REPEATED_ATTEMPTS = 7
    const val REASON_REMOTE_AUTH_CANCELED = 8
    const val REASON_REMOVED = 9

    /** Fixed PINs of legacy (Bluetooth 2.0) headsets, in the order we try them. */
    val PINS = listOf("0000", "1234")

    enum class Action {
        /** Legacy pairing: send a fixed PIN. */
        SEND_PIN,
        /** Secure Simple Pairing without a code: accept (Just Works, OOB). */
        CONFIRM,
        /**
         * Show the code and accept: numeric comparison (the other device shows the same code and
         * asks its user to compare), or the other device types the passkey that we show.
         */
        SHOW_CODE_AND_CONFIRM,
        /** The other device types the PIN that we show; we send the same PIN. */
        SHOW_CODE_AND_SEND_PIN,
        /** We must type a code shown on the other device: not possible on the Y2. */
        CANCEL
    }

    fun actionFor(variant: Int): Action = when (variant) {
        VARIANT_PIN -> Action.SEND_PIN
        VARIANT_CONSENT, VARIANT_OOB_CONSENT -> Action.CONFIRM
        VARIANT_PASSKEY_CONFIRMATION, VARIANT_DISPLAY_PASSKEY -> Action.SHOW_CODE_AND_CONFIRM
        VARIANT_DISPLAY_PIN -> Action.SHOW_CODE_AND_SEND_PIN
        else -> Action.CANCEL
    }

    /** Text above the code: compare it (numeric comparison) or type it (display variants). */
    fun codePrompt(variant: Int): String =
        if (variant == VARIANT_PASSKEY_CONFIRMATION) "Make sure that this code shows on"
        else "Type this code on"

    /**
     * How long other Bluetooth work (a device scan) must wait after a pairing or connection event.
     * A scan during pairing, or while a car connects its profiles, makes the attempt fail.
     */
    const val QUIET_PAIRING_MS = 40_000L
    const val QUIET_AFTER_BOND_MS = 15_000L

    fun pinForAttempt(attempt: Int): String? = PINS.getOrNull(attempt)

    /** Same format as the system pairing dialog: PIN with 4 digits, passkey with 6. */
    fun formatCode(variant: Int, key: Int): String? {
        if (key < 0) return null
        return if (variant == VARIANT_DISPLAY_PIN) String.format(Locale.US, "%04d", key)
        else String.format(Locale.US, "%06d", key)
    }

    sealed class FailureOutcome {
        /** Try to pair again; the next PIN request uses [pinIndex]. */
        data class Retry(val pinIndex: Int) : FailureOutcome()
        /** Stop; show [message] (null: show nothing, e.g. the user deleted the device). */
        data class Report(val message: String?) : FailureOutcome()
    }

    /**
     * Decide what to do when a bond attempt ends in BOND_NONE.
     * [sentPinIndex] is the index in [PINS] that we sent in this attempt, or null if we sent no PIN.
     */
    fun onBondFailed(reason: Int, sentPinIndex: Int?): FailureOutcome {
        if (reason == REASON_AUTH_FAILED && sentPinIndex != null && pinForAttempt(sentPinIndex + 1) != null) {
            return FailureOutcome.Retry(sentPinIndex + 1)
        }
        return FailureOutcome.Report(failureMessage(reason, sentPinIndex))
    }

    fun failureMessage(reason: Int, sentPinIndex: Int? = null): String? = when (reason) {
        REASON_AUTH_FAILED ->
            if (sentPinIndex != null) "Pairing failed: the device did not accept the PIN"
            else "Pairing failed: wrong PIN or passkey"
        REASON_AUTH_REJECTED -> "Pairing failed: the device rejected it"
        REASON_AUTH_CANCELED -> "Pairing cancelled"
        REASON_REMOTE_DEVICE_DOWN -> "Pairing failed: device not found. Put it in pairing mode"
        REASON_DISCOVERY_IN_PROGRESS -> "Pairing failed: a scan was running. Try again"
        REASON_AUTH_TIMEOUT -> "Pairing failed: no answer (timeout)"
        REASON_REPEATED_ATTEMPTS -> "Pairing failed: too many attempts. Wait, then try again"
        REASON_REMOTE_AUTH_CANCELED -> "Pairing cancelled by the device"
        REASON_REMOVED -> null
        else -> "Pairing failed"
    }
}
