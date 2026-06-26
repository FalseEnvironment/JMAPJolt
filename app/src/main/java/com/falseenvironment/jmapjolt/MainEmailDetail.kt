package com.falseenvironment.jmapjolt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.Html
import android.text.Layout
import android.text.Spannable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.text.style.AlignmentSpan
import android.text.style.BulletSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.Log
import android.util.Patterns
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.Menu
import android.widget.HorizontalScrollView
import android.widget.PopupMenu
import android.graphics.PorterDuff
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import androidx.appcompat.widget.SwitchCompat
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.core.view.GravityCompat
import androidx.core.widget.CompoundButtonCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.viewpager2.widget.ViewPager2
import coil.load
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.unifiedpush.android.connector.INSTANCE_DEFAULT
import org.unifiedpush.android.connector.UnifiedPush

internal fun MainActivity.setupEmailDetailView() {
    val activity = this
    val dp = resources.displayMetrics.density
    val barHeight = (60 * dp).toInt()
    // FrameLayout so the action row is a top overlay over the content: hiding it never
    // reflows the WebView (no flicker) and leaves the email background (no grey gap).
    emailDetailContainer =
            EmailDetailContainer(this).apply {
                id = View.generateViewId()
                visibility = View.GONE
                topZoneHeight = barHeight
                onSwipeDrag = { dx -> onDetailSwipeDrag(dx) }
                onSwipeEnd = { dx, vx -> onDetailSwipeEnd(dx, vx) }
                layoutParams =
                        FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                        )
                setBackgroundColor("#1F1F1F".toColorInt())
            }
    // Content column sits below the overlay bar via a top inset equal to the bar height.
    detailBody =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding(0, barHeight, 0, 0)
            }
    detailBarHeight = barHeight
    // Pinned Gmail-style header: subject + star on top, then sender/date with
    // "to me" expander, reply and an overflow menu for the remaining actions.
    val headerWrap =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor("#1F1F1F".toColorInt())
                minimumHeight = barHeight
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding((16 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt())
            }
    detailHeaderRow = headerWrap
    detailFrom =
            TextView(this).apply {
                setTextColor("#FFFFFF".toColorInt())
                textSize = 14f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxWidth = (resources.displayMetrics.widthPixels * 0.52f).toInt()
            }

    fun detailActionIcon(iconRes: Int, desc: String, onClick: (DisplayEmail) -> Unit): ImageView =
            ImageView(this).apply {
                setImageResource(iconRes)
                contentDescription = desc
                val sz = (40 * dp).toInt()
                layoutParams = LinearLayout.LayoutParams(sz, sz)
                val p = (9 * dp).toInt()
                setPadding(p, p, p, p)
                scaleType = ImageView.ScaleType.FIT_CENTER
                isClickable = true
                isFocusable = true
                background = ContextCompat.getDrawable(
                    activity,
                    android.util.TypedValue().also {
                        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true)
                    }.resourceId
                )
                setOnClickListener { currentDetailEmail?.let(onClick) }
            }

    detailReplyButton = detailActionIcon(R.drawable.ic_lucide_reply, "Reply") { startReply(it) }
    // Legacy action icons now live in the overflow menu; views kept for the tinting code.
    detailForwardButton = detailActionIcon(R.drawable.ic_lucide_forward, "Forward") { startForward(it) }
    detailArchiveButton = detailActionIcon(R.drawable.ic_lucide_archive, "Archive") { archiveDetailEmail(it) }
    detailTrashButton = detailActionIcon(R.drawable.ic_lucide_trash, "Delete") { trashDetailEmail(it) }
    detailMoveButton = detailActionIcon(R.drawable.ic_lucide_folder_input, "Move to") { moveDetailEmail(it) }
    detailStarButton = detailActionIcon(R.drawable.ic_lucide_star, "Favorite") { toggleDetailFavorite(it) }
    detailMoreButton = detailActionIcon(R.drawable.ic_lucide_more_vertical, "More") { showDetailOverflowMenu() }
    // Row of labels next to the star button
    detailLabelRowView = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        background = ContextCompat.getDrawable(
            activity,
            android.util.TypedValue().also {
                theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true)
            }.resourceId
        )
        setOnClickListener { currentDetailEmail?.let { showLabelPicker(listOf(it.id)) } }
        val p = (4 * dp).toInt()
        setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.marginStart = (4 * dp).toInt()
        }
        repeat(3) {
            addView(ImageView(activity).apply {
                setImageResource(R.drawable.ic_lucide_tag)
                val sz = (20 * dp).toInt()
                layoutParams = LinearLayout.LayoutParams(sz, sz).also { it.marginEnd = (2 * dp).toInt() }
                scaleType = ImageView.ScaleType.FIT_CENTER
            })
        }
        addView(TextView(activity).apply {
            text = "+"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.marginEnd = (2 * dp).toInt()
            }
        })
        visibility = View.GONE
    }

    // Row 1: subject + favorite star.
    detailSubject = TextView(this).apply {
        textSize = 18f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    headerWrap.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        addView(detailSubject)
        addView(detailLabelRowView)
        addView(detailStarButton)
    })

    // Row 2: sender (bold) + relative date in gray, "to me" expander below;
    // reply and overflow pinned at the right.
    detailDate = TextView(this).apply {
        textSize = 12f
        setTextColor("#9E9E9E".toColorInt())
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.marginStart = (8 * dp).toInt() }
    }
    detailToText = TextView(this).apply {
        textSize = 12f
        setTextColor("#9E9E9E".toColorInt())
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setOnClickListener { showDetailAddressDialog() }
    }
    val senderCol = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(detailFrom)
            addView(detailDate)
        })
        addView(detailToText)
    }
    headerWrap.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = (6 * dp).toInt() }
        addView(senderCol)
        addView(detailReplyButton)
        addView(detailTrashButton)
        addView(detailMoreButton)
    })


    detailWebView =
            android.webkit.WebView(this).apply {
                layoutParams =
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                setBackgroundColor(Color.WHITE)
                overScrollMode = View.OVER_SCROLL_NEVER
                isNestedScrollingEnabled = false
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                @Suppress("DEPRECATION")
                settings.allowUniversalAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                settings.allowFileAccessFromFileURLs = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                }
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
            }
    detailWebView.webViewClient = object : android.webkit.WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: android.webkit.WebView,
            request: android.webkit.WebResourceRequest
        ): Boolean {
            showLinkConfirmationDialog(request.url.toString())
            return true
        }
        @Suppress("DEPRECATION")
        override fun shouldOverrideUrlLoading(
            view: android.webkit.WebView,
            url: String
        ): Boolean {
            showLinkConfirmationDialog(url)
            return true
        }
        override fun onPageFinished(view: android.webkit.WebView, url: String) {
            super.onPageFinished(view, url)
        }
    }
    detailBody.addView(detailWebView)
    // Weighted spacer: when the body is shorter than the viewport (fillViewport stretches
    // detailBody to viewport height) this absorbs the slack and pushes the attachment
    // footer to the bottom. For tall bodies there is no slack, so attachments follow content.
    detailBody.addView(View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        )
    })
    detailScroll = androidx.core.widget.NestedScrollView(this).apply {
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        isFillViewport = true
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(detailBody)
    }
    // Auto-hide the action row when scrolling down, reveal it when scrolling up.
    val scrollThreshold = (24 * dp).toInt()
    detailScroll.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
        val dy = scrollY - oldScrollY
        when {
            dy > 4 && scrollY > scrollThreshold && !detailBarHidden -> setDetailBarHidden(true)
            dy < -4 && detailBarHidden -> setDetailBarHidden(false)
        }
    }
    emailDetailContainer.addView(detailScroll)
    emailDetailContainer.addView(headerWrap)
    mailboxContainer.addView(emailDetailContainer)
}

internal fun MainActivity.setDetailBarHidden(hidden: Boolean) {
    if (detailBarHidden == hidden) return
    val now = System.currentTimeMillis()
    if (now - detailBarLastToggleMs < 200) return
    detailBarLastToggleMs = now
    detailBarHidden = hidden
    detailHeaderRow.animate().cancel()
    if (hidden) {
        // Cache the measured height so the reveal animation has a distance to travel.
        detailHeaderRow.height.takeIf { it > 0 }?.let { detailBarHeight = it }
        detailHeaderRow.animate().translationY(-detailBarHeight.toFloat()).alpha(0f).setDuration(160).withEndAction {
            detailHeaderRow.visibility = View.GONE
        }.start()
        android.animation.ValueAnimator.ofInt(detailBarHeight, 0).apply {
            duration = 160
            addUpdateListener { detailBody.setPadding(0, it.animatedValue as Int, 0, 0) }
            start()
        }
    } else {
        detailHeaderRow.visibility = View.VISIBLE
        detailHeaderRow.translationY = -detailBarHeight.toFloat()
        detailHeaderRow.alpha = 0f
        detailHeaderRow.animate().translationY(0f).alpha(1f).setDuration(160).start()
        android.animation.ValueAnimator.ofInt(0, detailBarHeight).apply {
            duration = 160
            addUpdateListener { detailBody.setPadding(0, it.animatedValue as Int, 0, 0) }
            start()
        }
    }
}

internal fun MainActivity.detailSwipeTarget(forward: Boolean): DisplayEmail? {
    val current = currentDetailEmail ?: return null
    val idx = emails.indexOfFirst { it.id == current.id }
    if (idx < 0) return null
    return if (forward) emails.getOrNull(idx + 1) else emails.getOrNull(idx - 1)
}

internal fun MainActivity.ensureDetailPreviewPanel(): LinearLayout {
    detailPreviewPanel?.let { return it }
    val wv = android.webkit.WebView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        overScrollMode = View.OVER_SCROLL_NEVER
        settings.javaScriptEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        @Suppress("DEPRECATION")
        settings.allowUniversalAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
    }
    val panel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        setPadding(0, detailBarHeight, 0, 0)
        visibility = View.GONE
        // The preview is display-only: swallow touches while it briefly overlays.
        setOnTouchListener { _, _ -> true }
        addView(wv)
    }
    // Above detailBody, below the pinned header row.
    emailDetailContainer.addView(panel, 1)
    detailPreviewPanel = panel
    detailPreviewWebView = wv
    return panel
}

internal fun MainActivity.prepareDetailPreview(target: DisplayEmail, forward: Boolean) {
    val key = "${target.id}:$forward"
    if (detailPreviewKey == key) return
    detailPreviewKey = key
    val panel = ensureDetailPreviewPanel()
    val wv = detailPreviewWebView ?: return
    val bg = when (currentTheme) {
        "oled" -> "#000000"
        "light" -> "#ffffff"
        else -> "#1a1a1a"
    }.toColorInt()
    panel.setBackgroundColor(bg)
    wv.setBackgroundColor(bg)
    wv.settings.blockNetworkImage =
        !getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE).getBoolean("load_images", false)
    val html = if (target.fullBody.isNotBlank())
        buildHtmlContent(target.fullBody)
    else
        buildSkeletonHtml()
    wv.loadDataWithBaseURL("https://jmapjolt.invalid/email/", html, "text/html", "UTF-8", null)
}

internal fun MainActivity.onDetailSwipeDrag(dx: Float) {
    if (detailSwipeAnimating) return
    val forward = dx < 0
    val target = detailSwipeTarget(forward)
    val resistance = if (target == null) 0.25f else 1f
    detailBody.translationX = dx * resistance
    val w = detailBody.width.toFloat().takeIf { it > 0 }
        ?: resources.displayMetrics.widthPixels.toFloat()
    if (target != null) {
        prepareDetailPreview(target, forward)
        detailPreviewPanel?.let {
            it.visibility = View.VISIBLE
            it.alpha = 1f
            it.translationX = dx + if (forward) w else -w
        }
    } else {
        detailPreviewPanel?.visibility = View.GONE
        detailPreviewKey = null
    }
}

internal fun MainActivity.onDetailSwipeEnd(dx: Float, velocityX: Float) {
    if (detailSwipeAnimating) return
    val w = detailBody.width.toFloat().takeIf { it > 0 }
        ?: resources.displayMetrics.widthPixels.toFloat()
    val dp = resources.displayMetrics.density
    val forward = dx < 0
    val target = detailSwipeTarget(forward)
    val flung = kotlin.math.abs(velocityX) > 800 * dp &&
        (velocityX < 0) == forward  // fling must match the drag direction
    val shouldComplete = target != null && (kotlin.math.abs(dx) > w * 0.30f || flung)

    if (!shouldComplete) {
        detailBody.animate()
            .translationX(0f)
            .setDuration(240)
            .setInterpolator(android.view.animation.OvershootInterpolator(0.8f))
            .start()
        detailPreviewPanel?.let { p ->
            if (p.visibility == View.VISIBLE) {
                p.animate()
                    .translationX(if (forward) w else -w)
                    .setStartDelay(0)
                    .setDuration(240)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(2f))
                    .withEndAction { p.visibility = View.GONE; detailPreviewKey = null }
                    .start()
            }
        }
        return
    }

    detailSwipeAnimating = true
    val exitX = if (forward) -w else w
    prepareDetailPreview(target ?: return, forward)
    val panel = ensureDetailPreviewPanel().also {
        it.visibility = View.VISIBLE
        // Pure fling with no drag events yet: start from fully off-screen.
        if (detailBody.translationX == 0f) it.translationX = if (forward) w else -w
    }
    // Continue at roughly the finger's speed: duration from remaining distance.
    val remaining = kotlin.math.abs(exitX - detailBody.translationX)
    val exitDuration = (remaining / w * 220).toLong().coerceIn(90, 220)
    panel.animate()
        .translationX(0f)
        .setStartDelay(0)
        .setDuration(exitDuration)
        .setInterpolator(android.view.animation.LinearInterpolator())
        .start()
    detailBody.animate()
        .translationX(exitX)
        .setStartDelay(0)
        .setDuration(exitDuration)
        .setInterpolator(android.view.animation.LinearInterpolator())
        .withEndAction {
            detailBody.translationX = 0f
            showEmailDetail(target, fromSwipe = true)
            // Keep the preview overlaid while the real WebView paints the same
            // content underneath, then fade it away: no skeleton flash, no gap.
            panel.animate()
                .alpha(0f)
                .setStartDelay(140)
                .setDuration(160)
                .withEndAction {
                    panel.visibility = View.GONE
                    panel.alpha = 1f
                    detailPreviewKey = null
                    detailSwipeAnimating = false
                }
                .start()
        }
        .start()
}

internal fun MainActivity.showDetailOverflowMenu() {
    val email = currentDetailEmail ?: return
    val inArchive = selectedFolder == R.id.nav_archive
    showSettingsDropdown(
        detailMoreButton,
        listOf(
            if (inArchive) "Unarchive" else getString(R.string.swipe_action_archive),
            "Forward",
            "Move to",
            "Label"
        ),
        -1,
        icons = listOf(
            if (inArchive) R.drawable.ic_lucide_archive_restore else R.drawable.ic_lucide_archive,
            R.drawable.ic_lucide_forward,
            R.drawable.ic_lucide_folder_input,
            R.drawable.ic_lucide_tag
        )
    ) { idx ->
        when (idx) {
            0 -> if (inArchive) unarchiveDetailEmail(email) else archiveDetailEmail(email)
            1 -> startForward(email)
            2 -> moveDetailEmail(email)
            3 -> showLabelPicker(listOf(email.id))
        }
    }
}

internal fun MainActivity.updateDetailLabelIcon() {
    if (!isDetailLabelRowViewInit) return
    val email = currentDetailEmail ?: return
    val rowLabels = labelsOf(email)
    val owned = ownsEmail(email)
    // Not owned: labels are read-only. Show a single gray tag as a disabled
    // affordance (tapping routes through the guarded picker → "switch account").
    val grayTint = if (currentTheme == "light") "#BDBDBD".toColorInt() else "#616161".toColorInt()
    if (rowLabels.isEmpty() && owned) {
        detailLabelRowView.visibility = View.GONE
        return
    }
    detailLabelRowView.visibility = View.VISIBLE
    for (i in 0..2) {
        val iv = detailLabelRowView.getChildAt(i) as ImageView
        val l = rowLabels.getOrNull(i)
        when {
            l != null -> {
                iv.visibility = View.VISIBLE
                iv.imageTintList = ColorStateList.valueOf(
                    if (owned) l.colorHex.toColorInt() else grayTint
                )
            }
            // No labels but not owned → one gray tag at slot 0 as the locked affordance.
            i == 0 && !owned -> {
                iv.visibility = View.VISIBLE
                iv.imageTintList = ColorStateList.valueOf(grayTint)
            }
            else -> iv.visibility = View.GONE
        }
    }
    (detailLabelRowView.getChildAt(3) as TextView).apply {
        visibility = if (rowLabels.size > 3) View.VISIBLE else View.GONE
        val isLight = currentTheme == "light"
        setTextColor(if (isLight) "#757575".toColorInt() else "#9E9E9E".toColorInt())
    }
}

internal fun MainActivity.unarchiveDetailEmail(email: DisplayEmail) {
    val activity = this
    val acc = resolveAccountFor(email) ?: connectedAccount ?: return
    updateFolderCachesForInbox(email)
    closeEmailDetail()
    removeEmailsAnimated(listOf(email.id))
    saveEmailCache()
    Snackbar.make(drawerLayout, "Moved to Inbox", Snackbar.LENGTH_SHORT).show()
    lifecycleScope.launch {
        try {
            val inboxId = resolveMailboxIdByRole(acc, "inbox")
            if (inboxId != null) {
                jmapClient.setMailbox(acc, email.id, inboxId)
                BackgroundEmailSyncReceiver.addToBaseline(activity, acc.email, listOf(email.id))
            }
        } catch (e: Exception) { Log.e(MainActivity.TAG,"detail unarchive failed", e) }
    }
}

internal fun MainActivity.syncDetailHeaderHeight() {
    detailHeaderRow.post {
        val h = detailHeaderRow.height
        if (h > 0 && !detailBarHidden) {
            detailBarHeight = h
            detailBody.setPadding(0, h, 0, 0)
            detailPreviewPanel?.setPadding(0, h, 0, 0)
            emailDetailContainer.topZoneHeight = h
        }
    }
}

internal fun MainActivity.showDetailAddressDialog() {
    val email = currentDetailEmail ?: return
    val dp = resources.displayMetrics.density
    val textColor = if (currentTheme == "light") "#212121".toColorInt() else Color.WHITE
    val subColor = if (currentTheme == "light") "#757575".toColorInt() else "#BDBDBD".toColorInt()

    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val ph = (16 * dp).toInt()
        val pv = (12 * dp).toInt()
        setPadding(ph, pv, ph, pv)
        minimumWidth = (220 * dp).toInt()
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14 * dp
            setColor(getDialogBackgroundColor())
        }
        elevation = 8 * dp
    }
    fun row(label: String, value: String) {
        card.addView(TextView(this).apply {
            text = label
            textSize = 10f
            letterSpacing = 0.08f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(subColor)
        })
        card.addView(TextView(this).apply {
            text = value
            textSize = 13f
            setTextColor(textColor)
            setTextIsSelectable(true)
            maxWidth = (resources.displayMetrics.widthPixels * 0.75f).toInt()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (8 * dp).toInt() }
        })
    }
    row("FROM", listOf(email.from, email.fromEmail).filter { it.isNotBlank() }.distinct().joinToString(" · "))
    row("TO", email.toEmail.ifBlank { email.accountEmail.ifBlank { "me" } })
    if (email.receivedAt > 0) {
        row("DATE", java.text.DateFormat.getDateTimeInstance().format(java.util.Date(email.receivedAt)))
    }

    val pw = android.widget.PopupWindow(
        card,
        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
        true
    ).apply {
        elevation = 10 * dp
        isOutsideTouchable = true
    }
    pw.showAsDropDown(detailToText, 0, (4 * dp).toInt())
    // MD3 menu motion: scale-in from the anchor corner with a fade.
    card.alpha = 0f
    card.scaleX = 0.86f
    card.scaleY = 0.78f
    card.post {
        card.pivotX = card.width * 0.15f
        card.pivotY = 0f
        card.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(200)
            .setInterpolator(android.view.animation.DecelerateInterpolator(2.5f))
            .start()
    }
}

internal fun MainActivity.sanitizeEmailHtml(html: String): String {
    return html
        .replace(Regex("<script[\\s>][\\s\\S]*?</script\\s*>", RegexOption.IGNORE_CASE), "")
        .replace(Regex("<script\\s*/?>", RegexOption.IGNORE_CASE), "")
        // Embedding/navigation vectors: strip the tags, keep inner text content.
        .replace(Regex("</?(iframe|object|embed|frame|frameset|base|applet|form)\\b[^>]*>", RegexOption.IGNORE_CASE), "")
        .replace(Regex("<meta\\b[^>]*http-equiv\\s*=\\s*[\"']?refresh[^>]*>", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""(\s)on[a-zA-Z]+\s*=\s*"[^"]*""""), "$1")
        .replace(Regex("""(\s)on[a-zA-Z]+\s*=\s*'[^']*'"""), "$1")
        .replace(Regex("""(\s)on[a-zA-Z]+\s*=[^\s>]+"""), "$1")
        .replace(Regex("""(\s)srcdoc\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)""", RegexOption.IGNORE_CASE), "$1")
        // Block script-bearing URI schemes; data: stays allowed in src so inline images keep working.
        .replace(Regex("""(href|action|formaction)\s*=\s*["']?\s*(javascript|data|vbscript):[^"'\s>]*""", RegexOption.IGNORE_CASE), "$1=\"#\"")
        .replace(Regex("""(src|background)\s*=\s*["']?\s*(javascript|vbscript):[^"'\s>]*""", RegexOption.IGNORE_CASE), "$1=\"\"")
        .replace(Regex("""expression\s*\(""", RegexOption.IGNORE_CASE), "no-expression(")
}

internal fun MainActivity.looksLikeHtml(body: String): Boolean = MainActivity.HTML_MARKUP_REGEX.containsMatchIn(body)

internal fun MainActivity.buildSkeletonHtml(): String {
    val isDark = currentTheme == "gray" || currentTheme == "oled" || currentTheme == "violet"
    val bg = when (currentTheme) {
        "light"  -> "#F6F6F8"
        "oled"   -> "#000000"
        "violet" -> "#160E24"
        else     -> "#212126"
    }
    val base = if (currentTheme == "oled") "#111111" else if (isDark) "#2a2a2a" else "#e0e0e0"
    val shine = if (currentTheme == "oled") "#1e1e1e" else if (isDark) "#3a3a3a" else "#f0f0f0"
    return """<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>
*{margin:0;padding:0;box-sizing:border-box}
body{background:$bg;padding:20px}
.s{background:linear-gradient(90deg,$base 25%,$shine 50%,$base 75%);background-size:300% 100%;animation:sh 1.4s infinite;border-radius:6px;height:13px;margin-bottom:14px}
.w100{width:100%}.w85{width:85%}.w70{width:70%}.w55{width:55%}.w40{width:40%}.w30{width:30%}
.gap{height:24px}
@keyframes sh{0%{background-position:100% 0}100%{background-position:-100% 0}}
</style></head><body>
<div class="s w85"></div>
<div class="s w70"></div>
<div class="s w100"></div>
<div class="gap"></div>
<div class="s w100"></div>
<div class="s w85"></div>
<div class="s w55"></div>
<div class="gap"></div>
<div class="s w100"></div>
<div class="s w70"></div>
<div class="s w100"></div>
<div class="s w40"></div>
<div class="gap"></div>
<div class="s w85"></div>
<div class="s w30"></div>
</body></html>"""
}

internal fun MainActivity.buildHtmlContent(rawBodyIn: String, subject: String = ""): String {
    // Inline cid: images are shown as attachment cards below the body; strip the in-body
    // <img src="cid:..."> tags so a broken-image placeholder is not rendered in the content.
    val rawBody = rawBodyIn.replace(
        Regex("<img\\b[^>]*\\bsrc\\s*=\\s*[\"']cid:[^>]*>", RegexOption.IGNORE_CASE), ""
    )
    val isDark = currentTheme == "gray" || currentTheme == "oled" || currentTheme == "violet"
    val bgColor = when (currentTheme) {
        "light"  -> "#F6F6F8"
        "oled"   -> "#000000"
        "violet" -> "#160E24"
        else     -> "#212126"
    }
    val textColor = if (isDark) "#e0e0e0" else "#212121"
    val linkColor = currentAccentColor

    // Subject is rendered inside the scrollable WebView content so it scrolls away,
    // while the action row above stays pinned.
    val subjectHeading = if (subject.isNotBlank())
        "<div style=\"font-size:18px;font-weight:700;color:$textColor;padding:14px 12px 6px;line-height:1.3\">" +
            android.text.TextUtils.htmlEncode(subject) + "</div>"
    else ""

    val darkCss = if (isDark) """
        <style id="jj-dark">
        html,body{background-color:$bgColor!important;color:$textColor!important}
        *:not(img):not(svg):not(video){background-color:transparent!important;color:$textColor!important;border-color:#444!important}
        a,a *{color:$linkColor!important}
        table{background-color:$bgColor!important}
        td,th,tr{background-color:transparent!important;color:$textColor!important}
        img{filter:brightness(.9) contrast(1.05)}
        </style>
    """.trimIndent() else ""

    // Override quote/blockquote left bar to use the theme accent color (was grey),
    // detached a few px from the edge. Applies at display-time to ALL emails,
    // including ones already received with grey bars baked into their HTML.
    val accentQuoteCss = """
        <style id="jj-accent-quote">
        .quoted-html-island{border-left-color:$currentAccentColor!important;margin-left:4px!important}
        blockquote{border-left:3px solid $currentAccentColor!important;margin-left:4px!important;padding-left:12px!important}
        details.jj-quote-collapse{margin:6px 0}
        details.jj-quote-collapse>summary{display:flex;align-items:center;gap:7px;color:$currentAccentColor;cursor:pointer;font-size:13px;font-weight:600;padding:5px 0;list-style:none;-webkit-user-select:none;user-select:none}
        details.jj-quote-collapse>summary::-webkit-details-marker{display:none}
        details.jj-quote-collapse>summary .jj-fav{width:14px;height:14px;border-radius:50%;background:$currentAccentColor;flex:0 0 auto;opacity:.85}
        details.jj-quote-collapse>summary .jj-chev{display:inline-block;transition:transform .15s ease;flex:0 0 auto}
        details.jj-quote-collapse[open]>summary .jj-chev{transform:rotate(90deg)}
        details.jj-quote-collapse>summary .jj-lbl::after{content:"Show quoted message"}
        details.jj-quote-collapse[open]>summary .jj-lbl::after{content:"Hide quoted message"}
        </style>
    """.trimIndent()

    val isFullDoc = rawBody.contains("<html", ignoreCase = true)
    val isFragment = !isFullDoc && looksLikeHtml(rawBody)
    return if (isFullDoc) {
        val body = collapseDeepQuotes(sanitizeEmailHtml(rawBody))
        var html = body
        if (!html.contains("viewport", ignoreCase = true))
            html = html.replaceFirst("<head", "<head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=3.0\">", ignoreCase = true)
        // Insert the subject right after the opening <body> tag (fallback: prepend).
        if (subjectHeading.isNotEmpty()) {
            val bodyIdx = html.indexOf("<body", ignoreCase = true)
            val gt = if (bodyIdx >= 0) html.indexOf('>', bodyIdx) else -1
            html = if (gt >= 0) html.substring(0, gt + 1) + subjectHeading + html.substring(gt + 1)
                   else subjectHeading + html
        }
        if (isDark) html
            .replaceFirst("<html", "<html style=\"background-color:$bgColor\"", ignoreCase = true)
            .replaceFirst("</head>", "<meta name=\"color-scheme\" content=\"dark\">$darkCss$accentQuoteCss</head>", ignoreCase = true)
            .replaceFirst("<body", "<body style=\"background-color:$bgColor;color:$textColor\"", ignoreCase = true)
        else html.let { doc ->
            if (doc.contains("</head>", ignoreCase = true))
                doc.replaceFirst("</head>", "$accentQuoteCss</head>", ignoreCase = true)
            else doc.replaceFirst("<body", "$accentQuoteCss<body", ignoreCase = true)
        }
    } else if (isFragment) {
        // HTML fragment (no <html> root, e.g. JMAP htmlBody parts or this app's replies).
        // Wrap it in a styled document and render as HTML instead of escaping the markup.
        val body = collapseDeepQuotes(sanitizeEmailHtml(rawBody))
        val colorScheme = if (isDark) "dark" else "light"
        "<html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=3.0\"><meta name=\"color-scheme\" content=\"$colorScheme\">$darkCss$accentQuoteCss<style>body{color:$textColor;background:$bgColor;font-family:-apple-system,sans-serif;word-wrap:break-word;padding:12px;margin:0;max-width:100%;box-sizing:border-box}img{max-width:100%;height:auto}a{color:$linkColor}</style></head><body>$subjectHeading$body</body></html>"
    } else {
        // Plain text: escape HTML entities, then style quoted lines (lines starting with ">")
        val quoteColor = if (isDark) "#616161" else "#9E9E9E"
        val escaped = rawBody
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        val lines = escaped.split("\n").joinToString("<br>") { line ->
            if (line.trimStart().startsWith("&gt;"))
                "<span style=\"color:$quoteColor\">$line</span>"
            else
                line
        }
        "<html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=3.0\">$darkCss$accentQuoteCss<style>body{color:$textColor;background:$bgColor;font-family:-apple-system,sans-serif;word-wrap:break-word;padding:12px;margin:0;max-width:100%;box-sizing:border-box}img{max-width:100%;height:auto}</style></head><body>$subjectHeading$lines</body></html>"
    }
}

internal fun MainActivity.collapseDeepQuotes(html: String, threshold: Int = 4): String {
    val tagRegex = Regex("<(/?)(div|blockquote)\\b([^>]*)>", RegexOption.IGNORE_CASE)
    // Frame per open div/blockquote: whether it is a quote container + the matching insert.
    data class Frame(val isQuote: Boolean, val collapsedRoot: Boolean)
    val stack = ArrayDeque<Frame>()
    val inserts = mutableListOf<Pair<Int, String>>()  // (index, text-to-insert)
    var quoteDepth = 0
    var collapsedActive = false  // a <details> is already open above us
    val openDetails = "<details class=\"jj-quote-collapse\"><summary>" +
        "<span class=\"jj-chev\">▸</span><span class=\"jj-fav\"></span><span class=\"jj-lbl\"></span>" +
        "</summary>"

    for (m in tagRegex.findAll(html)) {
        val closing = m.groupValues[1] == "/"
        val tag = m.groupValues[2].lowercase()
        val attrs = m.groupValues[3]
        // Self-closing (e.g. <div .../>) opens and closes nothing structural — skip.
        if (!closing && attrs.trimEnd().endsWith("/")) continue

        if (!closing) {
            val isQuote = tag == "blockquote" ||
                Regex("class\\s*=\\s*[\"'][^\"']*quoted-html-island", RegexOption.IGNORE_CASE).containsMatchIn(attrs)
            if (isQuote) quoteDepth++
            val crossesThreshold = isQuote && !collapsedActive && quoteDepth == threshold + 1
            if (crossesThreshold) {
                inserts.add(m.range.first to openDetails)
                collapsedActive = true
            }
            stack.addLast(Frame(isQuote, crossesThreshold))
        } else {
            val frame = stack.removeLastOrNull() ?: continue
            if (frame.isQuote) quoteDepth--
            if (frame.collapsedRoot) {
                inserts.add((m.range.last + 1) to "</details>")
                collapsedActive = false
            }
        }
    }
    if (inserts.isEmpty()) return html
    val sb = StringBuilder(html)
    for ((idx, text) in inserts.sortedByDescending { it.first }) sb.insert(idx, text)
    return sb.toString()
}

internal fun MainActivity.updateDetailStarIcon(isFavorite: Boolean) {
    val color = if (isFavorite) currentAccentColor.toColorInt()
                else if (currentTheme == "light") "#9E9E9E".toColorInt() else "#888888".toColorInt()
    detailStarButton.imageTintList = ColorStateList.valueOf(color)
}

internal fun MainActivity.toggleDetailFavorite(email: DisplayEmail) {
    val newFav = !email.isFavorite
    email.isFavorite = newFav
    emails.find { it.id == email.id }?.isFavorite = newFav
    baseEmails.find { it.id == email.id }?.isFavorite = newFav
    optimisticFavorite[email.id] = newFav
    updateFolderCachesForFavorite(email.copy(), newFav)
    updateDetailStarIcon(newFav)
    detailStarButton.animateTap()
    // Only one row changed: a targeted rebind avoids re-running favicon
    // jobs and bind allocations for every visible row.
    val changedPos = emails.indexOfFirst { it.id == email.id }
    if (changedPos >= 0) emailAdapter.notifyItemChanged(changedPos)
    else emailAdapter.notifyDataSetChanged()
    saveEmailCache()
    val acc = resolveAccountFor(email) ?: connectedAccount ?: return
    lifecycleScope.launch {
        try { jmapClient.setFavorite(acc, email.id, newFav) }
        catch (e: Exception) { Log.e(MainActivity.TAG,"detail star failed", e) }
    }
}

internal fun MainActivity.archiveDetailEmail(email: DisplayEmail) {
    val acc = resolveAccountFor(email) ?: connectedAccount ?: return
    updateFolderCachesForMove(email, R.id.nav_archive)
    closeEmailDetail()
    removeEmailsAnimated(listOf(email.id))
    saveEmailCache()
    Snackbar.make(drawerLayout, "Archived", Snackbar.LENGTH_SHORT).show()
    lifecycleScope.launch {
        try {
            val archiveId = resolveOrCreateArchive(acc)
            if (archiveId != null) jmapClient.setMailbox(acc, email.id, archiveId)
        } catch (e: Exception) { Log.e(MainActivity.TAG,"detail archive failed", e) }
    }
}

internal fun MainActivity.trashDetailEmail(email: DisplayEmail) {
    val acc = resolveAccountFor(email) ?: connectedAccount ?: return
    // Deleting from Trash is permanent.
    if (selectedFolder == R.id.nav_trash) {
        closeEmailDetail()
        confirmPermanentDelete(acc, listOf(email.id))
        return
    }
    updateFolderCachesForMove(email, R.id.nav_trash)
    closeEmailDetail()
    removeEmailsAnimated(listOf(email.id))
    saveEmailCache()
    Snackbar.make(drawerLayout, "Moved to Trash", Snackbar.LENGTH_SHORT).show()
    lifecycleScope.launch {
        try {
            val trashId = jmapClient.resolveMailboxIdByRole(acc, "trash")
            if (trashId != null) jmapClient.setMailbox(acc, email.id, trashId)
        } catch (e: Exception) { Log.e(MainActivity.TAG,"detail trash failed", e) }
    }
}

internal fun MainActivity.moveDetailEmail(email: DisplayEmail) {
    val acc = resolveAccountFor(email) ?: connectedAccount ?: return
    val ids = listOf(email.id)
    val excludedRoles = buildList {
        add("drafts"); add("trash")
        if (selectedFolder == R.id.nav_inbox || selectedFolder == R.id.nav_unified_inbox) add("inbox")
        // Favorites live in the inbox already: moving there would be a no-op.
        if (selectedFolder == R.id.nav_favourite) add("inbox")
    }
    fun present(mailboxes: List<JMapClient.MailboxInfo>) {
        val filtered = mailboxes.filter { it.role?.lowercase() !in excludedRoles }
        // Stay on the email while picking; leave it only once a folder is chosen.
        if (filtered.isNotEmpty())
            showMoveLabelPicker(filtered, ids, null, setOf("sent"), onPicked = { closeEmailDetail() })
    }
    val cached = mailboxCache
    if (cached != null) {
        present(cached)
        lifecycleScope.launch {
            runCatching { jmapClient.fetchMailboxes(acc) }.getOrNull()?.let { mailboxCache = it }
        }
    } else {
        lifecycleScope.launch {
            val mailboxes = jmapClient.fetchMailboxes(acc)
            mailboxCache = mailboxes
            present(mailboxes)
        }
    }
}

internal fun MainActivity.closeEmailDetail() {
    // Hard-clear WebView immediately so next open starts blank
    detailWebView.stopLoading()
    detailWebView.loadDataWithBaseURL("https://jmapjolt.invalid/email/","", "text/html", "UTF-8", null)
    currentDetailEmail = null
    emailDetailContainer.animateScreenOutBack()
    mailSwipeRefresh.visibility = View.VISIBLE
    fabCompose.animateFabIn()
    isShowingEmailDetail = false
    setDrawerIndicator(true)
    supportActionBar?.setDisplayHomeAsUpEnabled(false)
    drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
    drawerToggle.syncState()
    applyNavIconTint(getOnAccentColor())
    updateCustomTopBar(getCurrentMailboxTitle(), inMailbox = true)
    if (isSearchActive) searchChipsScroll.visibility = View.VISIBLE
}

