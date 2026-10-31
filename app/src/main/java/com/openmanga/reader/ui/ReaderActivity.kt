package com.openmanga.reader.ui

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.openmanga.engine.Diagnostic
import com.openmanga.engine.SourceConfig
import com.openmanga.engine.SourceEngine
import com.openmanga.engine.SourceRunner
import com.openmanga.engine.Stage
import com.openmanga.reader.App
import com.openmanga.reader.R
import com.openmanga.reader.util.Diag
import com.openmanga.reader.util.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Vertical reader. Downloaded chapters are read from disk; others stream the real page images from the source. */
class ReaderActivity : AppCompatActivity() {
    private class Page(val index: Int, val url: String?, val file: File?, val headers: Map<String, String>)

    private lateinit var source: SourceConfig
    private lateinit var engine: SourceEngine
    private lateinit var chapterUrl: String
    private lateinit var chapterName: String
    private lateinit var mangaTitle: String
    private lateinit var list: RecyclerView
    private lateinit var adapter: SimpleAdapter<Page>
    private lateinit var errorBox: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var prevBtn: Button
    private lateinit var nextBtn: Button
    private var diagnostics: List<Diagnostic> = emptyList()
    private var total = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = intent.getStringExtra(EXTRA_SOURCE)?.let { App.instance.store.get(it) }
        chapterUrl = intent.getStringExtra(EXTRA_URL) ?: ""
        if (cfg == null || chapterUrl.isEmpty()) { finish(); return }
        source = cfg
        engine = SourceEngine(cfg, App.instance.fetcher)
        chapterName = intent.getStringExtra(EXTRA_NAME) ?: ""
        mangaTitle = intent.getStringExtra(EXTRA_MANGA) ?: ""

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.applyBottomInsets()
        val width = resources.displayMetrics.widthPixels
        adapter = SimpleAdapter(make = { ctx -> pageView(ctx) }, bind = { v, p, pos -> bindPage(v, p, pos, width) })
        val lm = LinearLayoutManager(this)
        list = RecyclerView(this).apply {
            layoutManager = lm
            adapter = this@ReaderActivity.adapter
            setItemViewCacheSize(4)
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    val pos = lm.findFirstVisibleItemPosition()
                    if (pos >= 0 && total > 0) supportActionBar?.subtitle = getString(R.string.page_of, pos + 1, total)
                }
            })
        }
        errorText = label("", 14f).apply { setPadding(dp(24), dp(24), dp(24), dp(12)) }
        errorBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            addView(errorText)
            addView(Button(this@ReaderActivity).apply { text = getString(R.string.retry); setOnClickListener { load() } })
            addView(Button(this@ReaderActivity).apply { text = getString(R.string.details); setOnClickListener { showDiagnostics(diagnostics, chapterUrl) } })
        }
        prevBtn = Button(this).apply { text = getString(R.string.prev_chapter); setOnClickListener { go(+1) } }
        nextBtn = Button(this).apply { text = getString(R.string.next_chapter); setOnClickListener { go(-1) } }
        val bar = LinearLayout(this).apply {
            addView(prevBtn, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(nextBtn, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        root.addView(errorBox, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(list, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
        setContentView(root)
        load()
    }

    /** The saved chapter list is newest first, so the NEXT chapter to read is the previous index. */
    private fun neighbour(delta: Int): com.openmanga.engine.Chapter? {
        val all = App.instance.readerChapters
        val i = all.indexOfFirst { it.url == chapterUrl }
        return if (i < 0) null else all.getOrNull(i + delta)
    }

    private fun go(delta: Int) {
        val ch = neighbour(delta) ?: return
        chapterUrl = ch.url
        chapterName = ch.name
        load()
    }

    private fun load() {
        title = listOf(mangaTitle, chapterName).filter { it.isNotBlank() }.joinToString(" — ")
        prevBtn.isEnabled = neighbour(+1) != null
        nextBtn.isEnabled = neighbour(-1) != null
        errorBox.visibility = View.GONE
        adapter.items = emptyList()
        val local = Files.chapterDir(this, source.id, mangaTitle, chapterName, chapterUrl)
        if (Files.isComplete(local)) {
            val files = Files.localPages(local)
            if (files.isNotEmpty()) { show(files.mapIndexed { i, f -> Page(i + 1, null, f, emptyMap()) }); return }
        }
        lifecycleScope.launch {
            val out = withContext(Dispatchers.IO) { SourceRunner.isolated(source, Stage.PAGES) { engine.pages(chapterUrl) } }
            diagnostics = out.diagnostics
            val pages = out.value
            if (out.ok && pages != null) show(pages.map { Page(it.index, it.url, null, it.headers) })
            else {
                errorText.text = Diag.summary(out.diagnostics)
                errorBox.visibility = View.VISIBLE
            }
        }
    }

    private fun show(pages: List<Page>) {
        total = pages.size
        adapter.items = pages
        list.scrollToPosition(0)
        supportActionBar?.subtitle = getString(R.string.page_of, 1, total)
    }

    private fun pageView(ctx: android.content.Context): View = FrameLayout(ctx).apply {
        layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
        addView(ImageView(ctx).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER }, FrameLayout.LayoutParams(MATCH, WRAP))
        addView(
            ctx.label("", 13f).apply { setPadding(ctx.dp(16), ctx.dp(40), ctx.dp(16), ctx.dp(40)); gravity = android.view.Gravity.CENTER; visibility = View.GONE },
            FrameLayout.LayoutParams(MATCH, WRAP),
        )
    }

    private fun bindPage(v: View, page: Page, position: Int, width: Int) {
        val box = v as FrameLayout
        val img = box.getChildAt(0) as ImageView
        val overlay = box.getChildAt(1) as TextView
        overlay.visibility = View.GONE
        val model: Any = page.file ?: run {
            val h = LazyHeaders.Builder()
            page.headers.forEach { (k, value) -> h.addHeader(k, value) }
            GlideUrl(page.url, h.build())
        }
        Glide.with(img).load(model).override(width, Target.SIZE_ORIGINAL)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(e: GlideException?, m: Any?, t: Target<Drawable>, first: Boolean): Boolean {
                    overlay.text = getString(R.string.page_failed, page.index)
                    overlay.visibility = View.VISIBLE
                    overlay.setOnClickListener { adapter.notifyItemChanged(position) }
                    return false
                }

                override fun onResourceReady(r: Drawable, m: Any, t: Target<Drawable>?, d: DataSource, first: Boolean): Boolean {
                    overlay.visibility = View.GONE
                    return false
                }
            })
            .into(img)
    }

    companion object {
        const val EXTRA_SOURCE = "source"
        const val EXTRA_URL = "url"
        const val EXTRA_NAME = "name"
        const val EXTRA_MANGA = "manga"
    }
}
