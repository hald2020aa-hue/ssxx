package com.openmanga.engine

enum class SourceKind { HTML, JSON }
enum class FetchMode { HTTP, WEBVIEW }
enum class PaginationMode { NONE, PAGE_PARAM, NEXT_LINK }
enum class ChapterOrder { AUTO, NEWEST_FIRST, OLDEST_FIRST }

val DEFAULT_IMAGE_ATTRS = listOf("data-src", "data-lazy-src", "data-original", "data-url", "data-cfsrc", "src")

/**
 * One extraction rule. For HTML use [css] or [xpath] (relative to the current element), for JSON use [json].
 * [attr] is "text" (default), "ownText", "html", "outerHtml" or any HTML attribute; [attrs] is a fallback list.
 * [regex] keeps group 1 (or the whole match); [replace] is a list of [pattern, replacement] pairs.
 */
data class Rule(
    val css: String? = null,
    val xpath: String? = null,
    val json: String? = null,
    val attr: String? = null,
    val attrs: List<String> = emptyList(),
    val regex: String? = null,
    val replace: List<List<String>> = emptyList(),
    val index: Int? = null,
    val constant: String? = null,
)

data class RequestSpec(
    val url: String = "{url}",
    val method: String = "GET",
    val body: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

data class ListingSpec(
    val request: RequestSpec,
    val item: Rule,
    val title: Rule,
    val link: Rule,
    val cover: Rule? = null,
    val latestChapter: Rule? = null,
    val updated: Rule? = null,
    val hasNext: Rule? = null,
    val startPage: Int = 1,
)

data class DetailsSpec(
    val request: RequestSpec? = null,
    val title: Rule? = null,
    val cover: Rule? = null,
    val description: Rule? = null,
    val author: Rule? = null,
    val genres: Rule? = null,
    val status: Rule? = null,
)

data class ChaptersSpec(
    val request: RequestSpec? = null,
    /** Tried when the manga page itself lists no chapters (e.g. chapters loaded through an AJAX call). */
    val ajaxRequest: RequestSpec? = null,
    val item: Rule,
    val name: Rule,
    val url: Rule,
    val number: Rule? = null,
    val date: Rule? = null,
    val pagination: PaginationMode = PaginationMode.NONE,
    val nextLink: Rule? = null,
    val startPage: Int = 1,
    val maxPages: Int = 300,
    val order: ChapterOrder = ChapterOrder.AUTO,
)

data class PagesSpec(
    val request: RequestSpec? = null,
    val item: Rule? = null,
    /** Restrict heuristic image discovery to this container (CSS). */
    val containerCss: String? = null,
    val imageAttrs: List<String> = DEFAULT_IMAGE_ATTRS,
    /** Extra regexes run against inline scripts; group 1 must be a JSON array (or object holding "images"). */
    val scriptRegexes: List<String> = emptyList(),
    /** For JSON sources: path to the array of image URLs (or objects, with [json] relative rule). */
    val json: Rule? = null,
    val headers: Map<String, String> = emptyMap(),
    /** Extra substrings that mark a URL as NOT a manga page for this source. */
    val excludeUrlContains: List<String> = emptyList(),
)

data class SourceConfig(
    val id: String,
    val name: String,
    val baseUrl: String,
    val kind: SourceKind = SourceKind.HTML,
    val fetchMode: FetchMode = FetchMode.HTTP,
    val headers: Map<String, String> = emptyMap(),
    val userAgent: String? = null,
    val preset: String? = null,
    val popular: ListingSpec? = null,
    val latest: ListingSpec? = null,
    val search: ListingSpec? = null,
    val details: DetailsSpec = DetailsSpec(),
    val chapters: ChaptersSpec,
    val pages: PagesSpec = PagesSpec(),
    val enabled: Boolean = true,
)

data class MangaItem(
    val title: String,
    val url: String,
    val cover: String? = null,
    val latestChapter: String? = null,
    val updatedText: String? = null,
    val updatedAt: Long? = null,
)

data class MangaDetails(
    val title: String?,
    val url: String,
    val cover: String?,
    val description: String?,
    val author: String?,
    val genres: List<String>,
    val status: String?,
)

data class Chapter(
    val name: String,
    val url: String,
    val number: Double? = null,
    val dateText: String? = null,
    val dateMillis: Long? = null,
)

data class PageImage(val index: Int, val url: String, val headers: Map<String, String> = emptyMap())

data class ListingPage(val items: List<MangaItem>, val hasNext: Boolean)

enum class Stage { FETCH, LISTING, DETAILS, CHAPTERS, PAGES, DISCOVERY, IMAGE }
enum class Severity { INFO, WARNING, ERROR }

/** [code] is stable for UI localisation; [message] is an English technical explanation. */
data class Diagnostic(
    val stage: Stage,
    val severity: Severity,
    val code: String,
    val message: String,
    val url: String? = null,
)

class Outcome<out T>(val value: T?, val diagnostics: List<Diagnostic>) {
    val ok: Boolean get() = value != null && diagnostics.none { it.severity == Severity.ERROR }
    val errors: List<Diagnostic> get() = diagnostics.filter { it.severity == Severity.ERROR }
}
