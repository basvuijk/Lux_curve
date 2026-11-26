package com.example.luxcurve

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the services after a reboot if they were switched on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = Prefs(context)
        if (prefs.enabled) {
            context.startForegroundService(Intent(context, BrightnessService::class.java))
        }
        // Also starts the Home Assistant link when it is set up (nothing happens otherwise).
        HaService.sync(context)
    }
}
