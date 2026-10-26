package com.yarisdoctor

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.app.ForegroundServiceStartNotAllowedException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.util.Log

/**
 * The phone is plugged into the car: wake the logger now instead of waiting for the idle alarm's next minute, so the
 * first minutes of a drive are logged. People plug the phone in when they get in, and ACTION_POWER_CONNECTED is an
 * exempt broadcast, so this may start a foreground service from the background.
 */
class CarPowerReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_POWER_CONNECTED) return
        if (!Prefs.logging(c) || c.checkSelfPermission(BLUETOOTH_CONNECT) != PERMISSION_GRANTED) return
        // Home Wi-Fi with nothing to upload: stay paused, so no "Waiting for dongle" notification comes back at home
        if (Sync.atHome(c) && !Prefs.debug && !Sync.pending(c)) return
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
        private const val TAG = "CarPower"
    }
}
