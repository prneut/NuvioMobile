package com.nuvio.app.features.downloads

import android.app.Service
import android.content.Intent
import android.os.IBinder

class DownloadsForegroundService : Service() {

    companion object {
        var activeService: DownloadsForegroundService? = null
            private set
        
        const val ACTION_STOP = "com.nuvio.app.action.STOP_DOWNLOADS_FOREGROUND_SERVICE"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            activeService = null
            return START_NOT_STICKY
        }
        
        activeService = this
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        if (activeService == this) {
            activeService = null
        }
    }
}
