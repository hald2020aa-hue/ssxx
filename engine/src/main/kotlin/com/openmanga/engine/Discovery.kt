package com.openmanga.engine

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class DiscoveryResult(
    /** The best configuration found. When [verified] is false it is a starting point to refine in advanced settings. */
    val config: SourceConfig?,
    val verified: Boolean,
    val diagnostics: List<Diagnostic>,
    val steps: List<String>,
)

/**
 * Learns how a site is laid out from its live pages and proves it end to end
 * (listing -> manga -> chapters -> page images) before declaring success.
 */
class SourceDiscovery(
    private val fetcher: Fetcher,
    private val retries: Int = 1,
    private val retryDelayMs: Long = 500,
    /** True for sites that only produce content with JavaScript: every request goes through the app's WebView. */
    private val render: Boolean = false,
) {
    private class Check(val passed: Boolean, val diagnostics: List<Diagnostic>, val summary: String)

    fun discover(input: String, displayName: String? = null): DiscoveryResult {
        val diags = ArrayList<Diagnostic>()
        val steps = ArrayList<String>()
        val base0 = UrlUtil.normalizeBase(input)
            ?: return DiscoveryResult(null, false, listOf(Diagnostic(Stage.DISCOVERY, Severity.ERROR, "BAD_URL", "'$input' is not a valid website address.")), steps)
        val resp = Net.get(fetcher, FetchRequest("$base0/", render = render), Stage.DISCOVERY, diags, retries, retryDelayMs)
            ?: return DiscoveryResult(null, false, diags, steps)
        val origin = UrlUtil.origin(resp.url) ?: base0
        val name = displayName?.takeIf { it.isNotBlank() } ?: (UrlUtil.host(origin) ?: origin)
        steps += "Fetched ${resp.url} (${resp.bytes.size} bytes)"
        if (resp.contentType.contains("json", true)) {
            diags += Diagnostic(Stage.DISCOVERY, Severity.WARNING, "JSON_ROOT", "The address returns JSON. Automatic discovery works on HTML sites; define the JSON paths in the advanced settings.", resp.url)
            return DiscoveryResult(null, false, diags, steps)
        }
        val home = Jsoup.parse(resp.text, resp.url)
        val lower = resp.text.lowercase()

        val candidates = ArrayList<SourceConfig>()
        if (lower.contains("wp-manga") || lower.contains("madara")) candidates += Presets.madara(name, origin)
        if (lower.contains("ts_reader") || lower.contains("themesia") || lower.contains("listupd")) candidates += Presets.mangastream(name, origin)
        val generic = buildGeneric(name, origin, home, steps, diags)
        if (generic != null) candidates += generic

        if (candidates.isEmpty()) {
            diags += Diagnostic(Stage.DISCOVERY, Severity.ERROR, "NO_LAYOUT", "Could not recognise a list of works on the homepage. The page may be rendered with JavaScript (WebView mode) or use a layout that needs manual selectors.", resp.url)
            return DiscoveryResult(null, false, diags, steps)
        }
        var partial: SourceConfig? = null
        val failures = ArrayList<Diagnostic>()
        for (raw in candidates) {
            val cand = if (render) raw.copy(fetchMode = FetchMode.WEBVIEW) else raw
            val label = cand.preset ?: "generic"
            val check = verify(cand)
            steps += "Candidate '$label': ${check.summary}"
            if (check.passed) {
                diags += check.diagnostics
                return DiscoveryResult(cand, true, diags + Diagnostic(Stage.DISCOVERY, Severity.INFO, "VERIFIED", "Layout '$label' verified end to end. ${check.summary}"), steps)
            }
            if (partial == null) partial = cand
            failures += check.diagnostics.filter { it.severity == Severity.ERROR }.map { it.copy(message = "[$label] ${it.message}") }
        }
        diags += failures
        diags += Diagnostic(Stage.DISCOVERY, Severity.ERROR, "NOT_VERIFIED", "A layout was guessed but it did not pass verification. The configuration is saved as a draft; refine the selectors in advanced settings.", resp.url)
        return DiscoveryResult(partial, false, diags, steps)
    }

    /** Runs the same end-to-end check on a hand-edited configuration (used by the "test" button). */
    fun verifyConfig(cfg: SourceConfig): DiscoveryResult {
        val check = verify(cfg)
        val code = if (check.passed) "VERIFIED" else "NOT_VERIFIED"
        val diags = check.diagnostics + Diagnostic(Stage.DISCOVERY, if (check.passed) Severity.INFO else Severity.ERROR, code, check.summary)
        return DiscoveryResult(cfg, check.passed, diags, listOf(check.summary))
    }

    // ------------------------------------------------------------------ verification

    /** Runs listing -> chapters -> page images on an existing (possibly hand-edited) configuration. */
    fun test(config: SourceConfig): DiscoveryResult {
        val check = verify(config)
        return DiscoveryResult(config, check.passed, check.diagnostics, listOf(check.summary))
    }

    private fun verify(cand: SourceConfig): Check {
        val engine = SourceEngine(cand, fetcher, retries, retryDelayMs)
        val all = ArrayList<Diagnostic>()
        val listing = engine.latest(1)
        all += listing.diagnostics
        val items = listing.value?.items.orEmpty()
        if (!listing.ok || items.isEmpty()) return Check(false, all, "listing failed")

        var chapters: List<Chapter>? = null
        var chosen: MangaItem? = null
        for (item in items.take(3)) {
            val ch = engine.chapters(item.url)
            if (ch.ok && ch.value!!.isNotEmpty()) { chapters = ch.value; chosen = item; all += ch.diagnostics; break }
            all += ch.diagnostics
        }
        if (chapters == null) return Check(false, all, "listing ok (${items.size} items) but no chapters found")

        for (chapter in chapters.take(2)) {
            val pg = engine.pages(chapter.url)
            all += pg.diagnostics
            if (pg.ok && pg.value!!.isNotEmpty()) {
                return Check(true, all, "${items.size} items, ${chapters.size} chapters in '${chosen!!.title}', ${pg.value.size} page images")
            }
        }
        return Check(false, all, "chapters ok (${chapters.size}) but no page images found")
    }

    // ------------------------------------------------------------------ generic layout learning

    private fun cssPart(e: Element): String {
        val id = e.id()
        if (id.isNotEmpty() && id.none { it.isDigit() }) return "${e.tagName()}#$id"
        val cls = e.classNames().filter { c -> c.isNotEmpty() && c.none { it.isDigit() } }.take(2)
        return e.tagName() + cls.joinToString("") { ".$it" }
    }

    private fun cssFor(e: Element): String {
        val p = e.parent()
        return if (p != null && p.tagName() != "body" && p.tagName() != "html") "${cssPart(p)} > ${cssPart(e)}" else cssPart(e)
    }

    private fun bestRule(items: List<Element>, candidates: List<Rule>, distinct: Boolean): Rule? {
        var best: Rule? = null
        var bestScore = 0
        for (r in candidates) {
            val values = items.take(8).mapNotNull { Rules.text(it, r) }
            val score = if (distinct) values.distinct().size else values.size
            if (score > bestScore) { best = r; bestScore = score }
        }
        return best
    }

    private fun buildGeneric(name: String, origin: String, home: Document, steps: MutableList<String>, diags: MutableList<Diagnostic>): SourceConfig? {
        val body = home.body() ?: return null
        // 1. repeated blocks that contain a link and an image
        val groups = LinkedHashMap<String, MutableList<Element>>()
        for (img in body.select("img")) {
            if (PageImageFilter.inBadContainer(img)) continue
            var e: Element? = img
            var depth = 0
            while (e != null && depth < 5 && e.tagName() != "body") {
                val parent = e.parent()
                val siblings = parent?.children()?.count { it.tagName() == e!!.tagName() } ?: 0
                if (parent != null && siblings >= 5 && e.select("a[href]").isNotEmpty() && e.tagName() != "a") {
                    groups.getOrPut(cssFor(e)) { ArrayList() }.add(e)
                    break
                }
                e = parent
                depth++
            }
        }
        val itemCss = groups.entries.filter { it.value.size >= 5 }.maxByOrNull { it.value.size }?.key
        if (itemCss == null) {
            steps += "No repeated work cards found on the homepage"
            return null
        }
        val items = home.select(itemCss)
        steps += "Work cards: '$itemCss' (${items.size} found)"

        val title = bestRule(items, listOf(
            Rule(css = "a[title]", attr = "title"), Rule(css = "h1, h2, h3, h4, h5, h6"),
            Rule(css = "img", attr = "alt"), Rule(css = "a"),
        ), false) ?: return null
        val link = bestRule(items, listOf(
            Rule(css = "h1 a, h2 a, h3 a, h4 a, h5 a, h6 a", attr = "href"), Rule(css = "a[href]:has(img)", attr = "href"), Rule(css = "a[href]", attr = "href"),
        ), true) ?: return null
        val cover = Rule(css = "img", attrs = DEFAULT_IMAGE_ATTRS)

        // 2. pagination template from page links such as /page/2/ or ?page=2
        val paginationHref = home.select("a[href]").map { it.attr("href") }.firstOrNull { Regex("([?&](page|paged|p)=2(&|$))|(/page/2(/|$|\\?))").containsMatchIn(it) }
        val resolved = paginationHref?.let { UrlUtil.resolve(origin + "/", it) }
        val template = resolved?.replace(Regex("([?&](?:page|paged|p)=)2"), "$1{page}")?.replace(Regex("(/page/)2"), "$1{page}")
        val request = if (template != null && template.contains("{page}")) RequestSpec(template) else RequestSpec("$origin/")
        val hasNext = if (template != null) null else Rule(css = "#__never__")
        steps += if (template != null) "Pagination template: $template" else "No pagination links found; the listing is a single page"

        val latest = ListingSpec(request, Rule(css = itemCss), title, link, cover, hasNext = hasNext)
        val search = searchFrom(home, origin, itemCss, title, link, cover)

        // 3. chapter layout, learned from the first work page
        val firstLinks = items.take(3).mapNotNull { UrlUtil.resolve(origin + "/", Rules.text(it, link)) }
        var chaptersSpec: ChaptersSpec? = null
        for (url in firstLinks) {
            val tmp = ArrayList<Diagnostic>()
            val r = Net.get(fetcher, FetchRequest(url, render = render), Stage.DISCOVERY, tmp, retries, retryDelayMs) ?: continue
            chaptersSpec = learnChapters(Jsoup.parse(r.text, r.url), steps)
            if (chaptersSpec != null) break
        }
        if (chaptersSpec == null) {
            diags += Diagnostic(Stage.DISCOVERY, Severity.WARNING, "NO_CHAPTER_LAYOUT", "Work cards were found but no chapter list could be recognised on the work pages.")
            chaptersSpec = ChaptersSpec(item = Rule(css = "a"), name = Rule(), url = Rule(attr = "href"))
        }
        val details = DetailsSpec(
            title = Rule(css = "h1"),
            cover = Rule(css = "meta[property=og:image]", attr = "content"),
            description = Rule(css = "meta[name=description]", attr = "content"),
        )
        return SourceConfig(
            id = ConfigJson.slug(UrlUtil.host(origin) ?: name), name = name, baseUrl = origin, preset = "generic",
            latest = latest, search = search, details = details, chapters = chaptersSpec, pages = PagesSpec(),
        )
    }

    private fun searchFrom(home: Document, origin: String, itemCss: String, title: Rule, link: Rule, cover: Rule): ListingSpec? {
        val form = home.select("form").firstOrNull { f -> f.select("input[type=search], input[name=s], input[name=q], input[name=search], input[name=keyword]").isNotEmpty() } ?: return null
        val input = form.select("input[type=search], input[name=s], input[name=q], input[name=search], input[name=keyword]").first() ?: return null
        val field = input.attr("name").ifEmpty { return null }
        val action = UrlUtil.resolve(origin + "/", form.attr("action").ifEmpty { "/" }) ?: return null
        val sep = if (action.contains("?")) "&" else "?"
        val hidden = form.select("input[type=hidden][name]").joinToString("") { "&" + UrlUtil.encode(it.attr("name")) + "=" + UrlUtil.encode(it.attr("value")) }
        return ListingSpec(RequestSpec("$action$sep$field={query}$hidden"), Rule(css = itemCss), title, link, cover, hasNext = Rule(css = "#__never__"))
    }

    private val chapterHref = Regex("(chapter|chap|/ch[-_/]?\\d|episode|/ep[-_]?\\d|/read|/c/\\d)", RegexOption.IGNORE_CASE)
    private val chapterText = Regex("(chapter|ch\\.|episode|الفصل|فصل|الحلقة|حلقة)\\s*\\d|^\\s*\\d+(\\.\\d+)?\\s*$", RegexOption.IGNORE_CASE)

    private fun learnChapters(doc: Document, steps: MutableList<String>): ChaptersSpec? {
        val anchors = doc.select("a[href]").filter { a ->
            val href = a.attr("href")
            val text = a.text()
            (chapterHref.containsMatchIn(href) || chapterText.containsMatchIn(text)) && (href.any { it.isDigit() } || text.any { it.isDigit() }) &&
                !PageImageFilter.inBadContainer(a)
        }
        if (anchors.size < 3) return null
        val byAnchor = anchors.groupBy { cssFor(it) }
        val byRow = anchors.mapNotNull { a ->
            val p = a.parent() ?: return@mapNotNull null
            if (p.tagName() == "body") null else p
        }.groupBy { cssFor(it) }.filterValues { it.size >= 3 }
        val bestAnchor = byAnchor.entries.maxByOrNull { it.value.size }
        val bestRow = byRow.entries.maxByOrNull { it.value.size }
        val dateRule = Rule(css = "time, [class*=date], [class*=time], [class*=release], [class*=ago]")

        if (bestRow != null && (bestAnchor == null || bestRow.value.size >= bestAnchor.value.size)) {
            val rows = doc.select(bestRow.key)
            val hasDates = rows.take(5).any { Rules.text(it, dateRule) != null }
            steps += "Chapter rows: '${bestRow.key}' (${rows.size} found)"
            return ChaptersSpec(
                item = Rule(css = bestRow.key), name = Rule(css = "a[href]"), url = Rule(css = "a[href]", attr = "href"),
                date = if (hasDates) dateRule else null,
            )
        }
        if (bestAnchor != null && bestAnchor.value.size >= 3) {
            steps += "Chapter links: '${bestAnchor.key}' (${bestAnchor.value.size} found)"
            return ChaptersSpec(item = Rule(css = bestAnchor.key), name = Rule(), url = Rule(attr = "href"))
        }
        return null
    }
}
