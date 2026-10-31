package com.openmanga.reader.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.openmanga.engine.FetchRequest
import com.openmanga.engine.FetchResponse
import com.openmanga.engine.Fetcher
import com.openmanga.engine.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Cookies are shared with the in-app browser. When a site asks the user to pass a browser check, the user does it
 * there and the resulting cookies are reused here. Nothing is forged or bypassed.
 */
class WebViewCookieJar : CookieJar {
    private val cm: CookieManager get() = CookieManager.getInstance()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { cm.setCookie(url.toString(), it.toString()) }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        cm.getCookie(url.toString())?.split(";")?.mapNotNull { Cookie.parse(url, it.trim()) } ?: emptyList()
}

class AppFetcher(private val context: Context) : Fetcher {
    private val client = OkHttpClient.Builder()
        .cookieJar(WebViewCookieJar())
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val defaultUserAgent: String by lazy {
        try { WebSettings.getDefaultUserAgent(context) } catch (e: Exception) { "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36" }
    }

    @Throws(IOException::class)
    override fun execute(req: FetchRequest): FetchResponse {
        if (req.render) return WebViewRenderer.render(context, req)
        val builder = try {
            Request.Builder().url(req.url)
        } catch (e: IllegalArgumentException) {
            throw IOException("Invalid URL: ${req.url}")
        }
        if (req.headers.keys.none { it.equals("User-Agent", true) }) builder.header("User-Agent", defaultUserAgent)
        req.headers.forEach { (k, v) -> builder.header(k, v) }
        if (req.method == "GET") {
            builder.get()
        } else {
            val type = (req.headers.entries.firstOrNull { it.key.equals("content-type", true) }?.value ?: "application/x-www-form-urlencoded").toMediaType()
            builder.method(req.method, (req.body ?: "").toRequestBody(type))
        }
        client.newCall(builder.build()).execute().use { r ->
            val headers = r.headers.toMultimap().mapValues { it.value.joinToString(", ") }
            return FetchResponse(r.request.url.toString(), r.code, headers, r.body?.bytes() ?: ByteArray(0))
        }
    }
}

/** For sites that only produce content with JavaScript: loads the page in a WebView and returns the rendered DOM. */
object WebViewRenderer {
    @Throws(IOException::class)
    fun render(context: Context, req: FetchRequest, timeoutMs: Long = 40_000): FetchResponse {
        if (req.method != "GET") throw IOException("WebView mode only supports GET requests")
        val latch = CountDownLatch(1)
        var html: String? = null
        var failure: String? = null
        Handler(Looper.getMainLooper()).post {
            val wv = WebView(context.applicationContext)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    // give late scripts a moment to build the DOM
                    view.postDelayed({
                        view.evaluateJavascript("(function(){return document.documentElement.outerHTML})()") { raw ->
                            html = try { Json.parse(raw) as? String } catch (e: Exception) { null }
                            if (html == null) failure = "Could not read the rendered page"
                            latch.countDown()
                            view.destroy()
                        }
                    }, 1500)
                }

                override fun onReceivedError(view: WebView, request: android.webkit.WebResourceRequest, error: android.webkit.WebResourceError) {
                    if (request.isForMainFrame) {
                        failure = "WebView error ${error.errorCode}: ${error.description}"
                        latch.countDown()
                        view.destroy()
                    }
                }
            }
            wv.loadUrl(req.url, req.headers)
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) throw IOException("WebView timed out loading ${req.url}")
        failure?.let { throw IOException(it) }
        return FetchResponse(req.url, 200, mapOf("Content-Type" to "text/html; charset=utf-8"), (html ?: "").toByteArray())
    }
}
