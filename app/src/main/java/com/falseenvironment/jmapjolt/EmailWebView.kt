package com.falseenvironment.jmapjolt

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

/**
 * Hardened WebView setup shared by every surface that renders email HTML.
 *
 * Bodies are untrusted: JavaScript, file/content access and mixed content are off, and
 * every sub-resource request goes through an allowlist. With "load images" off nothing
 * reaches the network at all — not just `<img>` but stylesheets, fonts, media and CSS
 * `url()` fetches, which [WebSettings.blockNetworkImage] alone lets through and which
 * senders use as read receipts. With it on, only image requests are let out, over HTTPS.
 */
internal object EmailWebView {
    const val BASE_URL = "https://jmapjolt.invalid/email/"

    fun harden(wv: WebView, loadImages: () -> Boolean, onLink: ((String) -> Unit)? = null) {
        wv.settings.apply {
            javaScriptEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            // Body HTML is local; without this WebView grows an unbounded
            // disk cache of remote images (~12 MB observed).
            cacheMode = WebSettings.LOAD_NO_CACHE
            blockNetworkImage = !loadImages()
        }
        wv.webViewClient = GuardedClient(loadImages, onLink)
    }

    fun isImageLoadingEnabled(context: Context): Boolean =
        context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("load_images", false)

    private class GuardedClient(
        private val loadImages: () -> Boolean,
        private val onLink: ((String) -> Unit)?
    ) : WebViewClient() {

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url
            // Inline content never touches the network.
            if (url.scheme == "data") return null
            val accept = request.requestHeaders.entries
                .firstOrNull { it.key.equals("Accept", ignoreCase = true) }?.value.orEmpty()
            val isImage = accept.startsWith("image/")
            val allowed = url.scheme == "https" && isImage && loadImages()
            return if (allowed) null else BLOCKED()
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            onLink?.invoke(request.url.toString())
            return true
        }

        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            onLink?.invoke(url)
            return true
        }
    }

    // A fresh response per request: WebView consumes the stream.
    @Suppress("FunctionName")
    private fun BLOCKED() = WebResourceResponse(
        "text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0))
    )
}

internal fun WebView.loadEmailHtml(html: String) =
    loadDataWithBaseURL(EmailWebView.BASE_URL, html, "text/html", "UTF-8", null)
