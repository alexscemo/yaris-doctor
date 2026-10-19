package com.yarisdoctor

import android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
import android.Manifest.permission.ACCESS_COARSE_LOCATION
import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.POST_NOTIFICATIONS
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var text: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        text = TextView(this).apply { setPadding(48, 96, 48, 48); textSize = 15f; setTextIsSelectable(true) }
        setContentView(ScrollView(this).apply { addView(text) })
        requestPermissions(arrayOf(BLUETOOTH_CONNECT, ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION, POST_NOTIFICATIONS), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        // Background location must be requested on its own, after fine location (Android 11+)
        if (requestCode == 1 && granted(ACCESS_FINE_LOCATION) && !granted(ACCESS_BACKGROUND_LOCATION)) {
            requestPermissions(arrayOf(ACCESS_BACKGROUND_LOCATION), 2)
            return
        }
        if (granted(BLUETOOTH_CONNECT)) startForegroundService(Intent(this, DpfService::class.java))
        if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val db = Db.get(this)
        val regens = db.regens()
        text.text = buildString {
            appendLine("Last reading: ${db.lastSample() ?: "none yet"}")
            db.milesSinceRegen()?.let { appendLine("Since last regen: %.0f mi".format(it)) }
            if (!granted(BLUETOOTH_CONNECT)) appendLine("\nBluetooth permission missing - logging is off.")
            appendLine("\nRegenerations (${regens.size})")
            regens.forEach { appendLine(it) }
        }
    }

    private fun granted(p: String) = checkSelfPermission(p) == PERMISSION_GRANTED
}
