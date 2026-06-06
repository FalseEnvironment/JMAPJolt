package com.falseenvironment.jmapjolt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class SseBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!JmapEventSourceService.isEnabled(context)) return
        if (BackgroundEmailSyncReceiver.readCurrentAccount(context) == null) return
        Log.d("SseBootReceiver", "Starting SSE service after ${intent.action}")
        JmapEventSourceService.start(context)
    }
}
