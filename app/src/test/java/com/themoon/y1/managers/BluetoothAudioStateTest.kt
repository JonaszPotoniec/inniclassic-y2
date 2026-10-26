package com.themoon.y1.managers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothAudioStateTest {
    private val connected = BluetoothAudioState.STATE_CONNECTED
    private val disconnected = 0
    private val connecting = 1
    private val disconnecting = 3

    @Test
    fun startsWithTheRuneOnly() {
        assertFalse(BluetoothAudioState().isConnected)
    }

    @Test
    fun connectedDeviceAddsTheDotsAndDisconnectRemovesThem() {
        val s = BluetoothAudioState()
        assertFalse(s.onAdapterState(true))
        assertFalse(s.onA2dpState("AA", connecting))
        assertFalse(s.isConnected)
        assertTrue(s.onA2dpState("AA", connected))
        assertTrue(s.isConnected)
        // The same broadcast again: no change, no new drawing.
        assertFalse(s.onA2dpState("AA", connected))
        assertTrue(s.onA2dpState("AA", disconnecting))
        assertFalse(s.isConnected)
        assertFalse(s.onA2dpState("AA", disconnected))
    }

    @Test
    fun staysConnectedUntilTheLastDeviceDisconnects() {
        val s = BluetoothAudioState()
        s.onAdapterState(true)
        s.onA2dpState("AA", connected)
        assertFalse(s.onA2dpState("BB", connected))
        assertFalse(s.onA2dpState("AA", disconnected))
        assertTrue(s.isConnected)
        assertTrue(s.onA2dpState("BB", disconnected))
        assertFalse(s.isConnected)
    }

    @Test
    fun adapterOffForgetsTheDevices() {
        val s = BluetoothAudioState()
        s.onAdapterState(true)
        s.onA2dpState("AA", connected)
        assertTrue(s.onAdapterState(false))
        assertFalse(s.isConnected)
        // On again: no dots until the A2DP query or a broadcast says a device is connected.
        assertFalse(s.onAdapterState(true))
        assertFalse(s.isConnected)
        assertTrue(s.onConnectedDevices(listOf("AA")))
        assertTrue(s.isConnected)
    }

    @Test
    fun queryAtStartReplacesTheList() {
        val s = BluetoothAudioState()
        assertFalse(s.onConnectedDevices(emptyList()))
        assertTrue(s.onConnectedDevices(listOf("AA", "BB")))
        assertTrue(s.adapterOn)
        assertFalse(s.onConnectedDevices(listOf("BB")))
        assertTrue(s.onConnectedDevices(emptyList()))
        assertFalse(s.isConnected)
    }

    @Test
    fun broadcastWithoutADeviceStillCounts() {
        val s = BluetoothAudioState()
        assertTrue(s.onA2dpState(null, connected))
        assertTrue(s.onA2dpState(null, disconnected))
    }

    @Test
    fun listTicksEachConnectedDeviceAlsoWithoutTheListProxy() {
        val s = BluetoothAudioState()
        // After an app restart the list has no A2DP proxy (null): the query at start gives the devices.
        assertFalse(s.listShowsConnected("AA", null))
        s.onConnectedDevices(listOf("AA", "BB"))
        assertTrue(s.listShowsConnected("AA", null))
        assertTrue(s.listShowsConnected("BB", disconnected))
        assertFalse(s.listShowsConnected("CC", null))
        assertFalse(s.listShowsConnected(null, null))
        // The proxy of the list can also say connected.
        assertTrue(s.listShowsConnected("CC", connected))
        // Disconnect broadcast: no tick.
        s.onA2dpState("AA", disconnected)
        assertFalse(s.listShowsConnected("AA", null))
        // Bluetooth off: no ticks.
        s.onAdapterState(false)
        assertFalse(s.listShowsConnected("BB", null))
        assertTrue(s.connectedAddresses.isEmpty())
    }
}
