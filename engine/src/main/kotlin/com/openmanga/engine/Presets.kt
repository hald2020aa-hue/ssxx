package com.openmanga.engine

/**
 * Starting points for two very common site templates. They are written from knowledge of those templates
 * and are only trusted after [SourceDiscovery] verifies them against the live site.
 */
object Presets {
    private val coverAttrs = listOf("data-src", "data-lazy-src", "data-original", "src")

    fun byName(preset: String, name: String, base: String): SourceConfig? = when (preset.lowercase()) {
        "madara" -> madara(name, base)
        "mangastream", "themesia" -> mangastream(name, base)
        else -> null
    }

    fun madara(name: String, base: String): SourceConfig {
        val b = base.trimEnd('/')
        return SourceConfig(
            id = ConfigJson.slug(UrlUtil.host(b) ?: name), name = name, baseUrl = b, preset = "madara",
            latest = ListingSpec(
                request = RequestSpec("{base}/manga/page/{page}/?m_orderby=latest"),
                item = Rule(css = "div.page-item-detail"),
                title = Rule(css = "h3 a, h5 a, .post-title a"), link = Rule(css = "h3 a, h5 a, .post-title a", attr = "href"),
                cover = Rule(css = "img", attrs = coverAttrs),
                latestChapter = Rule(css = "span.chapter a, .chapter-item a"), updated = Rule(css = "span.post-on, .chapter-item .post-on"),
            ),
            search = ListingSpec(
                request = RequestSpec("{base}/page/{page}/?s={query}&post_type=wp-manga"),
                item = Rule(css = "div.c-tabs-item__content"),
                title = Rule(css = "h3 a, .post-title a"), link = Rule(css = "h3 a, .post-title a", attr = "href"),
                cover = Rule(css = "img", attrs = coverAttrs),
            ),
            details = DetailsSpec(
                title = Rule(css = "div.post-title h1, .post-title h3", attr = "ownText"),
                cover = Rule(css = "div.summary_image img", attrs = coverAttrs),
                description = Rule(css = "div.summary__content, div.description-summary"),
                author = Rule(css = "div.author-content a"), genres = Rule(css = "div.genres-content a"),
                status = Rule(css = "div.post-status div.summary-content"),
            ),
            chapters = ChaptersSpec(
                ajaxRequest = RequestSpec("{urlSlash}ajax/chapters/", "POST"),
                item = Rule(css = "li.wp-manga-chapter"), name = Rule(css = "a"), url = Rule(css = "a", attr = "href"),
                date = Rule(css = "span.chapter-release-date"),
            ),
            pages = PagesSpec(item = Rule(css = "div.page-break img, .reading-content img")),
        )
    }

    fun mangastream(name: String, base: String): SourceConfig {
        val b = base.trimEnd('/')
        return SourceConfig(
            id = ConfigJson.slug(UrlUtil.host(b) ?: name), name = name, baseUrl = b, preset = "mangastream",
            latest = ListingSpec(
                request = RequestSpec("{base}/manga/?page={page}&order=update"),
                item = Rule(css = "div.listupd div.bsx"),
                title = Rule(css = "a", attr = "title"), link = Rule(css = "a", attr = "href"),
                cover = Rule(css = "img", attrs = coverAttrs), latestChapter = Rule(css = "div.epxs"),
                hasNext = Rule(css = "a.next.page-numbers, div.hpage a.r"),
            ),
            search = ListingSpec(
                request = RequestSpec("{base}/page/{page}/?s={query}"),
                item = Rule(css = "div.listupd div.bsx"),
                title = Rule(css = "a", attr = "title"), link = Rule(css = "a", attr = "href"),
                cover = Rule(css = "img", attrs = coverAttrs),
            ),
            details = DetailsSpec(
                title = Rule(css = "h1.entry-title"), cover = Rule(css = "div.thumb img", attrs = coverAttrs),
                description = Rule(css = "div.entry-content[itemprop=description], .entry-content"),
                genres = Rule(css = ".mgen a"),
            ),
            chapters = ChaptersSpec(
                item = Rule(css = "div#chapterlist li"), name = Rule(css = "span.chapternum"),
                url = Rule(css = "a", attr = "href"), date = Rule(css = "span.chapterdate"),
            ),
            pages = PagesSpec(item = Rule(css = "div#readerarea img")),
        )
    }
}
