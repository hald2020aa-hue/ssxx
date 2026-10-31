package com.openmanga.reader.download

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.openmanga.engine.ImageValidator
import com.openmanga.engine.SourceEngine
import com.openmanga.reader.App
import com.openmanga.reader.R
import com.openmanga.reader.util.Diag
import com.openmanga.reader.util.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Downloads one chapter. Every file is checked with the engine's image validator, so an HTML error page can
 * never be saved as a page. A chapter is marked complete only when every page was stored.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as App
        val sourceId = inputData.getString(KEY_SOURCE) ?: return@withContext Result.failure()
        val manga = inputData.getString(KEY_MANGA) ?: ""
        val name = inputData.getString(KEY_NAME) ?: ""
        val url = inputData.getString(KEY_URL) ?: return@withContext Result.failure()
        val cfg = app.store.get(sourceId) ?: return@withContext Result.failure()
        val engine = SourceEngine(cfg, app.fetcher)

        try { setForeground(foreground("$manga $name", 0, 0)) } catch (e: Exception) { /* background start not allowed: continue without it */ }

        val pages = engine.pages(url)
        val list = pages.value
        if (!pages.ok || list == null) {
            return@withContext Result.failure(workDataOf("error" to Diag.summary(pages.diagnostics)))
        }
        val dir = Files.chapterDir(applicationContext, sourceId, manga, name, url)
        dir.mkdirs()
        File(dir, ".complete").delete()
        var failed = 0
        list.forEachIndexed { i, page ->
            if (isStopped) return@withContext Result.retry()
            val prefix = "%03d".format(page.index)
            val existing = dir.listFiles { f -> f.name.startsWith("$prefix.") }?.firstOrNull()
            if (existing != null && ImageValidator.detectMime(existing.inputStream().use { s -> ByteArray(16).also { b -> s.read(b) } }) != null) {
                setForegroundAsync(foreground("$manga $name", i + 1, list.size))
                return@forEachIndexed
            }
            val res = engine.downloadPage(page)
            val bytes = res.value
            if (res.ok && bytes != null) {
                File(dir, "$prefix.${Files.extensionFor(ImageValidator.detectMime(bytes))}").writeBytes(bytes)
            } else failed++
            setForegroundAsync(foreground("$manga $name", i + 1, list.size))
        }
        if (failed == 0) {
            File(dir, ".complete").writeText(System.currentTimeMillis().toString())
            Result.success()
        } else if (runAttemptCount < 2) {
            Result.retry()
        } else {
            Result.failure(workDataOf("error" to "$failed of ${list.size} pages failed"))
        }
    }

    private fun foreground(title: String, done: Int, total: Int): ForegroundInfo {
        val n: Notification = NotificationCompat.Builder(applicationContext, App.CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(applicationContext.getString(R.string.notif_downloading, title))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total, done, total == 0)
            .build()
        val id = inputData.getString(KEY_URL).hashCode()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(id, n)
    }

    companion object {
        const val KEY_SOURCE = "source"
        const val KEY_MANGA = "manga"
        const val KEY_NAME = "name"
        const val KEY_URL = "url"
    }
}
