package com.openmanga.reader.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.google.android.material.snackbar.Snackbar
import com.openmanga.engine.Chapter
import com.openmanga.engine.Diagnostic
import com.openmanga.engine.MangaDetails
import com.openmanga.engine.SourceConfig
import com.openmanga.engine.SourceEngine
import com.openmanga.engine.SourceRunner
import com.openmanga.engine.Stage
import com.openmanga.reader.App
import com.openmanga.reader.R
import com.openmanga.reader.download.DownloadWorker
import com.openmanga.reader.util.Diag
import com.openmanga.reader.util.Files
import com.openmanga.reader.util.formatTime
import com.openmanga.reader.util.isOnline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Work details and the COMPLETE chapter list (every page of the site's chapter listing is fetched). */
class MangaActivity : AppCompatActivity() {
    private lateinit var source: SourceConfig
    private lateinit var engine: SourceEngine
    private lateinit var mangaUrl: String
    private lateinit var mangaTitle: String
    private lateinit var cover: ImageView
    private lateinit var titleTv: TextView
    private lateinit var metaTv: TextView
    private lateinit var descTv: TextView
    private lateinit var notice: TextView
    private lateinit var errorBox: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var adapter: SimpleAdapter<Chapter>
    private var chapters: List<Chapter> = emptyList()
    private var diagnostics: List<Diagnostic> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = intent.getStringExtra(EXTRA_SOURCE)?.let { App.instance.store.get(it) }
        mangaUrl = intent.getStringExtra(EXTRA_URL) ?: ""
        if (cfg == null || mangaUrl.isEmpty()) { finish(); return }
        source = cfg
        engine = SourceEngine(cfg, App.instance.fetcher)
        mangaTitle = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.unknown)
        title = mangaTitle

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.applyBottomInsets()
        cover = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0x22888888) }
        titleTv = label(mangaTitle, 18f, bold = true)
        metaTv = label("", 12f)
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(titleTv); addView(metaTv) }
        val head = LinearLayout(this).apply {
            setPadding(dp(12), dp(12), dp(12), dp(4))
            addView(cover, LinearLayout.LayoutParams(dp(90), dp(130)))
            addView(info, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12) })
        }
        descTv = label("", 13f).apply {
            setPadding(dp(12), dp(4), dp(12), dp(8)); maxLines = 3
            setOnClickListener { maxLines = if (maxLines == 3) 100 else 3 }
        }
        notice = label("", 12f).apply { setPadding(dp(12), dp(6), dp(12), dp(6)); setBackgroundColor(0x33FFC107); visibility = View.GONE }
        errorText = label("", 14f).apply { setPadding(dp(24), dp(16), dp(24), dp(8)) }
        errorBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            addView(errorText)
            addView(android.widget.Button(this@MangaActivity).apply { text = getString(R.string.retry); setOnClickListener { load() } })
            addView(android.widget.Button(this@MangaActivity).apply { text = getString(R.string.details); setOnClickListener { showDiagnostics(diagnostics, source.baseUrl) } })
        }
        adapter = SimpleAdapter(make = { ctx -> row(ctx) }, bind = { v, ch, _ -> bindRow(v, ch) })
        val list = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@MangaActivity); adapter = this@MangaActivity.adapter }
        listOf(head, descTv, notice, errorBox).forEach { root.addView(it, LinearLayout.LayoutParams(MATCH, WRAP)) }
        root.addView(list, LinearLayout.LayoutParams(MATCH, 0, 1f))
        setContentView(root)
        intent.getStringExtra(EXTRA_COVER)?.let { loadCover(it) }
        load()
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) adapter.notifyDataSetChanged() // refresh "downloaded" marks
    }

    private fun loadCover(url: String) {
        val h = LazyHeaders.Builder().addHeader("Referer", source.baseUrl.trimEnd('/') + "/")
        source.headers.forEach { (k, v) -> h.addHeader(k, v) }
        Glide.with(cover).load(GlideUrl(url, h.build())).into(cover)
    }

    private fun load() {
        errorBox.visibility = View.GONE
        lifecycleScope.launch {
            val details = async(Dispatchers.IO) { SourceRunner.isolated(source, Stage.DETAILS) { engine.details(mangaUrl) } }
            val chaptersOut = async(Dispatchers.IO) { SourceRunner.isolated(source, Stage.CHAPTERS) { engine.chapters(mangaUrl) } }
            val d = details.await()
            val c = chaptersOut.await()
            diagnostics = d.diagnostics + c.diagnostics
            d.value?.let { showDetails(it) }
            val list = c.value
            if (c.ok && list != null) {
                chapters = list
                App.instance.cache.saveChapters(source.id, mangaUrl, list)
                notice.visibility = View.GONE
                if (c.diagnostics.any { it.code == "PARTIAL" || it.code == "TRUNCATED" }) {
                    notice.text = c.diagnostics.filter { it.code == "PARTIAL" || it.code == "TRUNCATED" }.joinToString("\n") { Diag.explain(it) }
                    notice.visibility = View.VISIBLE
                }
            } else {
                val cached = App.instance.cache.loadChapters(source.id, mangaUrl)
                val reason = if (!isOnline(this@MangaActivity)) getString(R.string.offline) else Diag.summary(c.diagnostics)
                if (cached != null) {
                    chapters = cached.value
                    notice.text = getString(R.string.cached_banner, formatTime(cached.savedAt)) + "\n" + reason
                    notice.visibility = View.VISIBLE
                } else {
                    chapters = emptyList()
                    errorText.text = reason
                    errorBox.visibility = View.VISIBLE
                }
            }
            adapter.items = chapters
            metaTv.text = listOfNotNull(metaTv.text.toString().ifBlank { null }, getString(R.string.chapters_count, chapters.size)).joinToString("\n")
        }
    }

    private fun showDetails(d: MangaDetails) {
        d.title?.let { titleTv.text = it; mangaTitle = it; title = it }
        metaTv.text = listOfNotNull(d.author, d.status, d.genres.takeIf { it.isNotEmpty() }?.joinToString("، ")).joinToString("\n")
        descTv.text = d.description ?: ""
        if (intent.getStringExtra(EXTRA_COVER) == null) d.cover?.let { loadCover(it) }
    }

    private fun row(ctx: android.content.Context): View = LinearLayout(ctx).apply {
        layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
        setPadding(ctx.dp(16), ctx.dp(10), ctx.dp(8), ctx.dp(10))
        isClickable = true
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(ctx.label("", 15f)); addView(ctx.label("", 11f))
        }
        addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(ctx.label("⬇", 20f).apply { setPadding(ctx.dp(16), ctx.dp(4), ctx.dp(16), ctx.dp(4)); isClickable = true })
    }

    private fun bindRow(v: View, ch: Chapter) {
        val box = v as LinearLayout
        val texts = box.getChildAt(0) as LinearLayout
        (texts.getChildAt(0) as TextView).text = ch.name
        (texts.getChildAt(1) as TextView).text = ch.dateText ?: ""
        val dl = box.getChildAt(1) as TextView
        val done = Files.isComplete(Files.chapterDir(this, source.id, mangaTitle, ch.name, ch.url))
        dl.text = if (done) "✓" else "⬇"
        dl.contentDescription = getString(if (done) R.string.downloaded else R.string.download)
        dl.setOnClickListener { if (!done) enqueueDownload(ch) }
        box.setOnClickListener {
            App.instance.readerChapters = chapters
            startActivity(
                Intent(this, ReaderActivity::class.java)
                    .putExtra(ReaderActivity.EXTRA_SOURCE, source.id).putExtra(ReaderActivity.EXTRA_URL, ch.url)
                    .putExtra(ReaderActivity.EXTRA_NAME, ch.name).putExtra(ReaderActivity.EXTRA_MANGA, mangaTitle),
            )
        }
    }

    private fun enqueueDownload(ch: Chapter) {
        val req = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(DownloadWorker.KEY_SOURCE to source.id, DownloadWorker.KEY_MANGA to mangaTitle, DownloadWorker.KEY_NAME to ch.name, DownloadWorker.KEY_URL to ch.url))
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork("dl_${source.id}_${ch.url.hashCode()}", ExistingWorkPolicy.KEEP, req)
        val needsNotif = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        val bar = Snackbar.make(findViewById(android.R.id.content), R.string.download_queued, Snackbar.LENGTH_LONG)
        if (needsNotif) {
            bar.setAction(R.string.open_settings) {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }
        }
        bar.show()
    }

    companion object {
        const val EXTRA_SOURCE = "source"
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_COVER = "cover"
    }
}
