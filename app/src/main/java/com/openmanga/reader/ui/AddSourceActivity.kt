package com.openmanga.reader.ui

import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.switchmaterial.SwitchMaterial
import com.openmanga.engine.ConfigJson
import com.openmanga.engine.DiscoveryResult
import com.openmanga.engine.FetchMode
import com.openmanga.engine.Presets
import com.openmanga.engine.Severity
import com.openmanga.engine.SourceConfig
import com.openmanga.engine.SourceDiscovery
import com.openmanga.engine.UrlUtil
import com.openmanga.reader.App
import com.openmanga.reader.R
import com.openmanga.reader.util.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Add or edit a source from just a URL. "Auto-detect" learns the layout from the live site and proves it end to end;
 * everything it learned is shown as editable JSON so any site can be corrected by hand.
 */
class AddSourceActivity : AppCompatActivity() {
    private lateinit var nameEt: EditText
    private lateinit var urlEt: EditText
    private lateinit var logTv: TextView
    private lateinit var jsonEt: EditText
    private lateinit var headersEt: EditText
    private lateinit var webviewSw: SwitchMaterial
    private lateinit var presetSp: Spinner
    private lateinit var progress: ProgressBar
    private lateinit var advanced: LinearLayout
    private var verified = false
    private var editingId: String? = null
    private var lastUrl: String? = null

    private val presets = listOf("madara", "mangastream")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        editingId = intent.getStringExtra(EXTRA_ID)
        title = getString(if (editingId == null) R.string.add_source else R.string.edit_source)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        nameEt = EditText(this).apply { hint = getString(R.string.source_name); setSingleLine() }
        urlEt = EditText(this).apply {
            hint = getString(R.string.source_url) + " (https://…)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            textDirection = View.TEXT_DIRECTION_LTR
        }
        val discoverBtn = Button(this).apply { text = getString(R.string.discover); setOnClickListener { discover() } }
        progress = ProgressBar(this).apply { visibility = View.GONE; isIndeterminate = true }
        logTv = label("", 12f).apply { setTextIsSelectable(true); typeface = Typeface.MONOSPACE }

        val advToggle = label("▾ " + getString(R.string.advanced), 15f, bold = true).apply {
            setPadding(0, dp(16), 0, dp(8))
            setOnClickListener { advanced.visibility = if (advanced.visibility == View.GONE) View.VISIBLE else View.GONE }
        }
        presetSp = Spinner(this).apply { adapter = ArrayAdapter(this@AddSourceActivity, android.R.layout.simple_spinner_dropdown_item, presets) }
        val presetBtn = Button(this).apply { text = getString(R.string.apply_preset); setOnClickListener { applyPreset() } }
        webviewSw = SwitchMaterial(this).apply { text = getString(R.string.use_webview) }
        headersEt = EditText(this).apply {
            hint = getString(R.string.headers_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            textDirection = View.TEXT_DIRECTION_LTR
        }
        jsonEt = EditText(this).apply {
            hint = getString(R.string.json_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 10
            typeface = Typeface.MONOSPACE
            textSize = 11f
            textDirection = View.TEXT_DIRECTION_LTR
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        val applyJsonBtn = Button(this).apply { text = getString(R.string.apply_json); setOnClickListener { parseJson(true) } }
        val testBtn = Button(this).apply { text = getString(R.string.test_config); setOnClickListener { testCurrent() } }
        advanced = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(presetSp); addView(presetBtn); addView(webviewSw); addView(headersEt); addView(jsonEt); addView(applyJsonBtn); addView(testBtn)
        }
        val saveBtn = Button(this).apply { text = getString(R.string.save); setOnClickListener { save() } }

        listOf(nameEt, urlEt, discoverBtn, progress, logTv, advToggle, advanced, saveBtn).forEach { col.addView(it, LinearLayout.LayoutParams(MATCH, WRAP)) }
        val scroll = ScrollView(this).apply { addView(col); isFillViewport = true }
        scroll.applyBottomInsets()
        setContentView(scroll)

        editingId?.let { id ->
            App.instance.store.get(id)?.let { cfg ->
                load(cfg)
                urlEt.setText(cfg.baseUrl)
                verified = true
            }
        }
    }

    private fun load(cfg: SourceConfig) {
        if (nameEt.text.isBlank()) nameEt.setText(cfg.name)
        webviewSw.isChecked = cfg.fetchMode == FetchMode.WEBVIEW
        headersEt.setText(cfg.headers.entries.joinToString("\n") { "${it.key}: ${it.value}" })
        jsonEt.setText(ConfigJson.toJson(cfg))
    }

    private fun busy(on: Boolean) { progress.visibility = if (on) View.VISIBLE else View.GONE }

    private fun discover() {
        val base = UrlUtil.normalizeBase(urlEt.text.toString())
        if (base == null) { toast(getString(R.string.invalid_url)); return }
        lastUrl = base
        busy(true)
        logTv.text = getString(R.string.loading)
        val render = webviewSw.isChecked
        val name = nameEt.text.toString()
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) { SourceDiscovery(App.instance.fetcher, 1, 500, render).discover(base, name) }
            busy(false)
            show(res)
            res.config?.let { load(it) }
        }
    }

    private fun testCurrent() {
        val cfg = parseJson(false) ?: return
        busy(true)
        logTv.text = getString(R.string.loading)
        lastUrl = cfg.baseUrl
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) { SourceDiscovery(App.instance.fetcher, 1, 500, cfg.fetchMode == FetchMode.WEBVIEW).test(cfg) }
            busy(false)
            show(res)
        }
    }

    private fun show(res: DiscoveryResult) {
        verified = res.verified
        logTv.text = buildString {
            res.steps.forEach { appendLine("• $it") }
            appendLine()
            res.diagnostics.filter { it.severity != Severity.INFO || it.code == "VERIFIED" || it.code == "CHAPTER_COUNT" || it.code == "PAGES_STRATEGY" }
                .forEach { appendLine("[${it.severity}] ${Diag.explain(it)}\n") }
        }
        if (!res.verified && Diag.needsBrowser(res.diagnostics)) {
            val url = lastUrl
            if (url != null) showDiagnostics(res.diagnostics, url)
        }
    }

    private fun applyPreset() {
        val base = UrlUtil.normalizeBase(urlEt.text.toString())
        if (base == null) { toast(getString(R.string.invalid_url)); return }
        val name = nameEt.text.toString().ifBlank { UrlUtil.host(base) ?: base }
        val cfg = Presets.byName(presets[presetSp.selectedItemPosition], name, base) ?: return
        verified = false
        load(cfg.copy(fetchMode = if (webviewSw.isChecked) FetchMode.WEBVIEW else FetchMode.HTTP))
    }

    /** Reads the JSON editor; the name, WebView switch and headers fields override what the JSON says. */
    private fun parseJson(report: Boolean): SourceConfig? {
        val text = jsonEt.text.toString().trim()
        if (text.isEmpty()) { toast(getString(R.string.invalid_json, "empty")); return null }
        return try {
            var cfg = ConfigJson.fromJson(text)
            val headers = parseHeaders(headersEt.text.toString())
            cfg = cfg.copy(
                name = nameEt.text.toString().ifBlank { cfg.name },
                fetchMode = if (webviewSw.isChecked) FetchMode.WEBVIEW else FetchMode.HTTP,
                headers = if (headersEt.text.isNotBlank()) headers else cfg.headers,
            )
            if (report) toast("OK: ${cfg.name}")
            cfg
        } catch (e: Exception) {
            toast(getString(R.string.invalid_json, e.message ?: e.javaClass.simpleName))
            null
        }
    }

    private fun parseHeaders(text: String): Map<String, String> =
        text.lines().mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
        }.filter { it.first.isNotEmpty() }.toMap()

    private fun save() {
        var cfg = parseJson(false) ?: return
        val store = App.instance.store
        cfg = if (editingId != null) cfg.copy(id = editingId!!)
        else if (store.get(cfg.id) != null) cfg.copy(id = cfg.id + "-" + (System.currentTimeMillis() % 10000))
        else cfg
        val commit = { store.save(cfg); toast(getString(R.string.saved)); finish() }
        if (!verified && editingId == null) {
            AlertDialog.Builder(this)
                .setTitle(R.string.save_unverified_title)
                .setMessage(R.string.save_unverified_msg)
                .setPositiveButton(R.string.save) { _, _ -> commit() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else commit()
    }

    companion object {
        const val EXTRA_ID = "id"
    }
}
