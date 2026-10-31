package com.openmanga.engine

/** Converts [SourceConfig] to and from JSON so sources can be stored, edited by hand and shared. */
object ConfigJson {
    fun toJson(c: SourceConfig, pretty: Boolean = true): String = Json.stringify(toMap(c), pretty)

    fun fromJson(text: String): SourceConfig {
        val m = Json.parse(text) as? Map<*, *> ?: throw IllegalArgumentException("A source config must be a JSON object")
        return fromMap(m)
    }

    fun listToJson(list: List<SourceConfig>): String = Json.stringify(list.map { toMap(it) }, true)

    fun listFromJson(text: String): List<SourceConfig> {
        val root = Json.parse(text) as? List<*> ?: throw IllegalArgumentException("Expected a JSON array of sources")
        return root.mapNotNull { (it as? Map<*, *>)?.let { m -> fromMap(m) } }
    }

    // ------------------------------------------------------------------ to map

    fun toMap(c: SourceConfig): Map<String, Any?> = buildMap {
        put("id", c.id); put("name", c.name); put("baseUrl", c.baseUrl)
        put("kind", c.kind.name); put("fetchMode", c.fetchMode.name)
        if (c.headers.isNotEmpty()) put("headers", c.headers)
        c.userAgent?.let { put("userAgent", it) }
        c.preset?.let { put("preset", it) }
        c.popular?.let { put("popular", listing(it)) }
        c.latest?.let { put("latest", listing(it)) }
        c.search?.let { put("search", listing(it)) }
        put("details", details(c.details))
        put("chapters", chapters(c.chapters))
        put("pages", pages(c.pages))
        put("enabled", c.enabled)
    }

    private fun rule(r: Rule?): Map<String, Any?>? = r?.let {
        buildMap {
            it.css?.let { v -> put("css", v) }; it.xpath?.let { v -> put("xpath", v) }; it.json?.let { v -> put("json", v) }
            it.attr?.let { v -> put("attr", v) }; if (it.attrs.isNotEmpty()) put("attrs", it.attrs)
            it.regex?.let { v -> put("regex", v) }; if (it.replace.isNotEmpty()) put("replace", it.replace)
            it.index?.let { v -> put("index", v) }; it.constant?.let { v -> put("constant", v) }
        }
    }

    private fun request(r: RequestSpec?): Map<String, Any?>? = r?.let {
        buildMap {
            put("url", it.url); put("method", it.method)
            it.body?.let { b -> put("body", b) }
            if (it.headers.isNotEmpty()) put("headers", it.headers)
        }
    }

    private fun MutableMap<String, Any?>.opt(key: String, value: Any?) { if (value != null) put(key, value) }

    private fun listing(l: ListingSpec): Map<String, Any?> = buildMap {
        put("request", request(l.request)); put("item", rule(l.item)); put("title", rule(l.title)); put("link", rule(l.link))
        opt("cover", rule(l.cover)); opt("latestChapter", rule(l.latestChapter)); opt("updated", rule(l.updated)); opt("hasNext", rule(l.hasNext))
        put("startPage", l.startPage)
    }

    private fun details(d: DetailsSpec): Map<String, Any?> = buildMap {
        opt("request", request(d.request)); opt("title", rule(d.title)); opt("cover", rule(d.cover)); opt("description", rule(d.description))
        opt("author", rule(d.author)); opt("genres", rule(d.genres)); opt("status", rule(d.status))
    }

    private fun chapters(c: ChaptersSpec): Map<String, Any?> = buildMap {
        opt("request", request(c.request)); opt("ajaxRequest", request(c.ajaxRequest))
        put("item", rule(c.item)); put("name", rule(c.name)); put("url", rule(c.url))
        opt("number", rule(c.number)); opt("date", rule(c.date))
        put("pagination", c.pagination.name); opt("nextLink", rule(c.nextLink))
        put("startPage", c.startPage); put("maxPages", c.maxPages); put("order", c.order.name)
    }

    private fun pages(p: PagesSpec): Map<String, Any?> = buildMap {
        opt("request", request(p.request)); opt("item", rule(p.item)); opt("containerCss", p.containerCss)
        put("imageAttrs", p.imageAttrs)
        if (p.scriptRegexes.isNotEmpty()) put("scriptRegexes", p.scriptRegexes)
        opt("json", rule(p.json))
        if (p.headers.isNotEmpty()) put("headers", p.headers)
        if (p.excludeUrlContains.isNotEmpty()) put("excludeUrlContains", p.excludeUrlContains)
    }

    // ---------------------------------------------------------------- from map

    private fun str(m: Map<*, *>, k: String): String? = (m[k] as? String)?.takeIf { it.isNotEmpty() }
    private fun strMap(v: Any?): Map<String, String> = (v as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value.toString() } ?: emptyMap()
    private fun strList(v: Any?): List<String> = (v as? List<*>)?.map { it.toString() } ?: emptyList()
    private fun sub(m: Map<*, *>, k: String): Map<*, *>? = m[k] as? Map<*, *>

    private fun ruleOf(m: Map<*, *>?): Rule? = m?.let {
        Rule(
            css = str(it, "css"), xpath = str(it, "xpath"), json = str(it, "json"), attr = str(it, "attr"),
            attrs = strList(it["attrs"]), regex = str(it, "regex"),
            replace = (it["replace"] as? List<*>)?.mapNotNull { p -> (p as? List<*>)?.map { x -> x.toString() } } ?: emptyList(),
            index = (it["index"] as? Number)?.toInt(), constant = str(it, "constant"),
        )
    }

    private fun requestOf(m: Map<*, *>?): RequestSpec? = m?.let {
        RequestSpec(str(it, "url") ?: "{url}", str(it, "method") ?: "GET", str(it, "body"), strMap(it["headers"]))
    }

    private fun need(m: Map<*, *>, k: String): Rule = ruleOf(sub(m, k)) ?: throw IllegalArgumentException("Missing required rule '$k'")

    private fun listingOf(m: Map<*, *>?): ListingSpec? = m?.let {
        ListingSpec(
            request = requestOf(sub(it, "request")) ?: throw IllegalArgumentException("Listing needs a 'request'"),
            item = need(it, "item"), title = need(it, "title"), link = need(it, "link"),
            cover = ruleOf(sub(it, "cover")), latestChapter = ruleOf(sub(it, "latestChapter")),
            updated = ruleOf(sub(it, "updated")), hasNext = ruleOf(sub(it, "hasNext")),
            startPage = (it["startPage"] as? Number)?.toInt() ?: 1,
        )
    }

    inline fun <reified E : Enum<E>> enumOf(v: Any?, default: E): E =
        enumValues<E>().firstOrNull { it.name.equals(v?.toString(), true) } ?: default

    fun fromMap(m: Map<*, *>): SourceConfig {
        val baseUrl = str(m, "baseUrl") ?: throw IllegalArgumentException("'baseUrl' is required")
        val name = str(m, "name") ?: UrlUtil.host(baseUrl) ?: baseUrl
        val d = sub(m, "details")
        val c = sub(m, "chapters") ?: throw IllegalArgumentException("'chapters' section is required")
        val p = sub(m, "pages")
        return SourceConfig(
            id = str(m, "id") ?: slug(UrlUtil.host(baseUrl) ?: name),
            name = name, baseUrl = baseUrl,
            kind = enumOf(m["kind"], SourceKind.HTML), fetchMode = enumOf(m["fetchMode"], FetchMode.HTTP),
            headers = strMap(m["headers"]), userAgent = str(m, "userAgent"), preset = str(m, "preset"),
            popular = listingOf(sub(m, "popular")), latest = listingOf(sub(m, "latest")), search = listingOf(sub(m, "search")),
            details = DetailsSpec(
                request = requestOf(d?.let { sub(it, "request") }), title = ruleOf(d?.let { sub(it, "title") }),
                cover = ruleOf(d?.let { sub(it, "cover") }), description = ruleOf(d?.let { sub(it, "description") }),
                author = ruleOf(d?.let { sub(it, "author") }), genres = ruleOf(d?.let { sub(it, "genres") }),
                status = ruleOf(d?.let { sub(it, "status") }),
            ),
            chapters = ChaptersSpec(
                request = requestOf(sub(c, "request")), ajaxRequest = requestOf(sub(c, "ajaxRequest")),
                item = need(c, "item"), name = need(c, "name"), url = need(c, "url"),
                number = ruleOf(sub(c, "number")), date = ruleOf(sub(c, "date")),
                pagination = enumOf(c["pagination"], PaginationMode.NONE), nextLink = ruleOf(sub(c, "nextLink")),
                startPage = (c["startPage"] as? Number)?.toInt() ?: 1, maxPages = (c["maxPages"] as? Number)?.toInt() ?: 300,
                order = enumOf(c["order"], ChapterOrder.AUTO),
            ),
            pages = PagesSpec(
                request = requestOf(p?.let { sub(it, "request") }), item = ruleOf(p?.let { sub(it, "item") }),
                containerCss = p?.let { str(it, "containerCss") },
                imageAttrs = p?.let { strList(it["imageAttrs"]) }?.takeIf { it.isNotEmpty() } ?: DEFAULT_IMAGE_ATTRS,
                scriptRegexes = p?.let { strList(it["scriptRegexes"]) } ?: emptyList(),
                json = ruleOf(p?.let { sub(it, "json") }), headers = strMap(p?.get("headers")),
                excludeUrlContains = p?.let { strList(it["excludeUrlContains"]) } ?: emptyList(),
            ),
            enabled = m["enabled"] as? Boolean ?: true,
        )
    }

    fun slug(s: String): String = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "source" }
}
