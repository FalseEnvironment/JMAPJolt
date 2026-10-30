package com.falseenvironment.jmapjolt

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.toColorInt

/** State that survives folder switches and drives the banner/pill de-duplication. */
internal class ConnectionUiState {
    var problem: ConnectionProblem? = null
    var lastProblem: ConnectionProblem? = null
    var lastNoticeAt: Long = 0L
    var manualRefreshPending: Boolean = false
    val log = ArrayDeque<String>()
}

private const val CONNECTION_NOTICE_MIN_INTERVAL_MS = 1_500L
private const val MAX_CONNECTION_LOG_ENTRIES = 50
private const val MAX_CONNECTION_LOG_CHARS = 24_000

internal fun MainActivity.currentNetworkStatus(): NetworkStatus {
    val manager = getSystemService(ConnectivityManager::class.java) ?: return NetworkStatus.ONLINE
    val network = manager.activeNetwork ?: return NetworkStatus.NO_NETWORK
    val caps = manager.getNetworkCapabilities(network) ?: return NetworkStatus.NO_NETWORK
    if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
        return NetworkStatus.NO_INTERNET
    }
    // A VPN can carry a working server route without being validated by the
    // system's internet probe, so do not call that "no internet".
    return if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ||
        caps.hasCapability(NetworkCapabilities.TRANSPORT_VPN)
    ) {
        NetworkStatus.ONLINE
    } else {
        NetworkStatus.NO_INTERNET
    }
}

internal fun MainActivity.registerNetworkCallback() {
    if (networkCallback != null) return
    val manager = getSystemService(ConnectivityManager::class.java) ?: return
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread {
                if (connectionUi.problem == null) return@runOnUiThread
                refreshInboxNow()
            }
        }
    }
    runCatching { manager.registerDefaultNetworkCallback(callback) }
        .onSuccess { networkCallback = callback }
}

internal fun MainActivity.unregisterNetworkCallback() {
    val callback = networkCallback ?: return
    runCatching {
        getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
    }
    networkCallback = null
}

/**
 * Records one failed refresh: keeps the banner up, appends to the Details log, and
 * shows the transient pill only on a state change or an explicit user retry.
 */
internal fun MainActivity.notifySyncFailure(error: Throwable, userInitiated: Boolean = false) {
    if (DemoInbox.isEnabled(this) || connectedAccount == null) return
    val network = currentNetworkStatus()
    val failure = classifyConnectionFailure(error, network)
    appendConnectionLog(failure, error)
    showConnectionBanner(failure)

    val now = SystemClock.elapsedRealtime()
    val manual = userInitiated || connectionUi.manualRefreshPending
    connectionUi.manualRefreshPending = false
    val changed = failure.problem != connectionUi.lastProblem
    if (now - connectionUi.lastNoticeAt >= CONNECTION_NOTICE_MIN_INTERVAL_MS &&
        (manual || changed)
    ) {
        connectionUi.lastNoticeAt = now
        showThemedSnackbar(
            connectionProblemMessage(failure),
            actionLabel =
                if (failure.problem == ConnectionProblem.AUTH) {
                    getString(R.string.connection_action_sign_in)
                } else {
                    getString(R.string.connection_action_retry)
                },
        ) { retryConnection() }
    }
    connectionUi.lastProblem = failure.problem
}

/** Clears the banner after a successful refresh and confirms recovery once. */
internal fun MainActivity.notifySyncSuccess() {
    connectionUi.lastProblem = null
    connectionUi.manualRefreshPending = false
    if (connectionUi.problem == null) return
    connectionUi.problem = null
    hideConnectionBanner()
    showThemedSnackbar(getString(R.string.connection_back_online))
}

internal fun MainActivity.retryConnection() {
    if (connectionUi.problem == ConnectionProblem.AUTH) {
        showLoginScreen()
        return
    }
    connectionUi.manualRefreshPending = true
    refreshInboxNow()
}

internal fun MainActivity.showConnectionBanner(failure: ConnectionFailure) {
    val changed = connectionUi.problem != failure.problem
    connectionUi.problem = failure.problem
    connectionBannerText.text = connectionProblemMessage(failure)
    connectionBannerRetry.text =
        if (failure.problem == ConnectionProblem.AUTH) {
            getString(R.string.connection_action_sign_in)
        } else {
            getString(R.string.connection_action_retry)
        }
    connectionBannerIcon.setImageResource(
        if (failure.problem == ConnectionProblem.OFFLINE ||
            failure.problem == ConnectionProblem.NO_INTERNET
        ) {
            R.drawable.ic_lucide_wifi_off
        } else {
            R.drawable.ic_lucide_info
        }
    )
    if (changed) {
        styleConnectionBanner()
        if (connectionBanner.visibility != View.VISIBLE) {
            connectionBanner.visibility = View.VISIBLE
            connectionBanner.announceForAccessibility(connectionBannerText.text)
        }
        updateEmptyState()
    }
}

internal fun MainActivity.hideConnectionBanner() {
    connectionBanner.visibility = View.GONE
    updateEmptyState()
}

/** Called by the theme pass; every colour comes from the active theme + accent. */
internal fun MainActivity.styleConnectionBanner() {
    val t = tokens
    val accent = t.accentOnGround(currentAccentColor.toColorInt())
    connectionBanner.setBackgroundColor(t.surface)
    connectionBannerText.setTextColor(t.textPrimary)
    connectionBannerRetry.setTextColor(accent)
    connectionBannerDetail.setTextColor(t.textSecondary)
    connectionBannerIcon.imageTintList = ColorStateList.valueOf(accent)
}

internal fun MainActivity.connectionProblemMessage(failure: ConnectionFailure): String =
    when (failure.problem) {
        ConnectionProblem.OFFLINE -> getString(R.string.connection_offline)
        ConnectionProblem.NO_INTERNET -> getString(R.string.connection_no_internet)
        ConnectionProblem.UNREACHABLE -> getString(R.string.connection_unreachable)
        ConnectionProblem.TIMEOUT -> getString(R.string.connection_timeout)
        ConnectionProblem.TLS -> getString(R.string.connection_tls)
        ConnectionProblem.SERVER_ERROR ->
            failure.httpStatus?.let { getString(R.string.connection_server_error, it) }
                ?: getString(R.string.connection_generic)
        ConnectionProblem.REQUEST_REJECTED ->
            failure.httpStatus?.let { getString(R.string.connection_request_rejected, it) }
                ?: getString(R.string.connection_generic)
        ConnectionProblem.RATE_LIMIT -> getString(R.string.connection_rate_limited)
        ConnectionProblem.AUTH -> getString(R.string.connection_auth_expired)
        ConnectionProblem.UNKNOWN -> getString(R.string.connection_generic)
    }

private fun MainActivity.appendConnectionLog(
    failure: ConnectionFailure,
    error: Throwable,
) {
    // Readable header instead of a timestamp/network dump: the banner already says
    // what happened, the lines below say why.
    val entry = connectionProblemMessage(failure) + "\n" +
        describeFailureChain(error).take(4_000)
    // A repeated polling failure adds no information; keep the first occurrence.
    if (connectionUi.log.lastOrNull() == entry) return
    connectionUi.log.addLast(entry)
    while (connectionUi.log.size > MAX_CONNECTION_LOG_ENTRIES) connectionUi.log.removeFirst()
    while (connectionUi.log.size > 1 &&
        connectionUi.log.sumOf { it.length } > MAX_CONNECTION_LOG_CHARS
    ) {
        connectionUi.log.removeFirst()
    }
}

/** Themed sheet with the full local error history; selectable so it can be copied. */
internal fun MainActivity.showConnectionLogDialog() {
    val dp = resources.displayMetrics.density
    val body = TextView(this).apply {
        text = if (connectionUi.log.isEmpty()) {
            getString(R.string.connection_details_empty)
        } else {
            connectionUi.log.joinToString("\n\n")
        }
        textSize = 12f
        typeface = Typeface.MONOSPACE
        setTextColor(tokens.textPrimary)
        setTextIsSelectable(true)
        setPadding((20 * dp).toInt(), (10 * dp).toInt(), (20 * dp).toInt(), (16 * dp).toInt())
    }
    val maxHeight = minOf(
        (360 * dp).toInt(),
        (resources.displayMetrics.heightPixels * 0.6f).toInt(),
    )
    val dialogWidth = (resources.displayMetrics.widthPixels * 0.9f).toInt()
    // Measure at the final dialog width: a short log gets a compact sheet, a long
    // one is capped at maxHeight and scrolls instead of filling the screen.
    body.measure(
        View.MeasureSpec.makeMeasureSpec(dialogWidth, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
    )
    val scroll = ScrollView(this).apply {
        addView(
            body,
            android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        isFillViewport = false
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (body.measuredHeight > maxHeight) maxHeight
            else ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16 * dp
            setColor(getDialogBackgroundColor())
        }
        elevation = 8 * dp
    }
    root.addView(TextView(this).apply {
        text = getString(R.string.connection_details_title)
        textSize = 13f
        setTextColor(tokens.textSecondary)
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.08f
        setPadding((20 * dp).toInt(), (16 * dp).toInt(), (20 * dp).toInt(), (8 * dp).toInt())
    })
    root.addView(View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        setBackgroundColor(tokens.divider)
    })
    root.addView(scroll)
    val close = TextView(this).apply {
        text = getString(R.string.connection_details_close)
        textSize = 14f
        setTextColor(currentAccentColor.toColorInt())
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.END
        setPadding((20 * dp).toInt(), (10 * dp).toInt(), (20 * dp).toInt(), (12 * dp).toInt())
        isClickable = true
        isFocusable = true
    }
    root.addView(close)

    val dialog = AlertDialog.Builder(this).setView(root).create()
    close.setOnClickListener { dialog.dismiss() }
    dialog.show()
    dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    dialog.window?.attributes?.let { lp ->
        lp.width = dialogWidth
        dialog.window?.attributes = lp
    }
}
