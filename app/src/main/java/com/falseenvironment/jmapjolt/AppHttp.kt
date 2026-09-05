package com.falseenvironment.jmapjolt

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The app's single OkHttp stack.
 *
 * Every HTTP caller shares this instance so they share one connection pool,
 * dispatcher and thread set; before this each area built its own client (mail
 * transport, SSE, calendars, contacts) and two more used raw HttpURLConnection,
 * so timeouts and redirect policy drifted apart per feature.
 *
 * Callers that need different limits derive one with [OkHttpClient.newBuilder]
 * — that shares the pool rather than starting a second stack. Redirects follow
 * OkHttp's default (on); every call that carries Basic auth turns them off
 * itself, so credentials are never replayed to another host on a 30x.
 */
internal object AppHttp {

    private const val CONNECT_TIMEOUT_SECONDS = 10L
    private const val READ_TIMEOUT_SECONDS = 30L
    private const val WRITE_TIMEOUT_SECONDS = 30L

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Variant that never follows a redirect. For requests carrying credentials:
     * OkHttp would otherwise replay the manually set Authorization header on a
     * same-host hop, and a redirect is the server's choice, not ours.
     */
    val noRedirects: OkHttpClient by lazy {
        client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
