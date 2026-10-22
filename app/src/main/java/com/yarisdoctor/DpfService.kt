package com.yarisdoctor

import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.Manifest.permission.BLUETOOTH_CONNECT
import android.annotation.TargetApi
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
import com.yarisdoctor.domain.drive.DriveEffects
import com.yarisdoctor.domain.drive.DriveSession
import com.yarisdoctor.domain.drive.Sim
import com.yarisdoctor.domain.drive.burnPercent
import com.yarisdoctor.domain.health.CRIT
import com.yarisdoctor.domain.health.Finding
import com.yarisdoctor.domain.health.tripSummary
import java.util.UUID
import java.util.concurrent.Semaphore

/**
 * Always-on foreground service. Idle: probes the dongle once a minute (alarm-driven so it survives CPU sleep).
 * Connected: hands the dongle to [DriveSession], which polls the ECU and logs to SQLite. This class keeps the Android
 * side: the Bluetooth socket, GPS distance, the wakelock, the notifications and the edge light.
 */
class DpfService : Service() {

    @Volatile private var running = true
    @Volatile private var socket: BluetoothSocket? = null
    private var lastFix: Location? = null
    private val db by lazy { container.db() }
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
    // Outlives a dongle session, so a regen and a drive's alerts survive a dropped Bluetooth link
    private lateinit var drive: DriveSession // made in onCreate: the model year and the odometer need the context

    private val gps = LocationListener { l ->
        if (l.accuracy <= 25f) {
            val prev = lastFix
            // Speed gate drops GPS drift while parked
            if (prev != null && l.speed > 1f) drive.odoM += prev.distanceTo(l)
            lastFix = l
        }
    }

    /** The drive's notifications and edge light. Called on the worker thread; EdgeLight posts to the main thread itself. */
    private val effects = object : DriveEffects {
        override fun regen(start: Long, sootStart: Int?, soot: Int?, temp: Int?) {
            if (Prefs.alerts(this@DpfService)) nm.notify(ID_REGEN, regenNotification(start, sootStart, soot, temp)) else nm.cancel(ID_REGEN)
            edge.show(Prefs.edge(this@DpfService))
        }

        override fun regenHidden() {
            nm.cancel(ID_REGEN)
            edge.show(false)
        }

        override fun alertOpened(f: Finding) = nm.notify(alertId(f.key), notification(CH_HEALTH)
            .setContentTitle(if (f.level == CRIT) "Check the car now" else "Car warning")
            .setContentText(f.text)
            .setStyle(Notification.BigTextStyle().bigText(f.text))
            .setAutoCancel(true)
            .build())

        override fun alertCleared(key: String) = nm.cancel(alertId(key))

        override fun driveEnded() = tripDone()
    }

    override fun onCreate() {
        super.onCreate()
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Background logger", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(CH_REGEN, "DPF regeneration", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_HEALTH, "Health warnings", NotificationManager.IMPORTANCE_LOW))
        val types = FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            (if (checkSelfPermission(ACCESS_FINE_LOCATION) == PERMISSION_GRANTED) FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        try {
            startForeground(ID_SERVICE, status("Waiting for dongle"), types)
        } catch (e: SecurityException) {
            // Location type refused (e.g. started at boot without "Allow all the time"): log without GPS
            startForeground(ID_SERVICE, status("Waiting for dongle"), FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        }
        // The model year is read once: Settings restarts the service on a change
        drive = DriveSession(db, effects, Prefs.model2015(this), { Prefs.oilMetres(this) }).apply { odoM = db.odometer() }
        // A previous process killed mid-regen never closed its row; it would show "Ongoing" forever
        container.real.closeOrphanRegens()
        // Engine-stop blips (under a minute, no distance) are not burns; every start, so one that slips past engineStopped() goes too
        container.real.dropStopBlips()
        drive.start()
        HomeWatch.cancel(this) // back from a pause at home, or started by hand: this instance does the watching now
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
            drive.idle()
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
            try { thin() } catch (e: Exception) { Log.d(TAG, "thinning failed: $e") }
            if (pauseAtHome()) {
                // No dongle search and no notification at home; HomeWatch starts a fresh instance once the phone leaves
                HomeWatch.schedule(this)
                stopSelf()
                break
            }
            nm.notify(ID_SERVICE, status("Waiting for dongle"))
            alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + PROBE_MS, "yaris:probe", probe, null)
            try { idle.acquire() } catch (e: InterruptedException) { break }
        }
    }

    private fun session() {
        if (Prefs.debug) {
            nm.notify(ID_SERVICE, status("Debug mode: simulated dongle"))
            return drive.simulate(Sim.live()) { running }
        }
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
            val started = System.currentTimeMillis()
            try {
                drive.drive(elm) { running }
            } finally {
                stopGps()
                try { db.linkStats(started, elm.timings) } catch (e: Exception) { Log.d(TAG, "link stats not saved: $e") }
            }
        }
    }

    /**
     * On the home Wi-Fi with nothing left to do: no drive or regen still open (the link may come back), nothing the hub
     * hasn't got (a paused service doesn't sync), not Debug mode. Only when the app is exempt from battery optimisation,
     * because that is what lets HomeWatch start the service again from the background; otherwise it keeps running.
     */
    private fun pauseAtHome() = Sync.atHome(this) && !Prefs.debug && !drive.busy && !Sync.pending(this) &&
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    /** Frames older than FULL_MS are thinned to one per request per minute, each stretch once (tracked in Prefs). */
    private fun thin() {
        val to = System.currentTimeMillis() - FULL_MS
        val from = Prefs.long(this, Prefs.THINNED_TO)
        if (to - from < 3_600_000L) return
        container.real.thinFrames(from, to)
        Prefs.put(this, Prefs.THINNED_TO, to)
    }

    /** One quiet notification when a drive ends: distance, soot, regens and warnings. */
    private fun tripDone() {
        val from = db.tripStart() ?: return
        val s = db.tripStats(from)
        val (perUnit, unit) = Prefs.unit(this)
        nm.notify(ID_TRIP, notification(CH_HEALTH)
            .setContentTitle("Drive finished")
            .setContentText(tripSummary(s.metres, s.sootStart, s.sootEnd, s.regens, s.warnings, perUnit, unit))
            .setWhen(from)
            .setAutoCancel(true)
            .build())
    }

    private fun alertId(key: String) = ID_ALERT + (key.hashCode() and 0xFFFF)

    private fun startGps() {
        if (checkSelfPermission(ACCESS_FINE_LOCATION) != PERMISSION_GRANTED) return
        getSystemService(LocationManager::class.java)
            .requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, gps, Looper.getMainLooper())
    }

    private fun stopGps() {
        getSystemService(LocationManager::class.java).removeUpdates(gps)
        lastFix = null
    }

    /** The icon, colour and tap-to-open every notification here shares. */
    private fun notification(channel: String) = Notification.Builder(this, channel)
        .setSmallIcon(R.drawable.ic_stat_gauge)
        .setColor(getColor(R.color.primary))
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))

    private fun status(text: String): Notification =
        notification(CH_SERVICE)
            .setContentTitle("Yaris Doctor")
            .setContentText(text)
            .setOngoing(true)
            .build()

    private fun regenNotification(start: Long, sootStart: Int?, soot: Int?, temp: Int?): Notification =
        notification(CH_REGEN)
            .setContentTitle("DPF regenerating · keep driving")
            .setContentText("DPF ${temp ?: "?"}°C · soot ${soot ?: "?"}%")
            .setWhen(start)
            .setUsesChronometer(true)
            .setShowWhen(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
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
        private const val FULL_MS = 30 * 24 * 3_600_000L
        private const val CH_SERVICE = "service"
        private const val CH_REGEN = "regen"
        private const val ID_SERVICE = 1
        private const val ID_REGEN = 2
        private const val ID_TRIP = 3
        private const val ID_ALERT = 1000
        private const val CH_HEALTH = "health"
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
