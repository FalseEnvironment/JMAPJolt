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
 * JavaScript is enabled only because the generated shrink-to-fit script needs it.
 * Email scripts and event handlers are removed by the HTML sanitizer, and every
 * generated document carries a nonce-based CSP that permits no script other than
 * that one. File/content access and mixed content remain off, and every
 * sub-resource request goes through an allowlist. With "load images" off nothing
 * reaches the network at all — not just `<img>` but stylesheets, fonts, media and
 * CSS `url()` fetches, which [WebSettings.blockNetworkImage] alone lets through
 * and which senders use as read receipts. With it on, only image requests are let
 * out, over HTTPS.
 */
internal object EmailWebView {
    const val BASE_URL = "https://jmapjolt.invalid/email/"

    fun harden(wv: WebView, loadImages: () -> Boolean, onLink: ((String) -> Unit)? = null) {
        wv.settings.apply {
            // Only the trusted fit script runs: the sanitized HTML cannot carry a
            // script tag, and the CSP in each document allows just its own nonce.
            javaScriptEnabled = true
            javaScriptCanOpenWindowsAutomatically = false
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            useWideViewPort = true
            // Render at the declared CSS width instead of zooming the page to fit.
            // Emails narrower than the screen used to be blown up by overview mode,
            // and wide ones were scaled unpredictably. EMAIL_FIT_SCRIPT now owns
            // shrink-to-fit, while CSS owns the centred reading column.
            loadWithOverviewMode = false
            // Body HTML is local; without this WebView grows an unbounded
            // disk cache of remote images (~12 MB observed).
            cacheMode = WebSettings.LOAD_NO_CACHE
            blockNetworkImage = !loadImages()
        }
        wv.webViewClient = GuardedClient(loadImages, onLink)
        // A body WebView is laid out at its full content height inside a
        // NestedScrollView. If it takes focus on tap, Android scrolls both the
        // WebView and the outer ScrollView to reveal the focused link — the whole
        // message jumps as soon as a link is touched. Links still fire through
        // shouldOverrideUrlLoading without focus, so keep the WebView out of the
        // focus chain.
        wv.isFocusable = false
        wv.isFocusableInTouchMode = false
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
