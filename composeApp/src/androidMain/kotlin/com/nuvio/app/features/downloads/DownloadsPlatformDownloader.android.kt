package com.nuvio.app.features.downloads

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

private val downloadHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .followRedirects(true)
    .followSslRedirects(true)
    .build()

private const val PARALLEL_STREAMS = 4
private const val MIN_PARALLEL_FILE_SIZE = 20L * 1024L * 1024L // 20 MB
private const val BUFFER_SIZE = 64 * 1024 // 64 KB
private const val WATCHDOG_TIMEOUT_MS = 15_000L // 15 seconds inactivity
private const val PROGRESS_REPORT_INTERVAL_MS = 400L // 400ms UI throttle

internal actual object DownloadsPlatformDownloader {
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    actual fun start(
        request: DownloadPlatformRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?, speedBytesPerSec: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
    ): DownloadsTaskHandle {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val activeCalls = ConcurrentHashMap.newKeySet<Call>()

        scope.launch {
            val context = appContext
            if (context == null) {
                onFailure(runBlocking { getString(Res.string.downloads_error_not_initialized) })
                return@launch
            }

            try {
                androidx.core.content.ContextCompat.startForegroundService(
                    context,
                    android.content.Intent(context, DownloadsForegroundService::class.java)
                )
            } catch (e: Exception) {
                // Ignore background service start restriction
            }

            val downloadsDir = File(context.filesDir, "downloads").apply { mkdirs() }
            val destination = File(downloadsDir, request.destinationFileName)
            val tempFile = File(downloadsDir, "${request.destinationFileName}.part")

            var retryCount = 0
            val maxRetries = 20

            while (true) {
                activeCalls.clear()
                try {
                    // Probe server capability (Range support, total bytes, content type)
                    val probeResult = probeServer(request)

                    if (probeResult.isInvalidType) {
                        error("Unsupported content type for download: ${probeResult.contentType}")
                    }

                    val totalBytes = probeResult.totalBytes
                    val supportsRange = probeResult.supportsRange && totalBytes != null && totalBytes >= MIN_PARALLEL_FILE_SIZE

                    if (supportsRange && totalBytes != null) {
                        downloadParallel(
                            request = request,
                            tempFile = tempFile,
                            totalBytes = totalBytes,
                            activeCalls = activeCalls,
                            onProgress = onProgress,
                        )
                    } else {
                        downloadSingleStream(
                            request = request,
                            tempFile = tempFile,
                            activeCalls = activeCalls,
                            onProgress = onProgress,
                        )
                    }

                    if (destination.exists()) {
                        destination.delete()
                    }
                    if (!tempFile.renameTo(destination)) {
                        tempFile.copyTo(destination, overwrite = true)
                        tempFile.delete()
                    }

                    val finalSize = destination.length()
                    onSuccess(destination.toURI().toString(), totalBytes ?: finalSize)
                    break // Download completed successfully!
                } catch (error: CancellationException) {
                    activeCalls.forEach { runCatching { it.cancel() } }
                    throw error
                } catch (error: Throwable) {
                    activeCalls.forEach { runCatching { it.cancel() } }
                    if (error is java.io.IOException) {
                        retryCount++
                        if (retryCount <= maxRetries) {
                            kotlinx.coroutines.delay(3000L) // Wait 3s before auto-resuming
                            continue
                        }
                    }
                    onFailure(error.message ?: runBlocking { getString(Res.string.download_failed) })
                    break
                }
            }
        }

        job.invokeOnCompletion {
            activeCalls.forEach { runCatching { it.cancel() } }
        }

        return AndroidDownloadsTaskHandle(job, activeCalls)
    }

    actual fun removeFile(localFileUri: String?): Boolean {
        if (localFileUri.isNullOrBlank()) return false
        val file = localFileUri.toLocalFileOrNull() ?: return false
        return runCatching { file.delete() }.getOrDefault(false)
    }

    actual fun removePartialFile(destinationFileName: String): Boolean {
        val context = appContext ?: return false
        val downloadsDir = File(context.filesDir, "downloads")
        val tempFile = File(downloadsDir, "$destinationFileName.part")
        val stateFile = File(downloadsDir, "$destinationFileName.parts_meta")
        if (stateFile.exists()) runCatching { stateFile.delete() }
        if (!tempFile.exists()) return true
        return runCatching { tempFile.delete() }.getOrDefault(false)
    }

    actual fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? {
        localFileUri
            ?.toLocalFileOrNull()
            ?.takeIf { it.exists() }
            ?.let { return it.toURI().toString() }

        val context = appContext ?: return null
        val fileName = destinationFileName.trim().takeIf { it.isNotBlank() }
            ?: localFileUri
                ?.toLocalFileOrNull()
                ?.name
                ?.takeIf { it.isNotBlank() }
            ?: return null
        val downloadsDir = File(context.filesDir, "downloads")
        val localFile = File(downloadsDir, fileName)
        return localFile.takeIf { it.exists() }?.toURI()?.toString()
    }

    actual fun openDownloadsDirectory(): Boolean {
        val context = appContext ?: return false
        val downloadsDir = File(context.filesDir, "downloads").apply { mkdirs() }
        val uri = runCatching {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                downloadsDir,
            )
        }.getOrNull() ?: return false

        val intents = listOf(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "resource/folder")
            },
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "vnd.android.document/directory")
            },
            Intent(Intent.ACTION_VIEW).apply {
                data = uri
            },
        )

        return intents.any { intent ->
            intent.addCategory(Intent.CATEGORY_DEFAULT)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)

            runCatching {
                context.startActivity(intent)
                true
            }.getOrDefault(false)
        }
    }
}

private data class ProbeResult(
    val supportsRange: Boolean,
    val totalBytes: Long?,
    val contentType: String,
    val isInvalidType: Boolean,
)

private fun probeServer(request: DownloadPlatformRequest): ProbeResult {
    val reqBuilder = Request.Builder().url(request.sourceUrl)
    request.sourceHeaders.forEach { (key, value) -> reqBuilder.header(key, value) }
    reqBuilder.header("Range", "bytes=0-0")

    val call = downloadHttpClient.newCall(reqBuilder.get().build())
    val response = call.execute()

    response.use { resp ->
        val contentType = resp.header("Content-Type")?.lowercase() ?: ""
        val isInvalidType = contentType.contains("text/html") ||
            contentType.contains("application/json") ||
            contentType.contains("application/x-bittorrent") ||
            contentType.contains("application/x-mpegurl") ||
            contentType.contains("application/vnd.apple.mpegurl") ||
            contentType.contains("text/plain")

        val isPartial = resp.code == 206
        val acceptRanges = resp.header("Accept-Ranges")?.lowercase() == "bytes"
        val supportsRange = isPartial || acceptRanges

        val contentRangeHeader = resp.header("Content-Range")
        val totalBytes = parseContentRangeTotal(contentRangeHeader)
            ?: resp.body?.contentLength()?.takeIf { it > 0L }

        return ProbeResult(
            supportsRange = supportsRange,
            totalBytes = totalBytes,
            contentType = contentType,
            isInvalidType = isInvalidType,
        )
    }
}

private suspend fun downloadParallel(
    request: DownloadPlatformRequest,
    tempFile: File,
    totalBytes: Long,
    activeCalls: MutableSet<Call>,
    onProgress: (downloadedBytes: Long, totalBytes: Long?, speedBytesPerSec: Long?) -> Unit,
) = coroutineScope {
    val chunkSize = totalBytes / PARALLEL_STREAMS
    val metaFile = File(tempFile.parentFile, "${tempFile.name}.parts_meta")
    val savedProgress = loadPartProgress(metaFile, PARALLEL_STREAMS)

    RandomAccessFile(tempFile, "rw").use { raf ->
        if (tempFile.length() < totalBytes) {
            raf.setLength(totalBytes)
        }
    }

    val downloadedBytesCounter = AtomicLong(savedProgress.sumOf { it })
    val lastByteReadTime = AtomicLong(System.currentTimeMillis())

    // Launch Speed & Progress Reporter + Stalled Watchdog Job
    val monitoringJob = launch(Dispatchers.Default) {
        var lastBytes = downloadedBytesCounter.get()
        var lastTime = System.currentTimeMillis()

        while (isActive) {
            delay(PROGRESS_REPORT_INTERVAL_MS)
            val currentBytes = downloadedBytesCounter.get()
            val currentTime = System.currentTimeMillis()
            val timeDeltaSec = (currentTime - lastTime) / 1000.0
            val byteDelta = currentBytes - lastBytes

            val speed = if (timeDeltaSec > 0.0 && byteDelta > 0L) {
                (byteDelta / timeDeltaSec).toLong()
            } else null

            lastBytes = currentBytes
            lastTime = currentTime

            onProgress(currentBytes, totalBytes, speed)

            // Watchdog check for stalled connections
            val idleMs = currentTime - lastByteReadTime.get()
            if (idleMs >= WATCHDOG_TIMEOUT_MS && currentBytes < totalBytes) {
                activeCalls.forEach { runCatching { it.cancel() } }
                throw SocketTimeoutException("Download stalled (no bytes received for 15s)")
            }
        }
    }

    try {
        val jobs = (0 until PARALLEL_STREAMS).map { index ->
            val startByte = index * chunkSize
            val endByte = if (index == PARALLEL_STREAMS - 1) totalBytes - 1 else (index + 1) * chunkSize - 1
            val alreadyDownloaded = savedProgress[index].coerceAtMost(endByte - startByte + 1)
            val fetchStart = startByte + alreadyDownloaded

            async(Dispatchers.IO) {
                if (fetchStart > endByte) return@async

                val reqBuilder = Request.Builder().url(request.sourceUrl)
                request.sourceHeaders.forEach { (key, value) -> reqBuilder.header(key, value) }
                reqBuilder.header("Range", "bytes=$fetchStart-$endByte")

                val call = downloadHttpClient.newCall(reqBuilder.build())
                activeCalls.add(call)

                val response = call.execute()
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        error("Parallel chunk HTTP ${resp.code}")
                    }

                    val body = resp.body ?: error("Empty chunk body")
                    var currentOffset = fetchStart

                    RandomAccessFile(tempFile, "rw").use { raf ->
                        raf.seek(currentOffset)
                        body.byteStream().use { input ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            while (true) {
                                ensureActive()
                                val read = input.read(buffer)
                                if (read <= 0) break

                                raf.write(buffer, 0, read)
                                currentOffset += read
                                downloadedBytesCounter.addAndGet(read.toLong())
                                lastByteReadTime.set(System.currentTimeMillis())

                                savedProgress[index] = currentOffset - startByte
                            }
                        }
                    }
                }
            }
        }

        jobs.awaitAll()
        savePartProgress(metaFile, savedProgress)
    } finally {
        monitoringJob.cancel()
        savePartProgress(metaFile, savedProgress)
    }
}

private suspend fun downloadSingleStream(
    request: DownloadPlatformRequest,
    tempFile: File,
    activeCalls: MutableSet<Call>,
    onProgress: (downloadedBytes: Long, totalBytes: Long?, speedBytesPerSec: Long?) -> Unit,
) = coroutineScope {
    var resumeFromBytes = tempFile.takeIf { it.exists() }?.length()?.coerceAtLeast(0L) ?: 0L

    val reqBuilder = Request.Builder().url(request.sourceUrl)
    request.sourceHeaders.forEach { (key, value) -> reqBuilder.header(key, value) }
    if (resumeFromBytes > 0L) {
        reqBuilder.header("Range", "bytes=$resumeFromBytes-")
    }

    val call = downloadHttpClient.newCall(reqBuilder.build())
    activeCalls.add(call)

    var response = call.execute()

    if (resumeFromBytes > 0L && response.code == 416) {
        response.close()
        tempFile.delete()
        resumeFromBytes = 0L
        val retryCall = downloadHttpClient.newCall(
            Request.Builder().url(request.sourceUrl).apply {
                request.sourceHeaders.forEach { (k, v) -> header(k, v) }
            }.build()
        )
        activeCalls.add(retryCall)
        response = retryCall.execute()
    }

    response.use { resp ->
        if (!resp.isSuccessful) {
            error(runBlocking { getString(Res.string.downloads_error_http_failed, resp.code) })
        }

        val isPartial = resp.code == 206 && resumeFromBytes > 0L
        val startingBytes = if (isPartial) resumeFromBytes else 0L

        if (!isPartial && tempFile.exists()) {
            tempFile.delete()
        }

        val body = resp.body ?: error("Empty body")
        val totalBytes = resolveTotalBytes(
            startingBytes = startingBytes,
            isPartialResume = isPartial,
            contentRangeHeader = resp.header("Content-Range"),
            contentLength = body.contentLength().takeIf { it > 0L },
        )

        var downloadedBytes = startingBytes
        val lastByteReadTime = AtomicLong(System.currentTimeMillis())

        val monitoringJob = launch(Dispatchers.Default) {
            var lastBytes = downloadedBytes
            var lastTime = System.currentTimeMillis()

            while (isActive) {
                delay(PROGRESS_REPORT_INTERVAL_MS)
                val currentBytes = downloadedBytes
                val currentTime = System.currentTimeMillis()
                val timeDeltaSec = (currentTime - lastTime) / 1000.0
                val byteDelta = currentBytes - lastBytes

                val speed = if (timeDeltaSec > 0.0 && byteDelta > 0L) {
                    (byteDelta / timeDeltaSec).toLong()
                } else null

                lastBytes = currentBytes
                lastTime = currentTime

                onProgress(currentBytes, totalBytes, speed)

                val idleMs = currentTime - lastByteReadTime.get()
                if (idleMs >= WATCHDOG_TIMEOUT_MS && (totalBytes == null || currentBytes < totalBytes)) {
                    activeCalls.forEach { runCatching { it.cancel() } }
                    throw SocketTimeoutException("Single stream stalled (no bytes received for 15s)")
                }
            }
        }

        try {
            body.byteStream().use { input ->
                FileOutputStream(tempFile, isPartial).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloadedBytes += read.toLong()
                        lastByteReadTime.set(System.currentTimeMillis())
                    }
                    output.flush()
                }
            }
        } finally {
            monitoringJob.cancel()
        }
    }
}

private fun loadPartProgress(metaFile: File, count: Int): LongArray {
    val arr = LongArray(count)
    if (!metaFile.exists()) return arr
    return runCatching {
        val text = metaFile.readText().trim()
        val parts = text.split(",")
        LongArray(count) { i -> parts.getOrNull(i)?.toLongOrNull() ?: 0L }
    }.getOrDefault(arr)
}

private fun savePartProgress(metaFile: File, progress: LongArray) {
    runCatching {
        metaFile.writeText(progress.joinToString(","))
    }
}

private class AndroidDownloadsTaskHandle(
    private val job: Job,
    private val activeCalls: Set<Call>,
) : DownloadsTaskHandle {
    override fun cancel() {
        activeCalls.forEach { runCatching { it.cancel() } }
        job.cancel()
    }
}

private fun String.toLocalFileOrNull(): File? {
    return runCatching {
        if (startsWith("file:")) {
            File(URI(this))
        } else {
            File(this)
        }
    }.getOrNull()
}

private fun resolveTotalBytes(
    startingBytes: Long,
    isPartialResume: Boolean,
    contentRangeHeader: String?,
    contentLength: Long?,
): Long? {
    parseContentRangeTotal(contentRangeHeader)?.let { return it }
    val normalizedLength = contentLength?.takeIf { it > 0L } ?: return null
    return if (isPartialResume && startingBytes > 0L) {
        startingBytes + normalizedLength
    } else {
        normalizedLength
    }
}

private fun parseContentRangeTotal(headerValue: String?): Long? {
    val value = headerValue?.trim().orEmpty()
    if (value.isBlank()) return null
    val slashIndex = value.lastIndexOf('/')
    if (slashIndex == -1 || slashIndex == value.lastIndex) return null
    val totalPart = value.substring(slashIndex + 1).trim()
    if (totalPart == "*") return null
    return totalPart.toLongOrNull()?.takeIf { it > 0L }
}

