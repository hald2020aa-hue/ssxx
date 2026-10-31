package com.openmanga.engine

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

data class FetchRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    /** True when the page needs JavaScript to produce its content (the app uses an in-app WebView for this). */
    val render: Boolean = false,
)

class FetchResponse(
    val url: String,
    val status: Int,
    val headers: Map<String, String>,
    val bytes: ByteArray,
) {
    val contentType: String get() = headers.entries.firstOrNull { it.key.equals("content-type", true) }?.value ?: ""

    val text: String by lazy {
        val declared = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(contentType)?.groupValues?.get(1)
        val cs = try { if (declared != null) Charset.forName(declared) else Charsets.UTF_8 } catch (e: Exception) { Charsets.UTF_8 }
        String(bytes, cs)
    }
}

interface Fetcher {
    @Throws(IOException::class)
    fun execute(req: FetchRequest): FetchResponse
}

/** Plain JDK implementation, used for tests and on the JVM. The Android app provides an OkHttp implementation. */
class JdkFetcher(
    private val userAgent: String = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36",
    private val timeoutMs: Int = 20_000,
    private val maxBytes: Int = 40 * 1024 * 1024,
) : Fetcher {
    override fun execute(req: FetchRequest): FetchResponse {
        var url = req.url
        var method = req.method
        var body = req.body
        repeat(8) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = method
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Accept", "*/*")
            req.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null && method != "GET") {
                conn.doOutput = true
                if (req.headers.keys.none { it.equals("content-type", true) }) {
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                }
                conn.outputStream.use { it.write(body!!.toByteArray()) }
            }
            val status = conn.responseCode
            if (status in 300..399 && conn.getHeaderField("Location") != null) {
                url = URL(URL(url), conn.getHeaderField("Location")).toString()
                if (status == 301 || status == 302 || status == 303) { method = "GET"; body = null }
                conn.disconnect()
                return@repeat
            }
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val out = ByteArrayOutputStream()
            stream?.use { s ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) throw IOException("Response too large")
                }
            }
            val headers = LinkedHashMap<String, String>()
            conn.headerFields.forEach { (k, v) -> if (k != null && v != null) headers[k] = v.joinToString(", ") }
            conn.disconnect()
            return FetchResponse(url, status, headers, out.toByteArray())
        }
        throw IOException("Too many redirects")
    }
}

object UrlUtil {
    fun resolve(base: String, href: String?): String? {
        if (href == null) return null
        val h = href.trim()
        if (h.isEmpty() || h.startsWith("javascript:", true) || h.startsWith("#")) return null
        if (h.startsWith("data:", true)) return h
        return try {
            URL(URL(base), h.replace(" ", "%20")).toString()
        } catch (e: Exception) {
            null
        }
    }

    fun host(url: String): String? = try { URL(url).host.lowercase().removePrefix("www.") } catch (e: Exception) { null }

    fun origin(url: String): String? = try {
        val u = URL(url)
        val port = if (u.port == -1 || u.port == u.defaultPort) "" else ":${u.port}"
        "${u.protocol}://${u.host}$port"
    } catch (e: Exception) {
        null
    }

    fun encode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    fun normalizeBase(input: String): String? {
        var s = input.trim()
        if (s.isEmpty()) return null
        if (!s.contains("://")) s = "https://$s"
        return try {
            val u = URL(s)
            if (u.host.isNullOrBlank()) null else s.trimEnd('/')
        } catch (e: Exception) {
            null
        }
    }
}

/** Detects anti-bot walls and login pages so the UI can explain them. Never tries to defeat them. */
object ProtectionDetector {
    fun detect(resp: FetchResponse): Diagnostic? {
        val head = if (resp.bytes.size > 6000) String(resp.bytes, 0, 6000, Charsets.ISO_8859_1) else String(resp.bytes, Charsets.ISO_8859_1)
        val lower = head.lowercase()
        val cf = lower.contains("just a moment") || lower.contains("cf-chl") || lower.contains("challenge-platform") ||
            lower.contains("attention required! | cloudflare") || lower.contains("checking your browser")
        if (cf && (resp.status == 403 || resp.status == 503 || resp.status == 429 || resp.status == 200)) {
            return Diagnostic(
                Stage.FETCH, Severity.ERROR, "ANTI_BOT",
                "The site returned a browser-verification (anti-bot) page. Open the site in the in-app browser, pass the check, then retry. The engine does not bypass protections.",
                resp.url,
            )
        }
        if (resp.status == 401 || resp.status == 403) {
            return Diagnostic(
                Stage.FETCH, Severity.ERROR, "ACCESS_DENIED",
                "HTTP ${resp.status}: the site denied access (login, region or Referer restriction). Provide a signed-in session or the required headers in the source's advanced settings.",
                resp.url,
            )
        }
        return null
    }
}
