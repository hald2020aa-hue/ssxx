package com.openmanga.reader.ui

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.openmanga.engine.Diagnostic
import com.openmanga.engine.ListingPage
import com.openmanga.engine.MangaItem
import com.openmanga.engine.Outcome
import com.openmanga.engine.SourceConfig
import com.openmanga.engine.SourceEngine
import com.openmanga.engine.SourceRunner
import com.openmanga.engine.Stage
import com.openmanga.reader.App
import com.openmanga.reader.R
import com.openmanga.reader.util.Diag
import com.openmanga.reader.util.formatTime
import com.openmanga.reader.util.isOnline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Latest updates (or search results) of ONE source. A failing source only affects its own screen. */
class BrowseActivity : AppCompatActivity() {
    private lateinit var source: SourceConfig
    private lateinit var engine: SourceEngine
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var grid: RecyclerView
    private lateinit var banner: TextView
    private lateinit var errorBox: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var adapter: SimpleAdapter<MangaItem>

    private val items = ArrayList<MangaItem>()
    private var page = 1
    private var hasNext = true
    private var loading = false
    private var query: String? = null
    private var lastDiagnostics: List<Diagnostic> = emptyList()
    private var job: Job? = null

    private val kind get() = if (query == null) "latest" else "search:$query"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_SOURCE)
        val cfg = id?.let { App.instance.store.get(it) }
        if (cfg == null) { finish(); return }
        source = cfg
        engine = SourceEngine(cfg, App.instance.fetcher)
        title = cfg.name
        updateSubtitle()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.applyBottomInsets()
        banner = label("", 12f).apply { setPadding(dp(12), dp(8), dp(12), dp(8)); setBackgroundColor(0x33FFC107); visibility = View.GONE }
        errorText = label("", 14f).apply { setPadding(dp(24), dp(24), dp(24), dp(12)) }
        errorBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(errorText)
            addView(android.widget.Button(this@BrowseActivity).apply { text = getString(R.string.retry); setOnClickListener { reload() } })
            addView(android.widget.Button(this@BrowseActivity).apply { text = getString(R.string.details); setOnClickListener { showDiagnostics(lastDiagnostics, source.baseUrl) } })
        }
        adapter = SimpleAdapter(make = { ctx -> cell(ctx) }, bind = { v, item, _ -> bindCell(v, item) })
        val lm = GridLayoutManager(this, 3)
        grid = RecyclerView(this).apply {
            layoutManager = lm
            adapter = this@BrowseActivity.adapter
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    if (!loading && hasNext && lm.findLastVisibleItemPosition() >= items.size - 6 && items.isNotEmpty()) load(false)
                }
            })
        }
        swipe = SwipeRefreshLayout(this).apply { addView(grid, FrameLayout.LayoutParams(MATCH, MATCH)); setOnRefreshListener { reload() } }
        root.addView(banner, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(errorBox, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(swipe, LinearLayout.LayoutParams(MATCH, 0, 1f))
        setContentView(root)
        reload()
    }

    private fun cell(ctx: android.content.Context): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
        setPadding(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(8))
        addView(ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0x22888888) }, LinearLayout.LayoutParams(MATCH, ctx.dp(170)))
        addView(ctx.label("", 12f, bold = true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        addView(ctx.label("", 11f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
    }

    private fun bindCell(v: View, item: MangaItem) {
        val box = v as LinearLayout
        val img = box.getChildAt(0) as ImageView
        (box.getChildAt(1) as TextView).text = item.title
        (box.getChildAt(2) as TextView).text = listOfNotNull(item.latestChapter, item.updatedText).joinToString(" • ")
        if (item.cover != null) {
            val headers = LazyHeaders.Builder().addHeader("Referer", source.baseUrl.trimEnd('/') + "/")
            source.headers.forEach { (k, value) -> headers.addHeader(k, value) }
            Glide.with(img).load(GlideUrl(item.cover, headers.build())).into(img)
        } else img.setImageDrawable(null)
        box.setOnClickListener {
            startActivity(
                Intent(this, MangaActivity::class.java)
                    .putExtra(MangaActivity.EXTRA_SOURCE, source.id).putExtra(MangaActivity.EXTRA_URL, item.url)
                    .putExtra(MangaActivity.EXTRA_TITLE, item.title).putExtra(MangaActivity.EXTRA_COVER, item.cover),
            )
        }
    }

    private fun reload() {
        job?.cancel()
        loading = false
        page = 1
        hasNext = true
        load(true)
    }

    private fun load(reset: Boolean) {
        if (loading) return
        loading = true
        val myPage = page
        val myKind = kind
        val q = query
        swipe.isRefreshing = true
        job = lifecycleScope.launch {
            val out: Outcome<ListingPage> = withContext(Dispatchers.IO) {
                SourceRunner.isolated(source, Stage.LISTING) { if (q == null) engine.latest(myPage) else engine.search(q, myPage) }
            }
            if (myKind != kind) return@launch // the user changed the query meanwhile
            loading = false
            swipe.isRefreshing = false
            lastDiagnostics = out.diagnostics
            val value = out.value
            if (out.ok && value != null) {
                if (reset) items.clear()
                val known = items.map { it.url }.toHashSet()
                items.addAll(value.items.filter { known.add(it.url) })
                hasNext = value.hasNext
                page = myPage + 1
                App.instance.cache.saveListing(source.id, myKind, myPage, value)
                if (q == null && myPage == 1) getSharedPreferences("sources", MODE_PRIVATE).edit().putLong("ok_${source.id}", System.currentTimeMillis()).apply()
                banner.visibility = View.GONE
                errorBox.visibility = View.GONE
                adapter.items = items.toList()
                updateSubtitle()
            } else {
                showFailure(out, myPage, myKind, reset)
            }
        }
    }

    private fun showFailure(out: Outcome<ListingPage>, myPage: Int, myKind: String, reset: Boolean) {
        val reason = if (!isOnline(this)) getString(R.string.offline) else Diag.summary(out.diagnostics)
        val cached = App.instance.cache.loadListing(source.id, myKind, myPage)
        if (cached != null && reset) {
            // show the last successful data, clearly labelled as stored data
            items.clear()
            items.addAll(cached.value.items)
            hasNext = false
            adapter.items = items.toList()
            banner.text = getString(R.string.cached_banner, formatTime(cached.savedAt)) + "\n" + reason
            banner.visibility = View.VISIBLE
            errorBox.visibility = View.GONE
        } else if (items.isEmpty()) {
            errorText.text = reason
            errorBox.visibility = View.VISIBLE
        } else {
            toast(reason)
        }
    }

    private fun updateSubtitle() {
        val t = getSharedPreferences("sources", MODE_PRIVATE).getLong("ok_${source.id}", 0L)
        supportActionBar?.subtitle = if (t > 0) getString(R.string.last_success, formatTime(t)) else null
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, R.string.refresh).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        val search = menu.add(0, 2, 1, R.string.search)
        search.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM or MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW)
        val sv = SearchView(this)
        search.actionView = sv
        sv.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(text: String): Boolean {
                if (source.search == null) { toast(getString(R.string.no_search)); return true }
                query = text.trim().ifEmpty { null }
                items.clear(); adapter.items = emptyList()
                reload()
                sv.clearFocus()
                return true
            }

            override fun onQueryTextChange(newText: String) = false
        })
        search.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem) = true
            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                if (query != null) { query = null; items.clear(); adapter.items = emptyList(); reload() }
                return true
            }
        })
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) { reload(); return true }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        const val EXTRA_SOURCE = "source"
    }
}
