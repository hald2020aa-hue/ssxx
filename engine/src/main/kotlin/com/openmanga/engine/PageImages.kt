package com.openmanga.engine

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** Magic-byte validation so an HTML error page saved as ".jpg" is never treated as a manga page. */
object ImageValidator {
    fun detectMime(b: ByteArray): String? {
        if (b.size < 12) return null
        fun at(i: Int) = b[i].toInt() and 0xFF
        if (at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF) return "image/jpeg"
        if (at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47) return "image/png"
        if (at(0) == 'G'.code && at(1) == 'I'.code && at(2) == 'F'.code && at(3) == '8'.code) return "image/gif"
        if (String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP") return "image/webp"
        if (String(b, 4, 4, Charsets.ISO_8859_1) == "ftyp") {
            val brand = String(b, 8, 4, Charsets.ISO_8859_1)
            if (brand.startsWith("avi")) return "image/avif"
            if (brand.startsWith("hei") || brand.startsWith("mif")) return "image/heic"
        }
        if (at(0) == 'B'.code && at(1) == 'M'.code) return "image/bmp"
        return null
    }

    fun looksLikeHtml(b: ByteArray): Boolean {
        val head = String(b, 0, minOf(b.size, 512), Charsets.ISO_8859_1).trimStart().lowercase()
        return head.startsWith("<!doctype") || head.startsWith("<html") || head.startsWith("<?xml") || head.startsWith("{")
    }

    /** Returns null when [bytes] is a real image, otherwise a diagnostic explaining why not. */
    fun validate(url: String, bytes: ByteArray, contentType: String?): Diagnostic? {
        if (detectMime(bytes) != null) return null
        val why = if (looksLikeHtml(bytes)) "the response is an HTML/JSON document, not an image (content-type: ${contentType ?: "unknown"})"
        else "the bytes do not match any known image format"
        return Diagnostic(Stage.IMAGE, Severity.ERROR, "NOT_AN_IMAGE", "Downloaded file is not a valid image: $why", url)
    }
}

object PageImageFilter {
    private val badUrlParts = listOf(
        "emoji", "emoticon", "smilies", "smiley", "avatar", "gravatar", "logo", "favicon", "sprite", "/icons/", "icon-", "-icon",
        "banner", "/ads/", "/ad/", "adserver", "doubleclick", "googlesyndication", "pixel", "tracking", "placeholder", "blank.",
        "loading.", "spinner", "/flags/", "/badges/", "wp-includes/images", "s.w.org/images", "facebook", "twitter", "discord",
    )
    private val badExtensions = listOf(".svg", ".ico")
    private val badAncestor = Regex(
        "(^|[\\s_-])(comment|comments|avatar|header|footer|sidebar|widget|navbar|nav|menu|logo|related|recommend|popular|banner|ads?|advert|advertisement|social|share|breadcrumb|bookmark|profile)([\\s_-]|$)",
        RegexOption.IGNORE_CASE,
    )

    fun urlLooksBad(url: String, extra: List<String> = emptyList()): Boolean {
        val u = url.lowercase().substringBefore('?')
        if (badExtensions.any { u.endsWith(it) }) return true
        if (badUrlParts.any { u.contains(it) }) return true
        return extra.any { it.isNotBlank() && u.contains(it.lowercase()) }
    }

    fun inBadContainer(el: Element): Boolean {
        var p: Element? = el
        var depth = 0
        while (p != null && depth < 7 && p.tagName() != "body") {
            if (badAncestor.containsMatchIn(p.className()) || (p.id().isNotEmpty() && badAncestor.containsMatchIn(p.id()))) return true
            if (p.tagName() in setOf("header", "footer", "nav", "aside")) return true
            p = p.parent()
            depth++
        }
        return false
    }

    fun tooSmall(el: Element): Boolean {
        val w = el.attr("width").trim().removeSuffix("px").toIntOrNull()
        val h = el.attr("height").trim().removeSuffix("px").toIntOrNull()
        return (w != null && w in 1..149) && (h != null && h in 1..149)
    }
}

/** Finds the real page images of a chapter: explicit selector, then embedded script data, then a structural heuristic. */
object PageExtractor {
    private val defaultScriptRegexes = listOf(
        "ts_reader\\.run\\((\\{.*?\\})\\s*\\)\\s*;",
        "chapter_preloaded_images\\s*=\\s*(\\[.*?\\])",
        "\"images\"\\s*:\\s*(\\[[^\\]]*\\])",
        "\\b(?:pages|images|imageList|chapterImages)\\s*=\\s*(\\[[^\\]]*\\])",
    )
    private val imageLike = Regex("\\.(jpe?g|png|webp|avif|gif|bmp)(\\?|#|$)|/(image|img|uploads|wp-content|storage|cdn)/", RegexOption.IGNORE_CASE)

    class Result(val urls: List<String>, val strategy: String)

    fun extract(doc: Document, baseUrl: String, spec: PagesSpec): Result {
        spec.item?.let { rule ->
            val urls = fromRule(doc, baseUrl, rule, spec)
            if (urls.isNotEmpty()) return Result(urls, "selector")
        }
        val scripted = fromScripts(doc, baseUrl, spec)
        if (scripted.isNotEmpty()) return Result(scripted, "script-data")
        val guessed = heuristic(doc, baseUrl, spec)
        return Result(guessed, if (guessed.isEmpty()) "none" else "heuristic")
    }

    private fun candidateUrl(el: Element, baseUrl: String, attrs: List<String>): String? {
        for (a in attrs) {
            val v = Rules.read(el, a)?.trim()
            if (v.isNullOrEmpty() || v.startsWith("data:", true)) continue
            return UrlUtil.resolve(baseUrl, v)
        }
        Rules.bestFromSrcset(el.attr("srcset").ifEmpty { el.attr("data-srcset") })?.let { s ->
            return UrlUtil.resolve(baseUrl, s)
        }
        if (el.tagName() == "a") return UrlUtil.resolve(baseUrl, el.attr("href"))
        return null
    }

    private fun fromRule(doc: Document, baseUrl: String, rule: Rule, spec: PagesSpec): List<String> {
        val out = LinkedHashSet<String>()
        for (el in Rules.select(doc, rule)) {
            val raw = if (rule.attr != null || rule.attrs.isNotEmpty()) Rules.post(Rules.readFirst(el, rule), rule) else null
            val url = (if (raw != null) UrlUtil.resolve(baseUrl, raw) else candidateUrl(el, baseUrl, spec.imageAttrs)) ?: continue
            if (!PageImageFilter.urlLooksBad(url, spec.excludeUrlContains)) out.add(url)
        }
        return out.toList()
    }

    private fun fromScripts(doc: Document, baseUrl: String, spec: PagesSpec): List<String> {
        val regexes = spec.scriptRegexes + defaultScriptRegexes
        for (script in doc.select("script")) {
            val code = script.data()
            if (code.length < 20) continue
            for (r in regexes) {
                val rx = try { Regex(r, RegexOption.DOT_MATCHES_ALL) } catch (e: Exception) { continue }
                for (m in rx.findAll(code)) {
                    val payload = m.groupValues.getOrNull(1) ?: continue
                    val urls = urlsFromJsonish(payload, baseUrl, spec)
                    if (urls.size >= 2) return urls
                }
            }
        }
        return emptyList()
    }

    private fun urlsFromJsonish(payload: String, baseUrl: String, spec: PagesSpec): List<String> {
        val parsed = try { Json.parse(payload) } catch (e: Exception) {
            try { Json.parse(payload.replace('\'', '"')) } catch (e2: Exception) { return emptyList() }
        }
        var best: List<String> = emptyList()
        fun visit(n: Any?) {
            when (n) {
                is List<*> -> {
                    val strs = n.filterIsInstance<String>()
                    if (strs.size >= 2 && strs.size == n.size) {
                        val urls = strs.filter { imageLike.containsMatchIn(it) }
                            .mapNotNull { UrlUtil.resolve(baseUrl, it) }
                            .filter { !PageImageFilter.urlLooksBad(it, spec.excludeUrlContains) }
                        if (urls.size > best.size) best = urls
                    } else n.forEach { visit(it) }
                }
                is Map<*, *> -> n.values.forEach { visit(it) }
            }
        }
        visit(parsed)
        return best.distinct()
    }

    private fun heuristic(doc: Document, baseUrl: String, spec: PagesSpec): List<String> {
        val root: Element = spec.containerCss?.let { doc.selectFirst(it) } ?: doc.body() ?: return emptyList()
        data class Cand(val el: Element, val url: String)

        val cands = root.select("img").mapNotNull { img ->
            val url = candidateUrl(img, baseUrl, spec.imageAttrs) ?: return@mapNotNull null
            if (PageImageFilter.urlLooksBad(url, spec.excludeUrlContains)) return@mapNotNull null
            if (PageImageFilter.inBadContainer(img) || PageImageFilter.tooSmall(img)) return@mapNotNull null
            Cand(img, url)
        }
        if (cands.isEmpty()) return emptyList()
        if (cands.size <= 2) return cands.map { it.url }.distinct()

        fun signature(el: Element): String {
            val parts = ArrayList<String>()
            var p: Element? = el
            var d = 0
            while (p != null && d < 4 && p.tagName() != "body") {
                val cls = p.classNames().filter { c -> c.none { ch -> ch.isDigit() } }.take(2).joinToString(".")
                parts.add(p.tagName() + (if (cls.isNotEmpty()) ".$cls" else ""))
                p = p.parent()
                d++
            }
            return parts.joinToString(">")
        }

        val groups = cands.groupBy { signature(it.el) }
        val best = groups.values.maxByOrNull { it.size } ?: return emptyList()
        // Include siblings of the winning group that live in the same reader container (a first page often has a different class).
        var lca: Element? = best.first().el.parent()
        while (lca != null && best.any { !isInside(it.el, lca!!) }) lca = lca.parent()
        val region = lca
        val chosen = if (region != null && best.size >= 3) cands.filter { isInside(it.el, region) } else best
        return chosen.map { it.url }.distinct()
    }

    private fun isInside(el: Element, ancestor: Element): Boolean {
        var p: Element? = el
        while (p != null) {
            if (p === ancestor) return true
            p = p.parent()
        }
        return false
    }
}
