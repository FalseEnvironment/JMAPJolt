package com.falseenvironment.jmapjolt

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Shown when an inbox widget is placed. Lets the user pick which inbox the widget
 * shows: the unified inbox (accent-colored) or one specific account (its own color),
 * styled with the app's current theme + accent. Auto-picks when only one account exists.
 */
class InboxWidgetConfigActivity : Activity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish(); return
        }

        val accounts = WidgetSupport.savedAccountEmails(this)
        // No choice to make with a single account — configure it and finish immediately.
        if (accounts.size == 1) {
            commit(accounts[0]); return
        }

        buildUi(accounts)
    }

    private fun buildUi(accounts: List<String>) {
        val theme = WidgetSupport.currentTheme(this)
        val palette = WidgetSupport.palette(theme)
        val bg = palette[0]; val text = palette[2]; val secondary = palette[3]
        val accent = WidgetSupport.accentColor(this)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val scroll = ScrollView(this).apply { setBackgroundColor(bg) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            setText(R.string.widget_config_title)
            setTextColor(text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            setText(R.string.widget_config_subtitle)
            setTextColor(secondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(4), 0, dp(16))
        })

        if (accounts.isEmpty()) {
            root.addView(TextView(this).apply {
                setText(R.string.widget_config_no_accounts)
                setTextColor(secondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            })
            setContentView(scroll)
            return
        }

        // Unified inbox option (accent dot) — only meaningful with multiple accounts.
        root.addView(optionRow(
            getString(R.string.widget_unified_inbox), accent, text, dp(12)
        ) { commit(WidgetSupport.UNIFIED) })

        for (email in accounts) {
            root.addView(optionRow(
                email, WidgetSupport.accountColor(this, email), text, dp(12)
            ) { commit(email) })
        }

        setContentView(scroll)
    }

    private fun optionRow(
        label: String, dotColor: Int, textColor: Int, pad: Int, onClick: () -> Unit
    ): View {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), pad, dp(8), pad)
            isClickable = true
            isFocusable = true
            background = themedRipple()
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(dotColor)
            }
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply {
                marginEnd = dp(14)
            }
        }
        val tv = TextView(this).apply {
            text = label
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        row.addView(dot); row.addView(tv)
        return row
    }

    private fun themedRipple(): android.graphics.drawable.Drawable {
        val typed = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, typed, true)
        return runCatching {
            androidx.core.content.ContextCompat.getDrawable(this, typed.resourceId)
        }.getOrNull() ?: android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
    }

    private fun commit(selection: String) {
        WidgetSupport.saveSelection(this, appWidgetId, selection)
        val mgr = AppWidgetManager.getInstance(this)
        InboxWidgetProvider.renderWidget(this, mgr, appWidgetId)
        mgr.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widgetList)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        finish()
    }
}
