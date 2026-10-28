package com.yarisdoctor

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The car's Bluetooth connecting wakes the logger, also on the home Wi-Fi; any other device, or none picked, does nothing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CarBtReceiverTest {
    private lateinit var app: Application
    private val car = "00:11:22:33:44:55"

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(BLUETOOTH_CONNECT)
        Prefs.put(app, Prefs.CAR_BT, car)
    }

    @After fun tearDown() {
        Sync.ssid = null
        shadowOf(app).clearStartedServices()
    }

    private fun send(action: String, address: String = car) {
        val device = app.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address)
        CarBtReceiver().onReceive(app, Intent(action).putExtra(BluetoothDevice.EXTRA_DEVICE, device))
    }

    @Test fun carConnectingStartsTheLoggerEvenAtHome() {
        Prefs.put(app, Prefs.WIFI, "Home")
        Sync.ssid = "Home"
        send(BluetoothDevice.ACTION_ACL_CONNECTED)
        assertEquals(DpfService::class.java.name, shadowOf(app).nextStartedService?.component?.className)
        assertTrue(Prefs.carBtOn(app))
    }

    @Test fun carDisconnectingClearsTheFlagAndStartsNothing() {
        Prefs.put(app, Prefs.CAR_BT_ON, true)
        send(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        assertFalse(Prefs.carBtOn(app))
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun anotherDeviceIsIgnored() {
        send(BluetoothDevice.ACTION_ACL_CONNECTED, "AA:BB:CC:DD:EE:FF")
        assertNull(shadowOf(app).nextStartedService)
        assertFalse(Prefs.carBtOn(app))
    }

    @Test fun nothingPickedIsIgnored() {
        Prefs.put(app, Prefs.CAR_BT, null as String?)
        send(BluetoothDevice.ACTION_ACL_CONNECTED)
        assertNull(shadowOf(app).nextStartedService)
    }
}
