package com.yarisdoctor

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Plugging the phone into the car wakes the logger at once (the first minutes of a drive were the documented gap), but
 * never on the home Wi-Fi with nothing to upload, and never when logging is off or Bluetooth isn't allowed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CarPowerReceiverTest {
    private lateinit var app: Application

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(BLUETOOTH_CONNECT)
        Sync.ssid = null // not on Wi-Fi unless a test says so
    }

    @After fun tearDown() {
        Sync.ssid = null
        shadowOf(app).clearStartedServices()
    }

    private fun plugIn() = CarPowerReceiver().onReceive(app, Intent(Intent.ACTION_POWER_CONNECTED))

    @Test fun powerConnectedStartsTheLogger() {
        plugIn()
        assertEquals(DpfService::class.java.name, shadowOf(app).nextStartedService?.component?.className)
    }

    @Test fun loggingOffStartsNothing() {
        Prefs.put(app, Prefs.LOGGING, false)
        plugIn()
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun missingBluetoothPermissionStartsNothing() {
        shadowOf(app).denyPermissions(BLUETOOTH_CONNECT)
        plugIn()
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun atHomeWithNothingPendingStartsNothing() {
        Prefs.put(app, Prefs.WIFI, "Home")
        Sync.ssid = "Home" // no hub set, so nothing is pending: the service is paused at home and stays paused
        plugIn()
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun anotherActionIsIgnored() {
        CarPowerReceiver().onReceive(app, Intent(Intent.ACTION_POWER_DISCONNECTED))
        assertNull(shadowOf(app).nextStartedService)
    }
}
