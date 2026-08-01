package com.nuvio.app.features.downloads

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.nuvio.app.core.deeplink.buildDownloadsDeepLinkUrl
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import kotlin.math.abs

internal actual object DownloadsLiveStatusPlatform {
    internal const val channelId = "downloads_live_status"
    private const val notificationsPrefName = "nuvio_download_live_notifications"
    private const val trackedDownloadIdsKey = "tracked_download_ids"

    private var appContext: Context? = null
    private val lastRenderStateById = mutableMapOf<String, RenderState>()

    fun initialize(context: Context) {
        appContext = context.applicationContext
        ensureNotificationChannel(context)
    }

    actual fun onItemsChanged(items: List<DownloadItem>) {
        val context = appContext ?: return
        if (!canPostNotifications(context)) return

        val manager = NotificationManagerCompat.from(context)
        val trackedBefore = preferences(context)
            .getStringSet(trackedDownloadIdsKey, emptySet())
            .orEmpty()
            .toMutableSet()

        val activeItems = items.filter { item ->
            item.status == DownloadStatus.Downloading ||
                item.status == DownloadStatus.Paused ||
                item.status == DownloadStatus.Failed
        }

        val trackedNow = mutableSetOf<String>()
        var isForegroundAssigned = false

        if (activeItems.isNotEmpty()) {
            manager.cancel(DownloadsForegroundService.INITIAL_NOTIFICATION_ID)
        }

        activeItems.forEach { item ->
            val renderState = RenderState(
                status = item.status,
                progressPercent = progressPercent(item),
                downloadedBucket = item.downloadedBytes / (512L * 1024L),
                totalBytes = item.totalBytes,
                errorMessage = item.errorMessage,
            )

            val existingState = lastRenderStateById[item.id]
            if (existingState == renderState) {
                trackedNow += item.id
                // Note: we still need to assign foreground if not yet assigned, but we only have cached states.
                // It's fine to just notify again to ensure it's bound.
            }

            val notification = buildNotification(context, item)
            val service = DownloadsForegroundService.activeService
            if (service != null && !isForegroundAssigned) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        service.startForeground(
                            notificationId(item.id), 
                            notification, 
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                        )
                    } else {
                        service.startForeground(notificationId(item.id), notification)
                    }
                    isForegroundAssigned = true
                } catch (e: Exception) {
                    manager.notify(notificationId(item.id), notification)
                }
            } else {
                manager.notify(notificationId(item.id), notification)
            }
            
            lastRenderStateById[item.id] = renderState
            trackedNow += item.id
        }

        if (activeItems.isEmpty()) {
            val stopIntent = Intent(context, DownloadsForegroundService::class.java).apply {
                action = DownloadsForegroundService.ACTION_STOP
            }
            try { context.startService(stopIntent) } catch (e: Exception) {}
        }

        val staleIds = trackedBefore - trackedNow
        staleIds.forEach { downloadId ->
            manager.cancel(notificationId(downloadId))
            lastRenderStateById.remove(downloadId)
        }

        preferences(context)
            .edit()
            .putStringSet(trackedDownloadIdsKey, trackedNow)
            .apply()
    }

    private fun buildNotification(context: Context, item: DownloadItem): android.app.Notification {
        val subtitle = buildSubtitle(item)
        val launchIntent = Intent(context, com.nuvio.app.MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = android.net.Uri.parse(buildDownloadsDeepLinkUrl())
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val launchPendingIntent = PendingIntent.getActivity(
            context,
            notificationId(item.id),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notificationBuilder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(com.nuvio.app.R.drawable.ic_notification_small)
            .setContentTitle(item.title)
            .setContentText(subtitle)
            .setStyle(NotificationCompat.BigTextStyle().bigText(subtitle))
            .setOnlyAlertOnce(true)
            .setContentIntent(launchPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        when (item.status) {
            DownloadStatus.Downloading -> {
                notificationBuilder
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .addAction(
                        0,
                        runBlocking { getString(Res.string.compose_action_pause) },
                        buildActionPendingIntent(
                            context = context,
                            action = DownloadsNotificationActionReceiver.actionPause,
                            downloadId = item.id,
                        ),
                    )

                val progress = progressPercent(item)
                if (progress >= 0) {
                    notificationBuilder.setProgress(100, progress, false)
                } else {
                    notificationBuilder.setProgress(100, 0, true)
                }
            }

            DownloadStatus.Paused,
            DownloadStatus.Failed,
            DownloadStatus.Completed,
            -> {
                notificationBuilder
                    .setOngoing(false)
                    .setAutoCancel(false)
                    .setPriority(
                        if (item.status == DownloadStatus.Failed) {
                            NotificationCompat.PRIORITY_DEFAULT
                        } else {
                            NotificationCompat.PRIORITY_LOW
                        },
                    )
                    .setProgress(0, 0, false)
                    .addAction(
                        0,
                        runBlocking { getString(Res.string.action_resume) },
                        buildActionPendingIntent(
                            context = context,
                            action = DownloadsNotificationActionReceiver.actionResume,
                            downloadId = item.id,
                        ),
                    )
            }
        }

        return notificationBuilder.build()
    }

    private fun buildSubtitle(item: DownloadItem): String {
        val detail = item.displaySubtitle
        return when (item.status) {
            DownloadStatus.Downloading -> {
                val downloaded = formatBytes(item.downloadedBytes)
                val total = item.totalBytes?.let(::formatBytes)
                val speedText = item.downloadSpeedBytesPerSec?.takeIf { it > 0L }?.let { "${formatBytes(it)}/s" }
                val progressText = if (total != null) {
                    runBlocking { getString(Res.string.downloads_live_downloading_with_total, detail, downloaded, total) }
                } else {
                    runBlocking { getString(Res.string.downloads_live_downloading, detail, downloaded) }
                }
                if (speedText != null) {
                    "$progressText • $speedText"
                } else {
                    progressText
                }
            }

            DownloadStatus.Paused -> runBlocking { getString(Res.string.downloads_live_paused, detail) }
            DownloadStatus.Failed -> item.errorMessage?.takeIf { it.isNotBlank() } ?: runBlocking { getString(Res.string.downloads_live_failed) }
            DownloadStatus.Completed -> runBlocking { getString(Res.string.downloads_live_completed) }
        }
    }

    private fun formatBytes(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L).toDouble()
        val units = runBlocking {
            arrayOf(
                getString(Res.string.unit_bytes_b),
                getString(Res.string.unit_bytes_kb),
                getString(Res.string.unit_bytes_mb),
                getString(Res.string.unit_bytes_gb),
                getString(Res.string.unit_bytes_tb),
            )
        }
        var value = safe
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex += 1
        }
        return if (unitIndex == 0) {
            "${value.toLong()} ${units[unitIndex]}"
        } else {
            "${"%.1f".format(value)} ${units[unitIndex]}"
        }
    }

    private fun progressPercent(item: DownloadItem): Int {
        val total = item.totalBytes?.takeIf { it > 0L } ?: return -1
        return ((item.downloadedBytes.toDouble() / total.toDouble()) * 100.0)
            .toInt()
            .coerceIn(0, 100)
    }

    private fun buildActionPendingIntent(
        context: Context,
        action: String,
        downloadId: String,
    ): PendingIntent {
        val intent = Intent(context, DownloadsNotificationActionReceiver::class.java).apply {
            this.action = action
            putExtra(DownloadsNotificationActionReceiver.extraDownloadId, downloadId)
        }
        return PendingIntent.getBroadcast(
            context,
            notificationId("$action:$downloadId"),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    internal fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        if (manager.getNotificationChannel(channelId) != null) return

        manager.createNotificationChannel(
            NotificationChannel(
                channelId,
                runBlocking { getString(Res.string.downloads_channel_name) },
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = runBlocking { getString(Res.string.downloads_channel_description) }
            },
        )
    }

    private fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissionState = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            )
            if (permissionState != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(notificationsPrefName, Context.MODE_PRIVATE)

    private fun notificationId(downloadId: String): Int = abs(downloadId.hashCode())

    private data class RenderState(
        val status: DownloadStatus,
        val progressPercent: Int,
        val downloadedBucket: Long,
        val totalBytes: Long?,
        val errorMessage: String?,
    )
}
