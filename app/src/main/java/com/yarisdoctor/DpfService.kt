package com.yarisdoctor

import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.util.UUID
import java.util.concurrent.Semaphore

/**
 * Always-on foreground service. Idle: probes the dongle once a minute (alarm-driven so it survives CPU sleep).
 * Connected: polls the ECU like DPFViewer's loop(), logs to SQLite, tracks GPS distance and shows a regen notification.
 */
class DpfService : Service() {

    @Volatile private var running = true
    @Volatile private var odoM = 0.0 // only written on the main thread (GPS callback)
    @Volatile private var socket: BluetoothSocket? = null
    private var lastFix: Location? = null
    private val db by lazy { Db.get(this) }
    private val nm by lazy { getSystemService(NotificationManager::class.java) }
    private val alarms by lazy { getSystemService(AlarmManager::class.java) }
    private val wake by lazy {
        getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yaris:log").apply { setReferenceCounted(false) }
    }
    private val idle = Semaphore(0)
    private val probe = AlarmManager.OnAlarmListener { wake.acquire(30_000); idle.release() }
    private val worker = Thread(::run, "dpf")

    private val gps = LocationListener { l ->
        if (l.accuracy <= 25f) {
            val prev = lastFix
            // Speed gate drops GPS drift while parked
            if (prev != null && l.speed > 1f) odoM += prev.distanceTo(l)
            lastFix = l
        }
    }

    override fun onCreate() {
        super.onCreate()
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Background logger", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(CH_REGEN, "DPF regeneration", NotificationManager.IMPORTANCE_HIGH))
        val types = FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            (if (checkSelfPermission(ACCESS_FINE_LOCATION) == PERMISSION_GRANTED) FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        try {
            startForeground(ID_SERVICE, status("Waiting for dongle"), types)
        } catch (e: SecurityException) {
            // Location type refused (e.g. started at boot without "Allow all the time"): log without GPS
            startForeground(ID_SERVICE, status("Waiting for dongle"), FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        }
        odoM = db.odometer()
        worker.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        running = false
        alarms.cancel(probe)
        try { socket?.close() } catch (e: Exception) {}
        idle.release()
        worker.interrupt()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun run() {
        while (running) {
            try {
                session()
            } catch (e: Exception) {
                // Normal when the dongle is off or out of range: connect() throws IOException
                Log.d(TAG, "session ended: $e")
            } finally {
                if (wake.isHeld) wake.release()
            }
            if (!running) break
            nm.notify(ID_SERVICE, status("Waiting for dongle"))
            alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + PROBE_MS, "yaris:probe", probe, null)
            try { idle.acquire() } catch (e: InterruptedException) { break }
        }
    }

    private fun session() {
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        if (adapter == null || !adapter.isEnabled) return
        val s = adapter.getRemoteDevice(DONGLE_MAC).createRfcommSocketToServiceRecord(SPP)
        socket = s
        s.use {
            s.connect()
            wake.acquire()
            val elm = Elm(s.inputStream, s.outputStream)
            elm.init()
            startGps()
            nm.notify(ID_SERVICE, status("Logging"))
            var regenId = -1L
            var regenStart = 0L
            var fails = 0
            var seen = false
            try {
                // Ignition off (dongle may stay powered on the OBD port): give up after 1 dead cycle on a probe, 3 mid-drive
                while (running && fails < (if (seen) 3 else 1)) {
                    val soot = elm.send("21 DD")?.let(::sootOf)
                    val temp = elm.send("21 F1")?.let(::tempOf)
                    val regen = elm.send("21 DF")?.let(::regenOf)
                    if (soot == null && temp == null && regen == null) {
                        fails++
                        Thread.sleep(2000)
                        continue
                    }
                    fails = 0
                    seen = true
                    val now = System.currentTimeMillis()
                    val isRegen = regen ?: (regenId >= 0) // keep last state on a failed read, like DPFViewer
                    db.sample(now, soot, temp, isRegen, odoM)
                    if (isRegen && regenId < 0) {
                        regenId = db.regenStart(now, odoM, soot)
                        regenStart = now
                    } else if (!isRegen && regenId >= 0) {
                        db.regenEnd(regenId, now, odoM, soot)
                        regenId = -1
                        nm.cancel(ID_REGEN)
                    }
                    if (isRegen) nm.notify(ID_REGEN, regenNotification(regenStart, soot, temp))
                    Thread.sleep(if (isRegen) 1000 else 5000)
                }
            } finally {
                // Engine off or link lost mid-regen: close the record at the last moment we saw it
                if (regenId >= 0) db.regenEnd(regenId, System.currentTimeMillis(), odoM, null)
                nm.cancel(ID_REGEN)
                stopGps()
            }
        }
    }

    private fun startGps() {
        if (checkSelfPermission(ACCESS_FINE_LOCATION) != PERMISSION_GRANTED) return
        getSystemService(LocationManager::class.java)
            .requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, gps, Looper.getMainLooper())
    }

    private fun stopGps() {
        getSystemService(LocationManager::class.java).removeUpdates(gps)
        lastFix = null
    }

    private fun openApp() =
        PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    private fun status(text: String): Notification =
        Notification.Builder(this, CH_SERVICE)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Yaris Doctor")
            .setContentText(text)
            .setContentIntent(openApp())
            .setOngoing(true)
            .build()

    private fun regenNotification(start: Long, soot: Int?, temp: Int?): Notification =
        Notification.Builder(this, CH_REGEN)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("DPF regenerating - keep driving")
            .setContentText("DPF ${temp ?: "?"}°C · soot ${soot ?: "?"}%")
            .setWhen(start)
            .setUsesChronometer(true)
            .setShowWhen(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(openApp())
            .build()

    companion object {
        const val DONGLE_MAC = "66:1E:32:7A:2E:72" // same dongle as DPFViewer's address[]
        private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val PROBE_MS = 60_000L
        private const val CH_SERVICE = "service"
        private const val CH_REGEN = "regen"
        private const val ID_SERVICE = 1
        private const val ID_REGEN = 2
        private const val TAG = "DpfService"
    }
}
