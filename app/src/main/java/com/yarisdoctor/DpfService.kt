package com.yarisdoctor

import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.Manifest.permission.BLUETOOTH_CONNECT
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.annotation.TargetApi
import java.util.UUID
import java.util.concurrent.Semaphore

/**
 * Always-on foreground service. Idle: probes the dongle once a minute (alarm-driven so it survives CPU sleep).
 * Connected: polls the ECU like DPFViewer's loop(), logs to SQLite, tracks GPS distance and shows a regen notification.
 */
class DpfService : Service() {

    @Volatile private var running = true
    @Volatile private var odoM = 0.0 // written by the GPS callback (main thread), or by simulate() when GPS is off
    @Volatile private var socket: BluetoothSocket? = null
    private var lastFix: Location? = null
    private val db by lazy { Db.get(this) }
    private val nm by lazy { getSystemService(NotificationManager::class.java) }
    private val alarms by lazy { getSystemService(AlarmManager::class.java) }
    private val wake by lazy {
        getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yaris:log").apply { setReferenceCounted(false) }
    }
    private val edge by lazy { EdgeLight(this) }
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
        Sync.watch(this)
        worker.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        running = false
        Sync.unwatch(this)
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
            // On the home Wi-Fi after a drive: push new data to the hub (no-op elsewhere, when unset, or nothing new)
            try { Sync.maybe(this) } catch (e: Exception) { Log.d(TAG, "sync check failed: $e") }
            nm.notify(ID_SERVICE, status("Waiting for dongle"))
            alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + PROBE_MS, "yaris:probe", probe, null)
            try { idle.acquire() } catch (e: InterruptedException) { break }
        }
    }

    private fun session() {
        if (Prefs.debug) return simulate()
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        if (adapter == null || !adapter.isEnabled) return
        val s = adapter.getRemoteDevice(Prefs.dongle(this)).createRfcommSocketToServiceRecord(SPP)
        socket = s
        s.use {
            s.connect()
            wake.acquire()
            val elm = Elm(s.inputStream, s.outputStream)
            elm.init()
            startGps()
            nm.notify(ID_SERVICE, status("Logging"))
            val rec = Recorder(db)
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
                    publish(rec, soot, temp, regen)
                    Thread.sleep(if (rec.inRegen) 1000 else 5000)
                }
            } finally {
                finish(rec)
                stopGps()
            }
        }
    }

    /** Debug mode: random readings into the in-memory Db (see Db.get) until the service is stopped. */
    private fun simulate() {
        nm.notify(ID_SERVICE, status("Debug mode: simulated dongle"))
        val sim = Sim.live()
        val rec = Recorder(db)
        try {
            while (running) {
                val r = sim.next()
                odoM += sim.metresPerTick
                publish(rec, r.soot, r.temp, r.regen)
                Thread.sleep(1000)
            }
        } finally {
            finish(rec)
        }
    }

    private fun publish(rec: Recorder, soot: Int?, temp: Int?, regen: Boolean?) {
        rec.record(System.currentTimeMillis(), soot, temp, regen, odoM)
        if (rec.inRegen && Prefs.alerts(this)) nm.notify(ID_REGEN, regenNotification(rec.regenStart, rec.regenSootStart, soot, temp))
        else nm.cancel(ID_REGEN)
        edge.show(rec.inRegen && Prefs.edge(this))
    }

    private fun finish(rec: Recorder) {
        rec.close(System.currentTimeMillis(), odoM)
        nm.cancel(ID_REGEN)
        edge.show(false)
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
            .setSmallIcon(R.drawable.ic_stat_gauge)
            .setColor(getColor(R.color.primary))
            .setContentTitle("Yaris Doctor")
            .setContentText(text)
            .setContentIntent(openApp())
            .setOngoing(true)
            .build()

    private fun regenNotification(start: Long, sootStart: Int?, soot: Int?, temp: Int?): Notification =
        Notification.Builder(this, CH_REGEN)
            .setSmallIcon(R.drawable.ic_stat_gauge)
            .setColor(getColor(R.color.primary))
            .setContentTitle("DPF regenerating · keep driving")
            .setContentText("DPF ${temp ?: "?"}°C · soot ${soot ?: "?"}%")
            .setWhen(start)
            .setUsesChronometer(true)
            .setShowWhen(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(openApp())
            .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) liveUpdate(this, sootStart, soot) }
            .build()

    /**
     * Android 16 Live Update (standard Android, any brand): Samsung's One UI 8 shows it in the Now Bar, Pixels and
     * others as a status-bar chip; below Android 16 this is skipped and the plain notification is unchanged. Rules the
     * system checks: ongoing, has a title, non-MIN channel, not colorized, no custom views (developer.android.com/
     * develop/ui/views/notifications/live-update). The user can still turn Live Updates off for the app.
     */
    @TargetApi(Build.VERSION_CODES.BAKLAVA)
    private fun liveUpdate(b: Notification.Builder, sootStart: Int?, soot: Int?) {
        val burned = burnPercent(sootStart, soot)
        // setRequestPromotedOngoing() only exists from Android 16 QPR (API 36.1); the extra it sets works on every API 36 build
        b.addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
            .setStyle(Notification.ProgressStyle()
                .setProgressSegments(listOf(Notification.ProgressStyle.Segment(100).setColor(getColor(R.color.primary))))
                .apply { if (burned == null) setProgressIndeterminate(true) else setProgress(burned) })
        if (soot != null) b.setShortCriticalText("$soot%") // chip: icon + up to ~7 chars
    }

    companion object {
        private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val PROBE_MS = 60_000L
        private const val CH_SERVICE = "service"
        private const val CH_REGEN = "regen"
        private const val ID_SERVICE = 1
        private const val ID_REGEN = 2
        private const val TAG = "DpfService"
        private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing" // = Notification/NotificationCompat constant

        /** Starts logging if it is enabled and allowed; a no-op when already running. */
        fun start(c: Context) {
            if (Prefs.logging(c) && c.checkSelfPermission(BLUETOOTH_CONNECT) == PERMISSION_GRANTED) {
                c.startForegroundService(Intent(c, DpfService::class.java))
            }
        }

        /** Applies changed settings (dongle, logging, Debug mode): a new instance re-reads them. */
        fun restart(c: Context) {
            c.stopService(Intent(c, DpfService::class.java))
            start(c)
        }
    }
}
