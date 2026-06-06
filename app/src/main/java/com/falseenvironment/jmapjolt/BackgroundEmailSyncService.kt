package com.falseenvironment.jmapjolt

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class BackgroundEmailSyncService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Background sync service started")
        startForeground(
                SYNC_NOTIFICATION_ID,
                BackgroundEmailSyncReceiver.buildSyncInProgressNotification(this)
        )
        scope.launch {
            try {
                BackgroundEmailSyncReceiver.fetchAndNotify(applicationContext)
            } catch (error: Throwable) {
                Log.e(TAG, "Background email sync service failed", error)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BackgroundEmailSyncSvc"
        private const val SYNC_NOTIFICATION_ID = 4003

        fun start(context: Context) {
            val intent = Intent(context, BackgroundEmailSyncService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
    }
}
