package com.yarisdoctor

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * While the service is paused on the home Wi-Fi (so no "Waiting for dongle" notification at home): an alarm every
 * [CHECK_MS] checks the Wi-Fi name and starts the service again once the phone is off that network. Starting a
 * foreground service from the background is allowed because the app is exempt from battery optimisation, which the
 * service checks before it pauses.
 */
class HomeWatch : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val done = goAsync()
        Thread({
            try {
                when {
                    !Prefs.logging(c) -> {} // logging turned off meanwhile: stay stopped
                    onHomeWifi(c) -> schedule(c)
                    else -> try {
                        DpfService.start(c) // its onCreate cancels this alarm
                    } catch (e: IllegalStateException) {
                        // ForegroundServiceStartNotAllowedException: try again next time rather than stop logging for good
                        Log.d(TAG, "restart refused: $e")
                        schedule(c)
                    }
                }
            } finally {
                done.finish()
            }
        }, "home-watch").start()
    }

    companion object {
        private const val CHECK_MS = 60_000L // the dongle probe's pace: a drive starting off the home Wi-Fi loses at most a minute
        private const val TAG = "HomeWatch"

        private fun intent(c: Context) =
            PendingIntent.getBroadcast(c, 0, Intent(c, HomeWatch::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        fun schedule(c: Context) = c.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + CHECK_MS, intent(c))

        fun cancel(c: Context) = c.getSystemService(AlarmManager::class.java).cancel(intent(c))

        /**
         * Whether the phone is on the Wi-Fi named in Settings right now. The service isn't running to keep [Sync.ssid], so
         * this asks once: a callback reports the current Wi-Fi straight away, or not at all when there is none.
         * Blocks up to a second; call it off the main thread.
         */
        fun onHomeWifi(c: Context): Boolean {
            val home = Prefs.wifi(c)
            if (home.isBlank()) return false
            val cm = c.getSystemService(ConnectivityManager::class.java)
            var ssid: String? = null
            val seen = CountDownLatch(1)
            val callback = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    ssid = (caps.transportInfo as? WifiInfo)?.ssid?.removeSurrounding("\"")
                    seen.countDown()
                }
            }
            cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback)
            try {
                seen.await(1, TimeUnit.SECONDS)
            } finally {
                cm.unregisterNetworkCallback(callback)
            }
            return ssid == home
        }
    }
}
