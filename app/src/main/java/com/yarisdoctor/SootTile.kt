package com.yarisdoctor

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile: soot % and the regen state at a glance, read each time the shade opens (no updates while it is
 * closed, so it costs nothing in between). Active (lit) while a regen burns. A tap opens the app.
 */
class SootTile : TileService() {
    override fun onStartListening() {
        Thread({
            // The service's in-memory reading when it is newer than the log's last sample, like the Overview
            val stored = container.db().lastSample()
            val s = Live.reading.value?.takeIf { it.debug == Prefs.debug }?.sample?.takeIf { it.t > (stored?.t ?: 0) } ?: stored
            val tile = qsTile ?: return@Thread
            tile.label = s?.soot?.let { "Soot $it %" } ?: "Soot —"
            tile.subtitle = when {
                s == null -> "No readings yet"
                s.regen && System.currentTimeMillis() - s.t <= Live.LIVE_MS -> "Regen burning"
                else -> "Regen off"
            }
            tile.state = if (tile.subtitle == "Regen burning") Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.updateTile()
        }, "soot-tile").start()
    }

    override fun onClick() {
        val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION") @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated") // Android 12-13 only, where it still works
            startActivityAndCollapse(open)
        }
    }
}
