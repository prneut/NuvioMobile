package com.nuvio.app.features.downloads

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.Build
import android.content.Context
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import nuvio.composeapp.generated.resources.*

import android.net.wifi.WifiManager

class DownloadsForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    companion object {
        var activeService: DownloadsForegroundService? = null
            private set
        
        const val ACTION_STOP = "com.nuvio.app.action.STOP_DOWNLOADS_FOREGROUND_SERVICE"
        const val INITIAL_NOTIFICATION_ID = 84729
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            activeService = null
            releaseWakeLock()
            return START_NOT_STICKY
        }
        
        activeService = this
        acquireWakeLock()
        
        DownloadsLiveStatusPlatform.ensureNotificationChannel(this)
        val notification = NotificationCompat.Builder(this, DownloadsLiveStatusPlatform.channelId)
            .setSmallIcon(com.nuvio.app.R.drawable.ic_notification_small)
            .setContentTitle(runBlocking { getString(Res.string.downloads_channel_name) })
            .setContentText("Preparing download...")
            .setOngoing(true)
            .build()
            
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    INITIAL_NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(INITIAL_NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Ignore
        }
        
        return START_STICKY
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Nuvio:DownloadsWakeLock")
            wakeLock?.acquire(4 * 60 * 60 * 1000L) // 4 hours max per chunk/part
        }
        if (wifiLock == null) {
            val wifiManager = getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Nuvio:DownloadsWifiLock")
            wifiLock?.acquire()
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {}
        wakeLock = null
        try {
            wifiLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {}
        wifiLock = null
    }

    override fun onDestroy() {
        super.onDestroy()
        if (activeService == this) {
            activeService = null
        }
        releaseWakeLock()
    }
}
