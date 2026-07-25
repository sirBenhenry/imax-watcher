package com.odysseywatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Brings the watcher back after a reboot, an app update, or a watchdog alarm. */
class BootReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_WATCHDOG = "com.odysseywatch.WATCHDOG"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        if (!prefs.running) return
        WatchService.start(context)
    }
}
