package com.falseenvironment.jmapjolt

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.text.format.DateUtils
import android.text.style.StyleSpan
import android.text.SpannableString
import android.text.Spanned
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import kotlinx.coroutines.runBlocking

/** Provides the inbox rows for [InboxWidgetProvider]'s ListView. */
class InboxWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        )
        return InboxWidgetFactory(applicationContext, appWidgetId)
    }
}

private class InboxWidgetFactory(
    private val context: Context,
    private val appWidgetId: Int
) : RemoteViewsService.RemoteViewsFactory {

    private data class Row(
        val emailId: String,
        val account: String,
        val sender: String,
        val subject: String,
        val preview: String,
        val date: Long,
        val seen: Boolean,
        val stripColor: Int
    )

    private var rows: List<Row> = emptyList()
    private var hasMore = false
    private var textColor = 0
    private var secondaryColor = 0
    private var accentColor = 0

    override fun onCreate() {}

    override fun onDataSetChanged() {
        val palette = WidgetSupport.palette(WidgetSupport.currentTheme(context))
        textColor = palette[2]
        secondaryColor = palette[3]
        accentColor = WidgetSupport.accentColor(context)

        val selection = WidgetSupport.effectiveSelection(context, appWidgetId)
        val cache = EmailCache(context.filesDir)
        val accentForSingle = selection?.takeIf { it != WidgetSupport.UNIFIED }
            ?.let { WidgetSupport.accountColor(context, it) }

        val accounts = when (selection) {
            null -> emptyList()
            WidgetSupport.UNIFIED -> WidgetSupport.savedAccountEmails(context)
            else -> listOf(selection)
        }

        val collected = mutableListOf<Row>()
        for (account in accounts) {
            val result = runCatching { runBlocking { cache.load(account) } }.getOrNull() ?: continue
            val inbox = result.folderCache[R.id.nav_inbox] ?: result.emails
            val strip = accentForSingle ?: WidgetSupport.accountColor(context, account)
            for (e in inbox) {
                collected += Row(
                    emailId = e.id,
                    account = account,
                    sender = e.from.ifBlank { e.fromEmail },
                    subject = e.subject.ifBlank { "(no subject)" },
                    preview = e.preview,
                    date = e.receivedAt,
                    seen = e.seen,
                    stripColor = strip
                )
            }
        }
        val sorted = collected.sortedByDescending { it.date }
        hasMore = sorted.size > MAX_ROWS
        rows = sorted.take(MAX_ROWS)
    }

    override fun onDestroy() { rows = emptyList() }

    override fun getCount() = rows.size + if (hasMore) 1 else 0

    override fun getViewAt(position: Int): RemoteViews {
        if (position >= rows.size) return moreView()
        val row = rows[position]
        val views = RemoteViews(context.packageName, R.layout.widget_inbox_item)

        views.setInt(R.id.itemColorStrip, "setBackgroundColor", row.stripColor)
        views.setTextViewText(R.id.itemSender, bold(row.sender, !row.seen))
        views.setTextViewText(R.id.itemSubject, bold(row.subject, !row.seen))
        views.setTextViewText(R.id.itemPreview, row.preview)
        views.setTextViewText(R.id.itemDate, formatDate(row.date))

        views.setTextColor(R.id.itemSender, textColor)
        views.setTextColor(R.id.itemSubject, textColor)
        views.setTextColor(R.id.itemPreview, secondaryColor)
        views.setTextColor(R.id.itemDate, secondaryColor)
        views.setViewVisibility(R.id.itemColorStrip, View.VISIBLE)

        // Tint the press ripple/flash with the app accent (white drawable layers
        // recolored via background tint). setColorStateList is API 31+; older
        // versions keep the drawable's default white highlight.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            views.setColorStateList(
                R.id.itemRoot, "setBackgroundTintList",
                android.content.res.ColorStateList.valueOf(accentColor)
            )
        }

        // Fill-in opens this specific email (merged into the list's template intent).
        val fillIn = Intent()
            .putExtra(InboxWidgetProvider.EXTRA_OPEN_EMAIL_ID, row.emailId)
            .putExtra(InboxWidgetProvider.EXTRA_OPEN_ACCOUNT, row.account)
        views.setOnClickFillInIntent(R.id.itemRoot, fillIn)
        return views
    }

    private fun moreView(): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_inbox_more)
        views.setTextColor(R.id.moreText, accentColor)
        // No extras → template opens the app inbox.
        views.setOnClickFillInIntent(R.id.moreRoot, Intent())
        return views
    }

    private fun bold(text: String, bold: Boolean): CharSequence {
        if (!bold) return text
        return SpannableString(text).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun formatDate(ts: Long): String {
        if (ts <= 0L) return ""
        return DateUtils.getRelativeTimeSpanString(
            ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
        ).toString()
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount() = 2
    override fun getItemId(position: Int) = position.toLong()
    override fun hasStableIds() = false

    companion object {
        private const val MAX_ROWS = 50
    }
}
