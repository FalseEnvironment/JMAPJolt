package com.falseenvironment.jmapjolt

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt

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
        BottomNavTab.MAIL -> showMailboxScreen()
        BottomNavTab.CALENDAR -> showCalendarScreen()
        BottomNavTab.CONTACTS -> showContactsScreen()
        BottomNavTab.SETTINGS -> showSettingsScreen()
    }
    refreshBottomNav()
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
    if (bar.childCount == 0) buildBottomNavItems(bar)

    val t = tokens
    val accent = currentAccentColor.toColorInt()
    val activeColor = t.accentOnGround(accent)
    val dp = resources.displayMetrics.density
    val selected = currentBottomNavTab()
    bar.setBackgroundColor(t.surface)

    for (i in 0 until bar.childCount) {
        val item = bar.getChildAt(i) as LinearLayout
        val tab = item.tag as BottomNavTab
        item.visibility = if (isTabEnabled(tab)) View.VISIBLE else View.GONE
        val isSelected = tab == selected
        val color = if (isSelected) activeColor else t.textSecondary
        val indicator = item.getChildAt(0) as FrameLayout
        indicator.background = if (isSelected) {
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 999 * dp
                setColor(t.accentSoft(accent))
            }
        } else null
        (indicator.getChildAt(0) as ImageView).imageTintList = ColorStateList.valueOf(color)
        (item.getChildAt(1) as TextView).apply {
            setTextColor(color)
            setTypeface(null, if (isSelected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        item.isSelected = isSelected
    }
    bar.visibility = View.VISIBLE
}
