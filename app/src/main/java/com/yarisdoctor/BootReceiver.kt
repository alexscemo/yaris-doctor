package com.yarisdoctor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Nothing is connected right after a boot; a stale "on" would keep the service from pausing at home
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) Prefs.put(context, Prefs.CAR_BT_ON, false)
        DpfService.start(context)
    }
}
