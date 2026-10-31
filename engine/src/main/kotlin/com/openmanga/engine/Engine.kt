package com.openmanga.engine

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** Shared HTTP helper: retries transient failures and turns every failure into a [Diagnostic]. */
object Net {
    fun get(
        fetcher: Fetcher,
        req: FetchRequest,
        stage: Stage,
        diags: MutableList<Diagnostic>,
        retries: Int = 2,
        retryDelayMs: Long = 700,
    ): FetchResponse? {
        var lastError: Diagnostic? = null
        for (attempt in 0..retries) {
            if (attempt > 0 && retryDelayMs > 0) Thread.sleep(retryDelayMs * attempt)
            try {
                val r = fetcher.execute(req)
                if (r.status in 200..299) {
                    val prot = if (r.contentType.contains("html", true) || r.contentType.isEmpty()) ProtectionDetector.detect(r) else null
                    if (prot != null && r.status != 200) { diags += prot.copy(stage = stage); return null }
                    if (prot != null && r.text.length < 20_000 && prot.code == "ANTI_BOT") { diags += prot.copy(stage = stage); return null }
                    return r
                }
                ProtectionDetector.detect(r)?.let { diags += it.copy(stage = stage); return null }
                if (r.status == 429 || r.status >= 500) {
                    lastError = Diagnostic(stage, Severity.ERROR, "HTTP_${r.status}", "Server answered HTTP ${r.status} after ${attempt + 1} attempt(s).", req.url)
                    continue
                }
                diags += Diagnostic(stage, Severity.ERROR, "HTTP_${r.status}", "HTTP ${r.status} for ${req.url}", req.url)
                return null
            } catch (e: java.io.IOException) {
                lastError = Diagnostic(stage, Severity.ERROR, "NETWORK", "Network error: ${e.javaClass.simpleName}: ${e.message}", req.url)
            }
        }
        lastError?.let { diags += it }
        return null
    }
}

/**
 * Runs one configured source. Every public call returns an [Outcome]; failures never throw, so one broken
 * source cannot take the others down.
 */
class SourceEngine(
    val config: SourceConfig,
    private val fetcher: Fetcher,
    private val retries: Int = 2,
    private val retryDelayMs: Long = 700,
) {
    fun popular(page: Int, query: String? = null) = listing(config.popular ?: config.latest, page, query, "popular")
    fun latest(page: Int) = listing(config.latest ?: config.popular, page, null, "latest")
    fun search(query: String, page: Int) = listing(config.search, page, query, "search")

    // ---------------------------------------------------------------- requests

    private fun vars(url: String, page: Int, query: String?): Map<String, String> = mapOf(
        "url" to url,
        "urlSlash" to (url.trimEnd('/') + "/"),
        "base" to config.baseUrl.trimEnd('/'),
        "page" to page.toString(),
        "query" to UrlUtil.encode(query ?: ""),
    )

    private fun expand(s: String, v: Map<String, String>): String {
        var out = s
        for ((k, value) in v) out = out.replace("{$k}", value)
        return out
    }

    private fun build(spec: RequestSpec, v: Map<String, String>, referer: String? = null): FetchRequest {
        val headers = LinkedHashMap<String, String>()
        headers["Referer"] = referer ?: (config.baseUrl.trimEnd('/') + "/")
        config.userAgent?.let { headers["User-Agent"] = it }
        headers.putAll(config.headers)
        headers.putAll(spec.headers.mapValues { expand(it.value, v) })
        val rawUrl = expand(spec.url, v)
        val url = UrlUtil.resolve(config.baseUrl.trimEnd('/') + "/", rawUrl) ?: rawUrl
        return FetchRequest(url, spec.method.uppercase(), headers, spec.body?.let { expand(it, v) }, config.fetchMode == FetchMode.WEBVIEW)
    }

    private fun fetch(req: FetchRequest, stage: Stage, diags: MutableList<Diagnostic>) =
        Net.get(fetcher, req, stage, diags, retries, retryDelayMs)

    private fun expandBase(s: String?): String? = s?.replace("{base}", config.baseUrl.trimEnd('/'))

    // ---------------------------------------------------------------- listings

    private fun listing(spec: ListingSpec?, page: Int, query: String?, kind: String): Outcome<ListingPage> {
        val diags = ArrayList<Diagnostic>()
        if (spec == null) {
            return Outcome(null, listOf(Diagnostic(Stage.LISTING, Severity.ERROR, "NOT_CONFIGURED", "This source has no '$kind' listing configured.")))
        }
        val pageValue = spec.startPage + (page - 1)
        val req = build(spec.request, vars(config.baseUrl, pageValue, query))
        val resp = fetch(req, Stage.LISTING, diags) ?: return Outcome(null, diags)

        val items: List<MangaItem>
        var hasNext: Boolean
        try {
            if (config.kind == SourceKind.JSON) {
                val root = try { Json.parse(resp.text) } catch (e: Exception) {
                    diags += Diagnostic(Stage.LISTING, Severity.ERROR, "PARSE_JSON", "The response is not valid JSON: ${e.message}", resp.url)
                    return Outcome(null, diags)
                }
                val nodes = jsonNodes(root, spec.item)
                if (nodes.isEmpty()) diags += Diagnostic(Stage.LISTING, Severity.ERROR, "NO_ITEMS", "JSON path '${spec.item.json}' matched nothing.", resp.url)
                items = nodes.mapNotNull { n ->
                    val title = Rules.jsonText(n, spec.title)
                    val link = UrlUtil.resolve(resp.url, expandBase(Rules.jsonText(n, spec.link)))
                    if (title == null || link == null) null else toItem(resp.url, title, link, Rules.jsonText(n, spec.cover), Rules.jsonText(n, spec.latestChapter), Rules.jsonText(n, spec.updated))
                }
                hasNext = items.isNotEmpty()
            } else {
                val doc = Jsoup.parse(resp.text, resp.url)
                val els = Rules.select(doc, spec.item)
                if (els.isEmpty()) {
                    diags += Diagnostic(Stage.LISTING, Severity.ERROR, "NO_ITEMS", "Item selector '${spec.item.css ?: spec.item.xpath}' matched 0 elements on ${resp.url}. The page may need JavaScript (use WebView mode) or the selector changed.", resp.url)
                }
                items = els.mapNotNull { el ->
                    val title = Rules.text(el, spec.title)
                    val link = UrlUtil.resolve(resp.url, expandBase(Rules.text(el, spec.link)))
                    if (title == null || link == null) null else toItem(resp.url, title, link, Rules.text(el, spec.cover), Rules.text(el, spec.latestChapter), Rules.text(el, spec.updated))
                }
                hasNext = if (spec.hasNext != null) Rules.select(doc, spec.hasNext).isNotEmpty() else items.isNotEmpty()
            }
        } catch (e: Exception) {
            diags += Diagnostic(Stage.LISTING, Severity.ERROR, "PARSE_ERROR", "Unexpected parsing failure: ${e.javaClass.simpleName}: ${e.message}", resp.url)
            return Outcome(null, diags)
        }
        val unique = items.distinctBy { it.url }
        if (unique.isEmpty() && diags.none { it.code == "NO_ITEMS" }) {
            diags += Diagnostic(Stage.LISTING, Severity.ERROR, "FIELDS_EMPTY", "Items were found but the title/link rules produced nothing.", resp.url)
        }
        val ordered = sortByUpdate(unique)
        return Outcome(ListingPage(ordered, hasNext && unique.isNotEmpty()), diags)
    }

    private fun jsonNodes(root: Any?, rule: Rule): List<Any?> {
        val hits = JsonPath.select(root, rule.json ?: return emptyList())
        return if (hits.size == 1 && hits[0] is List<*>) (hits[0] as List<*>) else hits
    }

    private fun toItem(base: String, title: String, link: String, cover: String?, latest: String?, updated: String?) =
        MangaItem(title, link, UrlUtil.resolve(base, expandBase(cover)), latest, updated, DateParser.parse(updated))

    /** Sorts by the real update time only when every item exposes one; otherwise keeps the site's own order. */
    private fun sortByUpdate(items: List<MangaItem>): List<MangaItem> =
        if (items.size > 1 && items.all { it.updatedAt != null }) items.sortedByDescending { it.updatedAt } else items

    // ---------------------------------------------------------------- details

    fun details(mangaUrl: String): Outcome<MangaDetails> {
        val diags = ArrayList<Diagnostic>()
        val spec = config.details
        val req = (spec.request?.let { build(it, vars(mangaUrl, 1, null)) } ?: build(RequestSpec("{url}"), vars(mangaUrl, 1, null)))
        val resp = fetch(req, Stage.DETAILS, diags) ?: return Outcome(null, diags)
        return try {
            val d: MangaDetails
            if (config.kind == SourceKind.JSON) {
                val root = Json.parse(resp.text)
                d = MangaDetails(
                    Rules.jsonText(root, spec.title), mangaUrl,
                    UrlUtil.resolve(resp.url, Rules.jsonText(root, spec.cover)),
                    Rules.jsonText(root, spec.description), Rules.jsonText(root, spec.author),
                    Rules.jsonAll(root, spec.genres), Rules.jsonText(root, spec.status),
                )
            } else {
                val doc = Jsoup.parse(resp.text, resp.url)
                d = MangaDetails(
                    Rules.text(doc, spec.title), mangaUrl,
                    UrlUtil.resolve(resp.url, Rules.text(doc, spec.cover)),
                    Rules.text(doc, spec.description), Rules.text(doc, spec.author),
                    Rules.all(doc, spec.genres), Rules.text(doc, spec.status),
                )
            }
            if (d.title == null && d.description == null && d.cover == null) {
                diags += Diagnostic(Stage.DETAILS, Severity.WARNING, "EMPTY_DETAILS", "No detail field matched; check the details selectors.", resp.url)
            }
            Outcome(d, diags)
        } catch (e: Exception) {
            diags += Diagnostic(Stage.DETAILS, Severity.ERROR, "PARSE_ERROR", "${e.javaClass.simpleName}: ${e.message}", resp.url)
            Outcome(null, diags)
        }
    }

    // ---------------------------------------------------------------- chapters (all of them)

    fun chapters(mangaUrl: String): Outcome<List<Chapter>> {
        val spec = config.chapters
        val diags = ArrayList<Diagnostic>()
        val collected = LinkedHashMap<String, Chapter>()
        val visited = HashSet<String>()
        var page = spec.startPage
        var nextUrl: String? = null
        var fetched = 0
        var duplicates = 0

        while (true) {
            val v = vars(mangaUrl, page, null)
            val req = when {
                nextUrl != null -> build(RequestSpec(nextUrl!!), v, mangaUrl)
                spec.request != null -> build(spec.request, v, mangaUrl)
                else -> build(RequestSpec("{url}"), v)
            }
            if (!visited.add(req.method + " " + req.url + " " + (req.body ?: ""))) break
            val resp = fetch(req, Stage.CHAPTERS, diags)
            if (resp == null) {
                if (collected.isEmpty()) return Outcome(null, diags)
                diags += Diagnostic(Stage.CHAPTERS, Severity.WARNING, "PARTIAL", "Stopped after ${collected.size} chapters because a page failed; the list may be incomplete.", req.url)
                break
            }
            var parsed = parseChapters(resp, spec, diags)
            if (parsed.isEmpty() && fetched == 0 && spec.ajaxRequest != null) {
                val ajax = fetch(build(spec.ajaxRequest, v, mangaUrl), Stage.CHAPTERS, diags)
                if (ajax != null) parsed = parseChapters(ajax, spec, diags)
            }
            fetched++
            var fresh = 0
            for (c in parsed) {
                if (collected.putIfAbsent(c.url, c) == null) fresh++ else duplicates++
            }
            when (spec.pagination) {
                PaginationMode.NONE -> break
                PaginationMode.PAGE_PARAM -> {
                    if (parsed.isEmpty() || fresh == 0) break
                    page++
                }
                PaginationMode.NEXT_LINK -> {
                    val doc = if (config.kind == SourceKind.HTML) Jsoup.parse(resp.text, resp.url) else null
                    nextUrl = doc?.let { UrlUtil.resolve(resp.url, Rules.text(it, spec.nextLink)) }
                    if (nextUrl == null || fresh == 0) break
                }
            }
            if (fetched >= spec.maxPages) {
                diags += Diagnostic(Stage.CHAPTERS, Severity.WARNING, "TRUNCATED", "Stopped at the safety limit of ${spec.maxPages} pages; more chapters may exist.", req.url)
                break
            }
        }
        if (collected.isEmpty()) {
            diags += Diagnostic(Stage.CHAPTERS, Severity.ERROR, "NO_CHAPTERS", "No chapters were found for $mangaUrl. Check the chapter selectors, the AJAX request, or whether the page needs JavaScript.", mangaUrl)
            return Outcome(null, diags)
        }
        if (duplicates > 0) diags += Diagnostic(Stage.CHAPTERS, Severity.INFO, "DUPLICATES_REMOVED", "$duplicates duplicate chapter link(s) removed.")
        val ordered = normalizeOrder(collected.values.toList(), spec.order)
        diags += Diagnostic(Stage.CHAPTERS, Severity.INFO, "CHAPTER_COUNT", "${ordered.size} chapters collected from $fetched request(s).")
        return Outcome(ordered, diags)
    }

    private fun parseChapters(resp: FetchResponse, spec: ChaptersSpec, diags: MutableList<Diagnostic>): List<Chapter> {
        return try {
            if (config.kind == SourceKind.JSON) {
                val root = Json.parse(resp.text)
                jsonNodes(root, spec.item).mapNotNull { n ->
                    val url = UrlUtil.resolve(resp.url, expandBase(Rules.jsonText(n, spec.url))) ?: return@mapNotNull null
                    val name = Rules.jsonText(n, spec.name) ?: return@mapNotNull null
                    val date = Rules.jsonText(n, spec.date)
                    Chapter(name, url, Rules.jsonText(n, spec.number)?.toDoubleOrNull() ?: ChapterNumber.parse(name), date, DateParser.parse(date))
                }
            } else {
                val doc = Jsoup.parse(resp.text, resp.url)
                Rules.select(doc, spec.item).mapNotNull { el ->
                    val url = UrlUtil.resolve(resp.url, expandBase(Rules.text(el, spec.url))) ?: return@mapNotNull null
                    val name = Rules.text(el, spec.name) ?: return@mapNotNull null
                    val date = Rules.text(el, spec.date)
                    Chapter(name, url, Rules.text(el, spec.number)?.toDoubleOrNull() ?: ChapterNumber.parse(name), date, DateParser.parse(date))
                }
            }
        } catch (e: Exception) {
            diags += Diagnostic(Stage.CHAPTERS, Severity.ERROR, "PARSE_ERROR", "${e.javaClass.simpleName}: ${e.message}", resp.url)
            emptyList()
        }
    }

    /** Output is always newest-first. [order] describes how the SITE lists chapters. */
    private fun normalizeOrder(list: List<Chapter>, order: ChapterOrder): List<Chapter> = when (order) {
        ChapterOrder.NEWEST_FIRST -> list
        ChapterOrder.OLDEST_FIRST -> list.reversed()
        ChapterOrder.AUTO -> {
            val nums = list.mapNotNull { it.number }
            if (nums.size >= 2 && nums.first() < nums.last()) list.reversed() else list
        }
    }

    // ---------------------------------------------------------------- pages (the reader)

    fun pages(chapterUrl: String): Outcome<List<PageImage>> {
        val diags = ArrayList<Diagnostic>()
        val spec = config.pages
        val req = spec.request?.let { build(it, vars(chapterUrl, 1, null), chapterUrl) } ?: build(RequestSpec("{url}"), vars(chapterUrl, 1, null), config.baseUrl.trimEnd('/') + "/")
        val resp = fetch(req, Stage.PAGES, diags) ?: return Outcome(null, diags)
        val urls: List<String>
        val strategy: String
        try {
            if (config.kind == SourceKind.JSON) {
                val root = Json.parse(resp.text)
                val rule = spec.json ?: spec.item
                urls = (if (rule?.json != null) Rules.jsonAll(root, rule) else emptyList())
                    .mapNotNull { UrlUtil.resolve(resp.url, expandBase(it)) }
                    .filter { !PageImageFilter.urlLooksBad(it, spec.excludeUrlContains) }.distinct()
                strategy = "json"
            } else {
                val doc: Document = Jsoup.parse(resp.text, resp.url)
                val r = PageExtractor.extract(doc, resp.url, spec)
                urls = r.urls
                strategy = r.strategy
            }
        } catch (e: Exception) {
            diags += Diagnostic(Stage.PAGES, Severity.ERROR, "PARSE_ERROR", "${e.javaClass.simpleName}: ${e.message}", resp.url)
            return Outcome(null, diags)
        }
        if (urls.isEmpty()) {
            diags += Diagnostic(Stage.PAGES, Severity.ERROR, "NO_PAGES", "No page images were found in the chapter (tried selector, embedded script data and structural heuristics). The reader may load images with JavaScript; try WebView mode or set a custom image selector.", resp.url)
            return Outcome(null, diags)
        }
        diags += Diagnostic(Stage.PAGES, Severity.INFO, "PAGES_STRATEGY", "${urls.size} page image(s) found using strategy '$strategy'.", resp.url)
        val imgHeaders = LinkedHashMap<String, String>()
        imgHeaders["Referer"] = chapterUrl
        config.userAgent?.let { imgHeaders["User-Agent"] = it }
        imgHeaders.putAll(config.headers)
        imgHeaders.putAll(spec.headers)
        return Outcome(urls.mapIndexed { i, u -> PageImage(i + 1, u, imgHeaders) }, diags)
    }

    /** Downloads one page and verifies that the bytes really are an image. */
    fun downloadPage(page: PageImage): Outcome<ByteArray> {
        val diags = ArrayList<Diagnostic>()
        val resp = fetch(FetchRequest(page.url, "GET", page.headers), Stage.IMAGE, diags) ?: return Outcome(null, diags)
        ImageValidator.validate(page.url, resp.bytes, resp.contentType)?.let { diags += it; return Outcome(null, diags) }
        return Outcome(resp.bytes, diags)
    }
}

/** Runs work across sources so that one failing source never affects the others. */
object SourceRunner {
    fun <T> isolated(config: SourceConfig, stage: Stage, block: () -> Outcome<T>): Outcome<T> = try {
        block()
    } catch (e: Throwable) {
        Outcome(null, listOf(Diagnostic(stage, Severity.ERROR, "UNEXPECTED", "Source '${config.name}' crashed: ${e.javaClass.simpleName}: ${e.message}")))
    }
}
