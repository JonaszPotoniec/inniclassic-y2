package com.themoon.y1.managers

import com.themoon.y1.managers.BluetoothPairingPolicy.Action
import com.themoon.y1.managers.BluetoothPairingPolicy.FailureOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothPairingPolicyTest {

    @Test
    fun variantConstantsMatchAndroid() {
        // Values of BluetoothDevice.PAIRING_VARIANT_* in Android 4.x.
        assertEquals(0, BluetoothPairingPolicy.VARIANT_PIN)
        assertEquals(1, BluetoothPairingPolicy.VARIANT_PASSKEY)
        assertEquals(2, BluetoothPairingPolicy.VARIANT_PASSKEY_CONFIRMATION)
        assertEquals(3, BluetoothPairingPolicy.VARIANT_CONSENT)
        assertEquals(4, BluetoothPairingPolicy.VARIANT_DISPLAY_PASSKEY)
        assertEquals(5, BluetoothPairingPolicy.VARIANT_DISPLAY_PIN)
        assertEquals(6, BluetoothPairingPolicy.VARIANT_OOB_CONSENT)
    }

    @Test
    fun eachVariantGetsTheCorrectAction() {
        assertEquals(Action.SEND_PIN, BluetoothPairingPolicy.actionFor(0))
        assertEquals(Action.CANCEL, BluetoothPairingPolicy.actionFor(1))
        assertEquals(Action.SHOW_CODE_AND_CONFIRM, BluetoothPairingPolicy.actionFor(2))
        assertEquals(Action.CONFIRM, BluetoothPairingPolicy.actionFor(3))
        assertEquals(Action.SHOW_CODE_AND_CONFIRM, BluetoothPairingPolicy.actionFor(4))
        assertEquals(Action.SHOW_CODE_AND_SEND_PIN, BluetoothPairingPolicy.actionFor(5))
        assertEquals(Action.CONFIRM, BluetoothPairingPolicy.actionFor(6))
    }

    @Test
    fun unknownVariantIsCancelled() {
        assertEquals(Action.CANCEL, BluetoothPairingPolicy.actionFor(-1))
        assertEquals(Action.CANCEL, BluetoothPairingPolicy.actionFor(42))
    }

    @Test
    fun numericComparisonAsksToCompareTheCode() {
        assertEquals("Make sure that this code shows on", BluetoothPairingPolicy.codePrompt(BluetoothPairingPolicy.VARIANT_PASSKEY_CONFIRMATION))
        assertEquals("Type this code on", BluetoothPairingPolicy.codePrompt(BluetoothPairingPolicy.VARIANT_DISPLAY_PASSKEY))
        assertEquals("Type this code on", BluetoothPairingPolicy.codePrompt(BluetoothPairingPolicy.VARIANT_DISPLAY_PIN))
    }

    @Test
    fun pinsAre0000Then1234() {
        assertEquals("0000", BluetoothPairingPolicy.pinForAttempt(0))
        assertEquals("1234", BluetoothPairingPolicy.pinForAttempt(1))
        assertNull(BluetoothPairingPolicy.pinForAttempt(2))
    }

    @Test
    fun codesAreFormattedLikeTheSystemDialog() {
        assertEquals("001234", BluetoothPairingPolicy.formatCode(BluetoothPairingPolicy.VARIANT_DISPLAY_PASSKEY, 1234))
        assertEquals("123456", BluetoothPairingPolicy.formatCode(BluetoothPairingPolicy.VARIANT_PASSKEY_CONFIRMATION, 123456))
        assertEquals("0042", BluetoothPairingPolicy.formatCode(BluetoothPairingPolicy.VARIANT_DISPLAY_PIN, 42))
        assertNull(BluetoothPairingPolicy.formatCode(BluetoothPairingPolicy.VARIANT_DISPLAY_PASSKEY, -1))
    }

    @Test
    fun authFailureAfterFirstPinRetriesOnceWithSecondPin() {
        assertEquals(FailureOutcome.Retry(1), BluetoothPairingPolicy.onBondFailed(BluetoothPairingPolicy.REASON_AUTH_FAILED, 0))
    }

    @Test
    fun authFailureAfterSecondPinStops() {
        val outcome = BluetoothPairingPolicy.onBondFailed(BluetoothPairingPolicy.REASON_AUTH_FAILED, 1)
        assertTrue(outcome is FailureOutcome.Report)
        assertEquals("Pairing failed: the device did not accept the PIN", (outcome as FailureOutcome.Report).message)
    }

    @Test
    fun authFailureWithoutPinDoesNotRetry() {
        val outcome = BluetoothPairingPolicy.onBondFailed(BluetoothPairingPolicy.REASON_AUTH_FAILED, null)
        assertEquals(FailureOutcome.Report("Pairing failed: wrong PIN or passkey"), outcome)
    }

    @Test
    fun otherFailuresDoNotRetry() {
        for (reason in listOf(2, 3, 4, 5, 6, 7, 8, 99)) {
            val outcome = BluetoothPairingPolicy.onBondFailed(reason, 0)
            assertTrue("reason $reason", outcome is FailureOutcome.Report)
            assertNotNull("reason $reason", (outcome as FailureOutcome.Report).message)
        }
    }

    @Test
    fun removedDeviceShowsNoMessage() {
        assertEquals(FailureOutcome.Report(null), BluetoothPairingPolicy.onBondFailed(BluetoothPairingPolicy.REASON_REMOVED, null))
    }

    @Test
    fun unknownReasonShowsItsNumber() {
        assertEquals("Pairing failed", BluetoothPairingPolicy.failureMessage(99))
    }
}
