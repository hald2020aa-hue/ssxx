package com.openmanga.reader

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.openmanga.engine.Chapter
import com.openmanga.engine.Fetcher
import com.openmanga.reader.data.ListCache
import com.openmanga.reader.data.SourceStore
import com.openmanga.reader.net.AppFetcher
import java.io.File

class App : Application() {
    lateinit var store: SourceStore
    lateinit var cache: ListCache
    lateinit var fetcher: Fetcher

    /** The chapter list of the manga being read, so the reader can offer next/previous chapter. */
    @Volatile
    var readerChapters: List<Chapter> = emptyList()

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = SourceStore(filesDir)
        cache = ListCache(File(filesDir, "cache"))
        fetcher = AppFetcher(this)
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        const val CHANNEL = "downloads"
        lateinit var instance: App
            private set
    }
}
