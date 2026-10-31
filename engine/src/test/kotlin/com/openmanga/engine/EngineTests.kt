package com.openmanga.engine

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * End-to-end tests. Every "site" below is a real HTTP server on localhost, so the engine's networking,
 * retry, parsing, pagination and image-validation code all run for real. No external website is contacted.
 */
class Resp(val status: Int, val type: String, val bytes: ByteArray) {
    constructor(status: Int, type: String, text: String) : this(status, type, text.toByteArray())
}

class Site(private val handler: (method: String, path: String, query: String, body: String) -> Resp) {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val hits = AtomicInteger()
    val origin: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { ex ->
            hits.incrementAndGet()
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val r = try {
                handler(ex.requestMethod, ex.requestURI.path, ex.requestURI.rawQuery ?: "", body)
            } catch (e: Exception) {
                Resp(500, "text/plain", e.toString())
            }
            ex.responseHeaders.add("Content-Type", r.type)
            ex.sendResponseHeaders(r.status, r.bytes.size.toLong())
            ex.responseBody.use { it.write(r.bytes) }
        }
        server.executor = Executors.newFixedThreadPool(4)
        server.start()
    }

    fun stop() = server.stop(0)
}

val JPEG = byteArrayOf(
    0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10, 'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(),
    0, 1, 1, 0, 0, 1, 0, 1, 0, 0, 0xFF.toByte(), 0xD9.toByte(),
)

fun html(body: String) = Resp(200, "text/html; charset=utf-8", "<!doctype html><html><head><title>t</title></head><body>$body</body></html>")
fun jpeg() = Resp(200, "image/jpeg", JPEG)
fun json(s: String) = Resp(200, "application/json", s)

var passed = 0
var failed = 0
val failures = ArrayList<String>()

fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) { passed++; println("  PASS  $name") } else { failed++; failures += name; println("  FAIL  $name  $detail") }
}

fun section(title: String) = println("\n== $title")

// ---------------------------------------------------------------------------------------------- site fixtures

/** A site with an unknown layout: cards, /page/N/ pagination, lazy images, and lots of non-page images. */
fun siteGeneric(): Site = Site { method, path, query, _ ->
    fun cards(r: IntRange) = r.joinToString("") { n ->
        """<div class="card"><a href="/m/$n"><img data-src="/covers/$n.jpg" src="data:image/gif;base64,R0lGODlhAQABAAAAACw="></a><h3><a href="/m/$n">Title $n</a></h3></div>"""
    }
    fun home(r: IntRange) = html(
        """<header class="site-header"><img src="/static/logo.png" width="200" height="60"></header>
           <div class="cards">${cards(r)}</div>
           <nav class="pagination"><a href="/page/2/">2</a> <a href="/page/3/">3</a></nav>
           <form action="/search"><input type="search" name="q"></form>""",
    )
    when {
        path == "/" || path == "/page/1/" -> home(1..8)
        path == "/page/2/" -> home(9..11)
        path == "/page/3/" -> html("""<div class="cards"></div>""")
        path == "/search" -> {
            val q = URLDecoder.decode(query.substringAfter("q="), "UTF-8")
            html("""<div class="cards">${(1..11).filter { "Title $it".contains(q, true) }.joinToString("") { cards(it..it) }}</div>""")
        }
        Regex("/m/(\\d+)").matches(path) -> {
            val n = Regex("/m/(\\d+)").matchEntire(path)!!.groupValues[1]
            val rows = (30 downTo 1).joinToString("") { c -> """<li><a href="/m/$n/c/$c">Chapter $c</a><span class="date">2026-10-${"%02d".format(minOf(c, 28))}</span></li>""" }
            html("""<h1>Title $n</h1><meta name="description" content="About $n"><ul class="chapters">$rows</ul>""")
        }
        Regex("/m/\\d+/c/(\\d+)").matches(path) -> {
            val pages = (1..12).joinToString("") { p ->
                val pp = "%02d".format(p)
                when (p) {
                    1 -> """<div class="pg first"><img src="/img/p$pp.jpg"></div>"""
                    5 -> """<div class="pg"><img data-lazy-src="/img/p$pp.jpg" src="/static/loading.gif"></div>"""
                    else -> """<div class="pg"><img data-src="/img/p$pp.jpg" src="data:image/gif;base64,R0lGODlhAQABAAAAACw="></div>"""
                }
            }
            html(
                """<header class="site-header"><img src="/static/logo.png"></header>
                   <div class="reader">$pages</div>
                   <div class="comments"><img class="avatar" src="/avatars/u1.png"><img src="/static/emoji/smile.png"></div>
                   <div class="ad-slot"><img src="/ads/banner1.jpg"></div>
                   <footer><img src="/static/footer.jpg"></footer>""",
            )
        }
        path.startsWith("/img/") && path.endsWith("broken.jpg") -> html("<p>Not found</p>")
        path.startsWith("/img/") || path.startsWith("/covers/") -> jpeg()
        else -> Resp(404, "text/plain", "nope")
    }
}

/** Chapters spread over 3 pages (ascending order, one duplicate row across pages). Also a looping variant. */
fun siteChapters(): Site = Site { _, path, query, _ ->
    val page = Regex("cpage=(\\d+)").find(query)?.groupValues?.get(1)?.toInt() ?: 1
    fun rows(range: IntRange) = range.joinToString("") { c -> """<li class="ch"><a href="/read/$c">Chapter $c</a><span>2026-01-01</span></li>""" }
    when (path) {
        "/series/x" -> when (page) {
            1 -> html("<ul>${rows(1..40)}</ul>")
            2 -> html("<ul>${rows(40..80)}</ul>")
            3 -> html("<ul>${rows(81..120)}</ul>")
            else -> html("<ul></ul>")
        }
        "/series/loop" -> html("<ul>${rows(1..40)}</ul>") // ignores the page number
        else -> Resp(404, "text/plain", "nope")
    }
}

fun siteJson(): Site = Site { _, path, query, _ ->
    val page = Regex("page=(\\d+)").find(query)?.groupValues?.get(1)?.toInt() ?: 1
    when {
        path == "/api/latest" -> {
            val dates = listOf("2026-10-05", "2026-10-09", "2026-10-01", "2026-10-07", "2026-10-03", "2026-10-08", "2026-10-02", "2026-10-06")
            val ids = when (page) { 1 -> 1..5; 2 -> 6..8; else -> IntRange.EMPTY }
            json("""{"data":{"items":[${ids.joinToString(",") { """{"id":$it,"name":"JSON Manga $it","cover":"/covers/$it.jpg","updated":"${dates[it - 1]}"}""" }}]}}""")
        }
        Regex("/api/manga/(\\d+)").matches(path) -> json("""{"data":{"title":"JSON Manga","description":"A json described work","author":"Ann","genres":["Action","Drama"],"cover":"/covers/1.jpg"}}""")
        Regex("/api/manga/\\d+/chapters").matches(path) -> {
            val range = when (page) { 1 -> 1..25; 2 -> 26..50; 3 -> 51..60; else -> IntRange.EMPTY }
            json("""{"data":{"chapters":[${range.joinToString(",") { """{"id":$it,"title":"Chapter $it","url":"/read/1/$it","date":"$it days ago"}""" }}]}}""")
        }
        path.startsWith("/read/") -> json("""{"images":["/img/j1.jpg","/img/j2.jpg","/img/j3.jpg","/img/j4.jpg"]}""")
        path.startsWith("/img/") || path.startsWith("/covers/") -> jpeg()
        else -> Resp(404, "text/plain", "nope")
    }
}

/** Madara-style site: chapters are only available through an AJAX POST. */
fun siteMadara(): Site = Site { method, path, _, _ ->
    when {
        path == "/" -> html("""<div class="wp-manga">Madara</div>""")
        path == "/manga/page/1/" -> html(
            (1..6).joinToString("") { n ->
                """<div class="page-item-detail"><div class="item-thumb"><img data-src="/covers/$n.jpg"></div><div class="item-summary"><h3 class="h5"><a href="/manga/slug-$n/">Madara Work $n</a></h3><span class="post-on">$n hours ago</span></div></div>"""
            },
        )
        Regex("/manga/slug-\\d+/").matches(path) && method == "GET" -> html("""<div class="post-title"><h1>Madara Work</h1></div><div id="manga-chapters-holder"></div>""")
        Regex("/manga/slug-\\d+/ajax/chapters/").matches(path) && method == "POST" -> html(
            "<ul>" + (3 downTo 1).joinToString("") { c ->
                """<li class="wp-manga-chapter"><a href="/manga/slug-1/chapter-$c/">Chapter $c</a><span class="chapter-release-date"><i>March $c, 2026</i></span></li>"""
            } + "</ul>",
        )
        Regex("/manga/slug-\\d+/chapter-\\d+/").matches(path) -> html(
            """<div class="reading-content">
               <img src="https://s.w.org/images/core/emoji/14/72x72/1f600.png">
               ${(1..10).joinToString("") { p -> """<div class="page-break"><img data-src="/img/m$p.jpg" class="wp-manga-chapter-img"></div>""" }}
               </div>""",
        )
        path.startsWith("/img/") || path.startsWith("/covers/") -> jpeg()
        else -> Resp(404, "text/plain", "nope")
    }
}

/** MangaStream-style site: page images exist only inside an inline script (ts_reader.run). */
fun siteStream(): Site = Site { _, path, _, _ ->
    when {
        path == "/" -> html("""<div class="listupd"></div>""")
        path == "/manga/" -> html("""<div class="listupd">${(1..6).joinToString("") { n -> """<div class="bsx"><a href="/series-$n/" title="Stream Work $n"><img src="/covers/$n.jpg"></a><div class="epxs">Chapter $n</div></div>""" }}</div>""")
        Regex("/series-\\d+/").matches(path) -> html(
            """<h1 class="entry-title">Stream Work</h1><div id="chapterlist"><ul>${(3 downTo 1).joinToString("") { c ->
                """<li><a href="/chapter-$c/"><span class="chapternum">Chapter $c</span><span class="chapterdate">Oct $c, 2026</span></a></li>"""
            }}</ul></div>""",
        )
        Regex("/chapter-\\d+/").matches(path) -> html(
            """<div id="readerarea"></div><script>ts_reader.run({"defaultSource":"s1","sources":[{"source":"s1","images":["/img/s1.jpg","/img/s2.jpg","/img/s3.jpg","/img/s4.jpg","/img/s5.jpg"]}]});</script>""",
        )
        path.startsWith("/img/") || path.startsWith("/covers/") -> jpeg()
        else -> Resp(404, "text/plain", "nope")
    }
}

// ---------------------------------------------------------------------------------------------- tests

fun fetcher() = JdkFetcher(timeoutMs = 5_000)

fun testUnits() {
    section("Units: JSON, dates, chapter numbers, image validation")
    val src = mapOf("a" to listOf(1L, 2.5, "x\"y\n", null, true), "b" to mapOf("c" to "ال"))
    check("JSON round trip", Json.parse(Json.stringify(src)) == src, Json.stringify(src))
    check("JSON rejects trailing garbage", runCatching { Json.parse("{} x") }.isFailure)
    check("JsonPath wildcard", JsonPath.select(Json.parse("""{"a":[{"b":1},{"b":2}]}"""), "a[*].b") == listOf(1L, 2L))
    val now = 1_800_000_000_000L
    check("date: 3 days ago", DateParser.parse("3 days ago", now) == now - 3 * 86_400_000L)
    check("date: Arabic relative", DateParser.parse("منذ ٣ أيام", now) == now - 3 * 86_400_000L)
    check("date: Arabic dual form", DateParser.parse("منذ يومين", now) == now - 2 * 86_400_000L)
    check("date: ISO", DateParser.parse("2026-10-05") != null)
    check("date: text month", DateParser.parse("March 3, 2026") != null)
    check("chapter number: labelled", ChapterNumber.parse("Chapter 12.5 - Title") == 12.5)
    check("chapter number: Arabic", ChapterNumber.parse("الفصل ١٠") == 10.0)
    check("image magic: jpeg", ImageValidator.detectMime(JPEG) == "image/jpeg")
    check("image magic: html rejected", ImageValidator.validate("u", "<!doctype html><html></html>".toByteArray(), "text/html") != null)
}

fun testGenericDiscovery() {
    section("Adding a NEW site (unknown layout) by URL only")
    val site = siteGeneric()
    val result = SourceDiscovery(fetcher(), 1, 0).discover(site.origin, "Unknown Site")
    result.steps.forEach { println("      step: $it") }
    check("discovery verified end to end", result.verified, result.diagnostics.joinToString { "${it.code}:${it.message}" })
    val cfg = result.config ?: run { check("config produced", false); site.stop(); return }
    check("generic preset used (not hard-coded)", cfg.preset == "generic")

    val engine = SourceEngine(cfg, fetcher(), 0, 0)
    val p1 = engine.latest(1)
    check("listing page 1 has 8 works", p1.value?.items?.size == 8, "${p1.value?.items?.size}")
    check("listing titles are real", p1.value?.items?.first()?.title == "Title 1")
    check("listing covers resolved from lazy attr", p1.value?.items?.first()?.cover?.endsWith("/covers/1.jpg") == true, "${p1.value?.items?.first()?.cover}")
    val p2 = engine.latest(2)
    check("listing page 2 (pagination) has 3 works", p2.value?.items?.size == 3, "${p2.value?.items?.size}")
    check("page 2 continues numbering", p2.value?.items?.first()?.title == "Title 9")
    val p3 = engine.latest(3)
    check("page 3 is empty and reports no more", p3.value?.items.isNullOrEmpty() || p3.value?.hasNext == false)

    val search = engine.search("Title 7", 1)
    check("search finds the right work", search.value?.items?.map { it.title } == listOf("Title 7"), "${search.value?.items?.map { it.title }}")

    val details = engine.details(p1.value!!.items.first().url)
    check("details: title and description", details.value?.title == "Title 1" && details.value?.description == "About 1", "${details.value}")

    val chapters = engine.chapters(p1.value!!.items.first().url)
    check("chapters: all 30 found", chapters.value?.size == 30, "${chapters.value?.size}")
    check("chapters: newest first", chapters.value?.first()?.number == 30.0 && chapters.value?.last()?.number == 1.0)
    check("chapters: dates parsed", chapters.value?.all { it.dateMillis != null } == true)

    val pages = engine.pages(chapters.value!!.first().url)
    val urls = pages.value?.map { it.url }.orEmpty()
    check("pages: exactly the 12 real pages", urls.size == 12, "${urls.size}: $urls")
    check("pages: in reading order", urls == (1..12).map { "${site.origin}/img/p${"%02d".format(it)}.jpg" }, "$urls")
    check("pages: no emoji/avatar/ad/logo/footer", urls.none { listOf("emoji", "avatar", "ads", "logo", "footer", "loading").any { b -> it.contains(b) } })
    check("pages: lazy data-src and data-lazy-src both resolved", urls.any { it.endsWith("p05.jpg") } && urls.any { it.endsWith("p02.jpg") })
    check("pages: Referer header is the chapter URL", pages.value?.first()?.headers?.get("Referer") == chapters.value!!.first().url)

    val ok = engine.downloadPage(pages.value!!.first())
    check("download: real image accepted", ok.ok && ImageValidator.detectMime(ok.value!!) == "image/jpeg")
    val bad = engine.downloadPage(PageImage(1, "${site.origin}/img/broken.jpg"))
    check("download: HTML saved as .jpg is rejected", !bad.ok && bad.errors.any { it.code == "NOT_AN_IMAGE" }, bad.diagnostics.joinToString { it.code })

    val roundTrip = ConfigJson.fromJson(ConfigJson.toJson(cfg))
    check("config JSON round trip keeps behaviour", roundTrip == cfg)
    site.stop()
}

fun testChapterPagination() {
    section("Chapter pagination: complete, no duplicates, no infinite loop")
    val site = siteChapters()
    val spec = ChaptersSpec(
        request = RequestSpec("{url}?cpage={page}"), item = Rule(css = "li.ch"), name = Rule(css = "a"),
        url = Rule(css = "a", attr = "href"), date = Rule(css = "span"), pagination = PaginationMode.PAGE_PARAM,
    )
    val cfg = SourceConfig(id = "p", name = "Paged", baseUrl = site.origin, chapters = spec)
    val engine = SourceEngine(cfg, fetcher(), 0, 0)
    val r = engine.chapters("${site.origin}/series/x")
    check("all 120 chapters collected across 3 pages", r.value?.size == 120, "${r.value?.size}")
    check("duplicate row removed and reported", r.diagnostics.any { it.code == "DUPLICATES_REMOVED" })
    check("ascending site order normalised to newest first", r.value?.first()?.number == 120.0 && r.value?.last()?.number == 1.0)
    check("no chapter missing", (1..120).all { n -> r.value!!.any { it.number == n.toDouble() } })

    val before = site.hits.get()
    val loop = engine.chapters("${site.origin}/series/loop")
    check("site ignoring ?cpage stops after a repeated page", loop.value?.size == 40, "${loop.value?.size}")
    check("…without hammering the server", site.hits.get() - before <= 3, "hits=${site.hits.get() - before}")
    site.stop()
}

fun testJsonSource() {
    section("JSON API source defined by hand (config JSON)")
    val site = siteJson()
    val cfgText = """
    {"name":"JSON Site","baseUrl":"${site.origin}","kind":"JSON",
     "latest":{"request":{"url":"{base}/api/latest?page={page}"},"item":{"json":"data.items"},"title":{"json":"name"},
               "link":{"json":"id","replace":[["^(.*)${'$'}","{base}/api/manga/${'$'}1"]]},"cover":{"json":"cover"},"updated":{"json":"updated"}},
     "details":{"title":{"json":"data.title"},"description":{"json":"data.description"},"author":{"json":"data.author"},"genres":{"json":"data.genres"}},
     "chapters":{"request":{"url":"{url}/chapters?page={page}"},"item":{"json":"data.chapters"},"name":{"json":"title"},
                 "url":{"json":"url"},"date":{"json":"date"},"pagination":"PAGE_PARAM"},
     "pages":{"json":{"json":"images[*]"}}}
    """.trimIndent()
    val cfg = ConfigJson.fromJson(cfgText)
    val engine = SourceEngine(cfg, fetcher(), 0, 0)
    val l1 = engine.latest(1)
    check("JSON listing page 1 parsed (5 items)", l1.value?.items?.size == 5, l1.diagnostics.joinToString { it.code })
    val dates = l1.value!!.items.map { it.updatedAt }
    check("sorted by REAL update time (newest first)", dates == dates.sortedByDescending { it } && l1.value!!.items.first().title == "JSON Manga 2", "${l1.value!!.items.map { it.title }}")
    check("JSON listing page 2 (3 items)", engine.latest(2).value?.items?.size == 3)
    val d = engine.details(l1.value!!.items.first().url)
    check("JSON details incl. genres", d.value?.genres == listOf("Action", "Drama") && d.value?.author == "Ann", "${d.value}")
    val ch = engine.chapters(l1.value!!.items.first().url)
    check("JSON chapters: all 60 across 3 pages", ch.value?.size == 60, "${ch.value?.size} ${ch.diagnostics.map { it.code }}")
    val pg = engine.pages(ch.value!!.first().url)
    check("JSON pages: 4 images", pg.value?.size == 4, pg.diagnostics.joinToString { it.message })
    site.stop()
}

fun testPresets() {
    section("Known templates are verified live, not trusted blindly")
    val madara = siteMadara()
    val m = SourceDiscovery(fetcher(), 0, 0).discover(madara.origin, "Madara Test")
    check("Madara-style site verified (AJAX chapters)", m.verified && m.config?.preset == "madara", m.diagnostics.joinToString { "${it.code}:${it.message}" })
    if (m.config != null) {
        val e = SourceEngine(m.config, fetcher(), 0, 0)
        val first = e.latest(1).value!!.items.first()
        check("Madara listing title + relative time parsed", first.title == "Madara Work 1" && first.updatedAt != null, "$first")
        val chapters = e.chapters(first.url)
        check("Madara chapters via POST ajax", chapters.value?.size == 3, "${chapters.value?.size}")
        val pages = e.pages(chapters.value!!.first().url)
        check("Madara pages: 10, emoji excluded", pages.value?.size == 10 && pages.value!!.none { it.url.contains("emoji") }, "${pages.value?.size}")
    }
    madara.stop()

    val stream = siteStream()
    val s = SourceDiscovery(fetcher(), 0, 0).discover(stream.origin, "Stream Test")
    check("MangaStream-style site verified", s.verified && s.config?.preset == "mangastream", s.diagnostics.joinToString { "${it.code}:${it.message}" })
    if (s.config != null) {
        val e = SourceEngine(s.config, fetcher(), 0, 0)
        val first = e.latest(1).value!!.items.first()
        val ch = e.chapters(first.url)
        val pg = e.pages(ch.value!!.first().url)
        check("images found inside inline script data", pg.value?.size == 5 && pg.diagnostics.any { it.message.contains("script-data") }, pg.diagnostics.joinToString { it.message })
    }
    stream.stop()
}

fun testFailures() {
    section("Diagnostics and failure isolation")
    val dead = Site { _, _, _, _ -> Resp(503, "text/plain", "busy") }
    val cf = Site { _, _, _, _ -> Resp(403, "text/html", "<html><title>Just a moment...</title><div id=\"cf-chl-widget\"></div></html>") }
    val denied = Site { _, _, _, _ -> Resp(403, "text/html", "<html>Forbidden</html>") }
    val empty = Site { _, _, _, _ -> html("<p>hello, nothing to see</p>") }
    val good = siteJson()

    fun cfgFor(origin: String) = SourceConfig(
        id = "x", name = origin, baseUrl = origin,
        latest = ListingSpec(RequestSpec("{base}/"), Rule(css = "div.card"), Rule(css = "h3"), Rule(css = "a", attr = "href")),
        chapters = ChaptersSpec(item = Rule(css = "li"), name = Rule(css = "a"), url = Rule(css = "a", attr = "href")),
    )

    val r503 = SourceEngine(cfgFor(dead.origin), fetcher(), 2, 0).latest(1)
    check("503: reported with code HTTP_503", r503.errors.any { it.code == "HTTP_503" }, r503.diagnostics.joinToString { it.code })
    check("503: retried 3 times then gave up", dead.hits.get() == 3, "hits=${dead.hits.get()}")
    val rcf = SourceEngine(cfgFor(cf.origin), fetcher(), 2, 0).latest(1)
    check("anti-bot page named as such, not bypassed, not retried", rcf.errors.any { it.code == "ANTI_BOT" } && cf.hits.get() == 1, rcf.diagnostics.joinToString { it.code } + " hits=${cf.hits.get()}")
    val rd = SourceEngine(cfgFor(denied.origin), fetcher(), 2, 0).latest(1)
    check("plain 403 explained as ACCESS_DENIED", rd.errors.any { it.code == "ACCESS_DENIED" })
    val re = SourceEngine(cfgFor(empty.origin), fetcher(), 0, 0).latest(1)
    check("wrong selector explained (NO_ITEMS with the selector)", re.errors.any { it.code == "NO_ITEMS" && it.message.contains("div.card") }, re.diagnostics.joinToString { it.message })
    val unreachable = SourceEngine(cfgFor("http://127.0.0.1:1"), fetcher(), 1, 0).latest(1)
    check("unreachable host reported as NETWORK", unreachable.errors.any { it.code == "NETWORK" })

    val goodCfg = ConfigJson.fromJson("""
        {"name":"ok","baseUrl":"${good.origin}","kind":"JSON",
         "latest":{"request":{"url":"{base}/api/latest?page={page}"},"item":{"json":"data.items"},"title":{"json":"name"},"link":{"json":"id","replace":[["^(.*)${'$'}","{base}/api/manga/${'$'}1"]]}},
         "chapters":{"item":{"json":"data.chapters"},"name":{"json":"title"},"url":{"json":"url"}}}""")
    val sources = listOf(cfgFor(dead.origin), cfgFor(cf.origin), goodCfg, cfgFor(empty.origin))
    val results = sources.map { c -> SourceRunner.isolated(c, Stage.LISTING) { SourceEngine(c, fetcher(), 0, 0).latest(1) } }
    check("one working source among 3 broken ones still works", results[2].ok && results[2].value!!.items.size == 5)
    check("the 3 broken ones failed individually", listOf(0, 1, 3).all { !results[it].ok })

    val crash = SourceRunner.isolated<ListingPage>(goodCfg, Stage.LISTING) { throw IllegalStateException("boom") }
    check("an unexpected exception is contained", !crash.ok && crash.errors.first().code == "UNEXPECTED")

    val none = SourceDiscovery(fetcher(), 0, 0).discover(empty.origin)
    check("discovery on an unsupported page fails with an explanation", !none.verified && none.diagnostics.any { it.code == "NO_LAYOUT" }, none.diagnostics.joinToString { it.code })
    val bad = SourceDiscovery(fetcher(), 0, 0).discover("not a url at all")
    check("discovery rejects a malformed address", bad.diagnostics.any { it.code == "BAD_URL" })
    listOf(dead, cf, denied, empty, good).forEach { it.stop() }
}

fun main() {
    testUnits()
    testGenericDiscovery()
    testChapterPagination()
    testJsonSource()
    testPresets()
    testFailures()
    println("\n==============================")
    println("passed: $passed   failed: $failed")
    if (failed > 0) { println("FAILED:"); failures.forEach { println(" - $it") } }
    System.exit(if (failed == 0) 0 else 1)
}
