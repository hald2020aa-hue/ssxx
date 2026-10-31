package com.openmanga.engine

import org.jsoup.nodes.Element

/** Applies [Rule]s to HTML elements or parsed JSON nodes. */
object Rules {
    fun select(ctx: Element, rule: Rule): List<Element> = try {
        when {
            rule.css != null -> ctx.select(rule.css)
            rule.xpath != null -> ctx.selectXpath(rule.xpath)
            else -> listOf(ctx)
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun text(ctx: Element, rule: Rule?): String? {
        if (rule == null) return null
        rule.constant?.let { return it }
        val targets = select(ctx, rule)
        val el = pick(targets, rule.index) ?: return null
        return post(readFirst(el, rule), rule)
    }

    fun all(ctx: Element, rule: Rule?): List<String> {
        if (rule == null) return emptyList()
        return select(ctx, rule).mapNotNull { post(readFirst(it, rule), rule) }.distinct()
    }

    fun readFirst(el: Element, rule: Rule): String? {
        val names = if (rule.attrs.isNotEmpty()) rule.attrs else listOf(rule.attr ?: "text")
        for (n in names) {
            val v = read(el, n)
            if (!v.isNullOrBlank()) return v
        }
        return null
    }

    fun read(el: Element, name: String): String? = when (name) {
        "text" -> el.text()
        "ownText" -> el.ownText()
        "html" -> el.html()
        "outerHtml" -> el.outerHtml()
        "srcset" -> bestFromSrcset(el.attr("srcset"))
        else -> el.attr(name).ifEmpty { null }
    }

    /** Picks the widest candidate of a srcset attribute. */
    fun bestFromSrcset(srcset: String?): String? {
        if (srcset.isNullOrBlank()) return null
        var best: String? = null
        var bestW = -1.0
        for (part in srcset.split(",")) {
            val bits = part.trim().split(Regex("\\s+"))
            if (bits.isEmpty() || bits[0].isEmpty()) continue
            val w = bits.getOrNull(1)?.dropLast(1)?.toDoubleOrNull() ?: 0.0
            if (w >= bestW) { bestW = w; best = bits[0] }
        }
        return best
    }

    fun jsonText(node: Any?, rule: Rule?): String? {
        if (rule == null) return null
        rule.constant?.let { return it }
        val path = rule.json ?: return post(JsonPath.asText(node), rule)
        val hits = JsonPath.select(node, path)
        val chosen = pick(hits, rule.index) ?: return null
        return post(JsonPath.asText(chosen), rule)
    }

    fun jsonAll(node: Any?, rule: Rule?): List<String> {
        val path = rule?.json ?: return emptyList()
        val hits = JsonPath.select(node, path)
        val flat = if (hits.size == 1 && hits[0] is List<*>) (hits[0] as List<*>) else hits
        return flat.mapNotNull { post(JsonPath.asText(it), rule) }.distinct()
    }

    private fun <T> pick(list: List<T>, index: Int?): T? {
        if (list.isEmpty()) return null
        if (index == null) return list.first()
        val i = if (index < 0) list.size + index else index
        return list.getOrNull(i)
    }

    fun post(raw: String?, rule: Rule): String? {
        var v = raw?.trim() ?: return null
        if (rule.regex != null) {
            val m = try { Regex(rule.regex).find(v) } catch (e: Exception) { null } ?: return null
            v = if (m.groupValues.size > 1) m.groupValues[1] else m.value
        }
        for (pair in rule.replace) {
            if (pair.size < 2) continue
            v = try { Regex(pair[0]).replace(v, pair[1]) } catch (e: Exception) { v }
        }
        v = v.trim()
        return v.ifEmpty { null }
    }
}

object DateParser {
    private val patterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd", "yyyy/MM/dd", "dd/MM/yyyy", "dd-MM-yyyy", "dd.MM.yyyy",
        "MMM d, yyyy", "MMMM d, yyyy", "d MMM yyyy", "d MMMM yyyy", "MMM dd yyyy", "MMMM dd yyyy",
    )

    private val unitMillis = mapOf(
        "second" to 1_000L, "sec" to 1_000L, "minute" to 60_000L, "min" to 60_000L, "hour" to 3_600_000L,
        "hr" to 3_600_000L, "day" to 86_400_000L, "week" to 604_800_000L, "month" to 2_592_000_000L, "year" to 31_536_000_000L,
        "ثانية" to 1_000L, "ثواني" to 1_000L, "دقيقة" to 60_000L, "دقائق" to 60_000L, "دقيقتين" to 60_000L,
        "ساعة" to 3_600_000L, "ساعات" to 3_600_000L, "ساعتين" to 3_600_000L,
        "يوم" to 86_400_000L, "أيام" to 86_400_000L, "ايام" to 86_400_000L, "يومين" to 86_400_000L,
        "أسبوع" to 604_800_000L, "اسبوع" to 604_800_000L, "أسابيع" to 604_800_000L, "أسبوعين" to 604_800_000L,
        "شهر" to 2_592_000_000L, "أشهر" to 2_592_000_000L, "شهرين" to 2_592_000_000L,
        "سنة" to 31_536_000_000L, "سنوات" to 31_536_000_000L, "سنتين" to 31_536_000_000L,
    )

    private val dualWords = setOf("دقيقتين", "ساعتين", "يومين", "أسبوعين", "شهرين", "سنتين")

    fun normalizeDigits(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            sb.append(
                when (c) {
                    in '٠'..'٩' -> '0' + (c - '٠')
                    in '۰'..'۹' -> '0' + (c - '۰')
                    else -> c
                },
            )
        }
        return sb.toString()
    }

    fun parse(text: String?, now: Long = System.currentTimeMillis()): Long? {
        if (text.isNullOrBlank()) return null
        val t = normalizeDigits(text.trim()).lowercase()
        relative(t, now)?.let { return it }
        for (p in patterns) {
            try {
                val f = java.text.SimpleDateFormat(p, java.util.Locale.ENGLISH)
                f.isLenient = false
                f.timeZone = java.util.TimeZone.getTimeZone("UTC")
                val pos = java.text.ParsePosition(0)
                val d = f.parse(t, pos)
                if (d != null && pos.index > 0) return d.time
            } catch (e: Exception) {
                // try next pattern
            }
        }
        return null
    }

    private fun relative(t: String, now: Long): Long? {
        if (t == "today" || t == "اليوم") return now
        if (t == "yesterday" || t == "أمس" || t == "امس") return now - 86_400_000L
        val en = Regex("(\\d+|an?|one)\\s*(second|sec|minute|min|hour|hr|day|week|month|year)s?\\s*ago").find(t)
        if (en != null) {
            val n = en.groupValues[1].toLongOrNull() ?: 1L
            return now - n * (unitMillis[en.groupValues[2]] ?: return null)
        }
        val ar = Regex("منذ\\s*(\\d+)?\\s*([\\p{L}]+)").find(t)
        if (ar != null) {
            val word = ar.groupValues[2]
            val unit = unitMillis[word] ?: return null
            val n = ar.groupValues[1].toLongOrNull() ?: if (word in dualWords) 2L else 1L
            return now - n * unit
        }
        return null
    }
}

object ChapterNumber {
    private val labelled = Regex("(?:chapter|chap|ch\\.?|episode|ep\\.?|الفصل|فصل|الحلقة|حلقة)\\s*[:#-]?\\s*(\\d+(?:[.,]\\d+)?)", RegexOption.IGNORE_CASE)
    private val any = Regex("(\\d+(?:[.,]\\d+)?)")

    fun parse(name: String?): Double? {
        if (name == null) return null
        val n = DateParser.normalizeDigits(name)
        val m = labelled.find(n) ?: any.find(n) ?: return null
        return m.groupValues[1].replace(',', '.').toDoubleOrNull()
    }
}
