package com.yarisdoctor

import android.app.ForegroundServiceStartNotAllowedException
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * The car's own Bluetooth (head unit, hands-free, picked in Settings) connected: the ignition is on, so wake the logger
 * now, even while it is paused on the home Wi-Fi. That logs a drive from the driveway instead of from about a minute
 * after the Wi-Fi drops. [Prefs.CAR_BT_ON] keeps the service from pausing at home again until the car disconnects.
 */
class CarBtReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val car = Prefs.carBt(c) ?: return
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        if (device?.address != car) return
        val on = intent.action == BluetoothDevice.ACTION_ACL_CONNECTED
        if (!on && intent.action != BluetoothDevice.ACTION_ACL_DISCONNECTED) return
        Prefs.put(c, Prefs.CAR_BT_ON, on)
        if (!on) return
        try {
            DpfService.start(c)
            DpfService.probe(c) // a service already running stops waiting for its alarm and probes at once
        } catch (e: ForegroundServiceStartNotAllowedException) {
            Log.d(TAG, "start refused: $e") // the next boot, alarm or app open covers it
        } catch (e: SecurityException) {
            Log.d(TAG, "start refused: $e")
        }
    }

    companion object {
        private const val TAG = "CarBt"
    }
}
