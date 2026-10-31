package com.openmanga.reader.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

/**
 * Minimal in-app browser. Cookies are shared with the app's network layer (see WebViewCookieJar), so after the
 * user passes a site's own browser check or signs in here, retrying in the app works.
 */
class BrowserActivity : AppCompatActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        val web = WebView(this)
        web.applyBottomInsets()
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, finished: String) {
                title = view.title ?: finished
                CookieManager.getInstance().flush()
            }
        }
        setContentView(web)
        web.loadUrl(url)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val web = findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as? WebView
        if (web != null && web.canGoBack()) web.goBack() else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    companion object {
        const val EXTRA_URL = "url"
    }
}
