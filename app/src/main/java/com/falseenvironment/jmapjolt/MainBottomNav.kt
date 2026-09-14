package com.falseenvironment.jmapjolt

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import androidx.interpolator.view.animation.FastOutSlowInInterpolator

// Bottom navigation bar for the app's top-level areas. Calendar, Contacts and Settings
// used to sit at the end of the folder drawer, mixed with mail destinations; the drawer
// now lists mail only and these four areas are one tap away from every root screen.

internal enum class BottomNavTab(val iconRes: Int, val labelRes: Int) {
    MAIL(R.drawable.ic_lucide_mail, R.string.nav_mail),
    CALENDAR(R.drawable.ic_lucide_calendar, R.string.calendar_title),
    CONTACTS(R.drawable.ic_lucide_user, R.string.contacts_title),
    SETTINGS(R.drawable.ic_lucide_settings, R.string.settings_title),
}

private const val INDICATOR_WIDTH_DP = 56
private const val INDICATOR_HEIGHT_DP = 30
private const val ICON_SIZE_DP = 22

/**
 * Minimum gap between backend refreshes that a tab starts just by being shown. Explicit
 * actions (pull to refresh, saving an event or contact) are not throttled.
 */
internal const val TAB_SYNC_MIN_INTERVAL_MS = 60_000L

private const val PILL_SWOOSH_DURATION_MS = 380L
private const val TAB_FADE_MS = 120L
private const val TAB_FADE_START_ALPHA = 0.6f
// Share of the travel the leading edge finishes early and the trailing edge starts late:
// the pill stretches towards the new tab, then catches up.
private const val PILL_STRETCH = 0.35f

/**
 * Bar background: the theme ground plus the selected-tab pill. The pill lives in the
 * background, not in each tab, so it can slide between tabs under the icons.
 */
private class BottomNavPillDrawable : Drawable() {
    private val groundPaint = Paint()
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val from = RectF()
    private val to = RectF()
    private val pill = RectF()
    private var animator: ValueAnimator? = null

    var groundColor: Int
        get() = groundPaint.color
        set(value) { groundPaint.color = value; invalidateSelf() }

    var pillColor: Int
        get() = pillPaint.color
        set(value) { pillPaint.color = value; invalidateSelf() }

    val hasPill: Boolean get() = !pill.isEmpty

    /** Moves the pill to [target], sliding from where it is when [animate] is set. */
    fun moveTo(target: RectF, animate: Boolean) {
        if (target == to && (animator?.isRunning == true || pill == target)) return
        animator?.cancel()
        to.set(target)
        if (!animate || pill.isEmpty) {
            pill.set(target)
            invalidateSelf()
            return
        }
        from.set(pill)
        val movingRight = to.centerX() >= from.centerX()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PILL_SWOOSH_DURATION_MS
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener {
                val p = it.animatedValue as Float
                val lead = (p / (1f - PILL_STRETCH)).coerceAtMost(1f)
                val trail = ((p - PILL_STRETCH) / (1f - PILL_STRETCH)).coerceAtLeast(0f)
                val leftT = if (movingRight) trail else lead
                val rightT = if (movingRight) lead else trail
                pill.set(
                    lerp(from.left, to.left, leftT), lerp(from.top, to.top, p),
                    lerp(from.right, to.right, rightT), lerp(from.bottom, to.bottom, p)
                )
                invalidateSelf()
            }
            start()
        }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    override fun draw(canvas: Canvas) {
        canvas.drawRect(bounds, groundPaint)
        if (pill.isEmpty) return
        val radius = pill.height() / 2f
        canvas.drawRoundRect(pill, radius, radius, pillPaint)
    }

    override fun setAlpha(alpha: Int) {
        groundPaint.alpha = alpha
        pillPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        groundPaint.colorFilter = colorFilter
        pillPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Drawable")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

private val MainActivity.bottomNavBar: LinearLayout
    get() = findViewById(R.id.bottomNavBar)

/** Tab matching the screen currently shown. */
private fun MainActivity.currentBottomNavTab(): BottomNavTab = when {
    settingsContainer.visibility == View.VISIBLE -> BottomNavTab.SETTINGS
    calendarPanelView?.visibility == View.VISIBLE -> BottomNavTab.CALENDAR
    contactsPanelView?.visibility == View.VISIBLE -> BottomNavTab.CONTACTS
    else -> BottomNavTab.MAIL
}

/** The bar belongs to root screens only: never over onboarding, login, compose or a message. */
private fun MainActivity.shouldShowBottomNav(): Boolean =
    onboardingContainer.visibility != View.VISIBLE &&
        loginContainer.visibility != View.VISIBLE &&
        composeContainer.visibility != View.VISIBLE &&
        !isShowingEmailDetail

private fun MainActivity.isTabEnabled(tab: BottomNavTab): Boolean = when (tab) {
    BottomNavTab.CALENDAR -> CalendarPrefs.isEnabled(this)
    BottomNavTab.CONTACTS -> ContactsPrefs.isEnabled(this)
    BottomNavTab.MAIL, BottomNavTab.SETTINGS -> true
}

private fun MainActivity.onBottomNavTabSelected(tab: BottomNavTab) {
    if (tab == currentBottomNavTab()) {
        if (tab == BottomNavTab.MAIL) emailsRecyclerView.smoothScrollToPosition(0)
        return
    }
    when (tab) {
        // The sync loop keeps running on the other tabs, so the list is already current:
        // re-querying the folder here is what made coming back to Mail feel like a reload.
        BottomNavTab.MAIL -> showMailboxScreen(skipRefresh = emails.isNotEmpty() && isPeriodicSyncActive)
        BottomNavTab.CALENDAR -> showCalendarScreen()
        BottomNavTab.CONTACTS -> showContactsScreen()
        BottomNavTab.SETTINGS -> showSettingsScreen()
    }
    tabScreenFor(tab)?.fadeInTab()
    refreshBottomNav()
}

private fun MainActivity.tabScreenFor(tab: BottomNavTab): View? = when (tab) {
    BottomNavTab.MAIL -> mailboxContainer
    BottomNavTab.CALENDAR -> calendarPanelView
    BottomNavTab.CONTACTS -> contactsPanelView
    BottomNavTab.SETTINGS -> settingsContainer
}

/**
 * Tab switches are lateral moves: a short cross-fade in place replaces the slower
 * screen transitions (a 250-300 ms fade from blank, or a slide) the screens run on
 * their own, which read as the tab reloading.
 */
private fun View.fadeInTab() {
    animate().cancel()
    translationX = 0f
    alpha = TAB_FADE_START_ALPHA
    animate().alpha(1f).setDuration(scaledDuration(TAB_FADE_MS)).start()
}

private fun MainActivity.buildBottomNavItems(bar: LinearLayout) {
    val dp = resources.displayMetrics.density
    BottomNavTab.entries.forEach { tab ->
        val indicator = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                (INDICATOR_WIDTH_DP * dp).toInt(), (INDICATOR_HEIGHT_DP * dp).toInt()
            )
            addView(ImageView(this@buildBottomNavItems).apply {
                setImageResource(tab.iconRes)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = null
                layoutParams = FrameLayout.LayoutParams(
                    (ICON_SIZE_DP * dp).toInt(), (ICON_SIZE_DP * dp).toInt(), Gravity.CENTER
                )
            })
        }
        val label = TextView(this).apply {
            setText(tab.labelRes)
            textSize = 12f
            maxLines = 1
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (3 * dp).toInt() }
        }
        bar.addView(LinearLayout(this).apply {
            tag = tab
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            contentDescription = getString(tab.labelRes)
            isClickable = true
            isFocusable = true
            setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(indicator)
            addView(label)
            setOnClickListener { onBottomNavTabSelected(tab) }
        })
    }
}

/**
 * Syncs the bottom bar with the current screen: visibility, which tabs exist (Calendar
 * and Contacts follow their Settings toggles), the selected tab and the theme colours.
 * Call after any screen change and after a theme or accent change.
 */
internal fun MainActivity.refreshBottomNav() {
    val bar = bottomNavBar
    if (!shouldShowBottomNav()) {
        bar.visibility = View.GONE
        return
    }
    if (bar.childCount == 0) {
        buildBottomNavItems(bar)
        bar.background = BottomNavPillDrawable()
        // Tabs appear or vanish with the Calendar and Contacts toggles: keep the pill on
        // the selected tab when the row is laid out again.
        bar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeBottomNavPill(animate = false) }
    }

    val t = tokens
    val accent = currentAccentColor.toColorInt()
    val activeColor = t.accentOnGround(accent)
    val selected = currentBottomNavTab()
    (bar.background as BottomNavPillDrawable).apply {
        groundColor = t.surface
        pillColor = t.accentSoft(accent)
    }

    for (i in 0 until bar.childCount) {
        val item = bar.getChildAt(i) as LinearLayout
        val tab = item.tag as BottomNavTab
        item.visibility = if (isTabEnabled(tab)) View.VISIBLE else View.GONE
        val isSelected = tab == selected
        val color = if (isSelected) activeColor else t.textSecondary
        val indicator = item.getChildAt(0) as FrameLayout
        (indicator.getChildAt(0) as ImageView).imageTintList = ColorStateList.valueOf(color)
        (item.getChildAt(1) as TextView).apply {
            setTextColor(color)
            setTypeface(null, if (isSelected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        item.isSelected = isSelected
    }
    // A bar coming back from hidden snaps into place; a tab change on a visible bar slides.
    val wasVisible = bar.visibility == View.VISIBLE
    bar.visibility = View.VISIBLE
    placeBottomNavPill(animate = wasVisible)
}

/** Puts the bar pill behind the selected tab's icon. No-op until the bar is laid out. */
private fun MainActivity.placeBottomNavPill(animate: Boolean) {
    val bar = bottomNavBar
    val pill = bar.background as? BottomNavPillDrawable ?: return
    val selected = currentBottomNavTab()
    val item = (0 until bar.childCount).map { bar.getChildAt(it) }
        .firstOrNull { it.tag == selected && it.visibility == View.VISIBLE } ?: return
    val indicator = (item as LinearLayout).getChildAt(0)
    if (bar.width == 0 || indicator.width == 0) return
    val left = (item.left + indicator.left).toFloat()
    val top = (item.top + indicator.top).toFloat()
    pill.moveTo(
        RectF(left, top, left + indicator.width, top + indicator.height),
        animate = animate && pill.hasPill
    )
}
