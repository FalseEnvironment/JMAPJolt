package com.falseenvironment.jmapjolt

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import java.util.Calendar

/**
 * Day/week timeline: a scrollable 24-hour grid that draws event blocks positioned by time.
 * Tapping a block opens it; tapping empty space creates an event at that slot.
 */
class CalendarTimelineView(context: Context) : View(context) {

    var palette: CalendarTheme.Palette = CalendarTheme.palette(context)
    /** Day-start (local midnight) instants, one per column. */
    var days: List<Long> = listOf(midnight(System.currentTimeMillis()))
        set(value) { field = value; allDayBand = buildBand(); requestLayout(); invalidate() }
    var occurrences: List<EventOccurrence> = emptyList()
        set(value) { field = value; allDayBand = buildBand(); requestLayout(); invalidate() }

    var onEventClick: ((EventOccurrence) -> Unit)? = null
    var onSlotClick: ((Long) -> Unit)? = null
    /** When true the whole 24h day is scaled to fit the view height (Day view). */
    var fitHeight: Boolean = false
        set(value) { field = value; requestLayout(); invalidate() }

    private val density = resources.displayMetrics.density
    private var hourHeight = 56f * density
    private val gutter = 44f * density
    /** Etar-style two-tap add: first tap arms this slot, second tap on it creates. */
    private var pendingSlot: Long = -1L

    /**
     * Hour-gutter label honouring the 24H/12H setting. In 12-hour mode the gutter is
     * too narrow for ":00 AM", so it renders the compact "9 AM" / "12 PM" form.
     */
    private fun hourGutterLabel(hour: Int): String {
        if (CalendarPrefs.use24Hour(context)) return "%02d:00".format(hour)
        val suffix = if (hour < 12) "AM" else "PM"
        val h12 = when (hour % 12) { 0 -> 12; else -> hour % 12 }
        return "$h12 $suffix"
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hourTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f * density }
    private val blockPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f * density }
    private val dayHeaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f * density; isFakeBoldText = true }

    private val dayLabelHeight get() = if (days.size > 1) 28f * density else 0f
    private val allDayRowHeight = 22f * density
    private val headerHeight get() = dayLabelHeight
    /**
     * All-day / multi-day chips live in a band below the hour grid. One row is always
     * reserved so every page keeps the same footer padding, with or without all-day events.
     */
    private val bandRows get() = allDayBand.size.coerceAtLeast(1)
    private val footerHeight get() = bandRows * allDayRowHeight + 8f * density
    private data class Hit(val rect: RectF, val occ: EventOccurrence)
    private val hits = mutableListOf<Hit>()

    /** An all-day chip: the occurrence plus the inclusive column span it covers. */
    private data class Span(val occ: EventOccurrence, val firstCol: Int, val lastCol: Int)
    /** Chips packed into rows; row 0 is drawn topmost. */
    private var allDayBand: List<List<Span>> = emptyList()

    private fun isBanded(occ: EventOccurrence): Boolean =
        occ.event.allDay || !isSameDay(occ.start, occ.end - 1)

    /** Greedy row packing of banded occurrences over the visible day columns. */
    private fun buildBand(): List<List<Span>> {
        if (days.isEmpty()) return emptyList()
        val dayEnd = days.last() + 86_400_000L
        val spans = occurrences.filter { isBanded(it) }.mapNotNull { occ ->
            // All-day instants are UTC-based; shift them to the local day they belong to.
            val off = if (occ.event.allDay)
                java.util.TimeZone.getDefault().getOffset(occ.start).toLong() else 0L
            val start = occ.start - off
            val end = occ.end - off
            if (end <= days.first() || start >= dayEnd) return@mapNotNull null
            val first = days.indexOfLast { it <= start }.coerceAtLeast(0)
            val last = days.indexOfLast { it < end }.coerceAtLeast(first)
            Span(occ, first, last)
        }.sortedWith(compareBy({ it.firstCol }, { -(it.lastCol - it.firstCol) }))
        val rows = mutableListOf<MutableList<Span>>()
        for (span in spans) {
            val row = rows.firstOrNull { r -> r.none { it.lastCol >= span.firstCol && it.firstCol <= span.lastCol } }
            if (row != null) row += span else rows += mutableListOf(span)
        }
        return rows
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        if (fitHeight) {
            val h = MeasureSpec.getSize(heightMeasureSpec)
            hourHeight = ((h - headerHeight - footerHeight) / 24f).coerceAtLeast(1f)
            setMeasuredDimension(w, h)
        } else {
            hourHeight = 56f * density
            setMeasuredDimension(w, (24 * hourHeight + headerHeight + footerHeight).toInt())
        }
    }

    override fun onDraw(canvas: Canvas) {
        hits.clear()
        canvas.drawColor(palette.background)
        linePaint.color = adjustAlpha(palette.secondaryText, 0.25f)
        hourTextPaint.color = palette.secondaryText
        dayHeaderPaint.color = palette.text

        val top = headerHeight
        val colWidth = (width - gutter) / days.size.coerceAtLeast(1)

        // Day headers (week mode)
        if (days.size > 1) {
            for (i in days.indices) {
                val c = CalendarPrefs.calendar().apply { timeInMillis = days[i] }
                val label = "%s %d".format(
                    shortDow(c.get(Calendar.DAY_OF_WEEK)), c.get(Calendar.DAY_OF_MONTH))
                val isToday = isSameDay(days[i], System.currentTimeMillis())
                dayHeaderPaint.color = if (isToday) palette.accent else palette.text
                canvas.drawText(label, gutter + i * colWidth + 8f * density, 18f * density, dayHeaderPaint)
            }
        }

        // All-day / multi-day chips: accent bars under the grid, spanning their day columns.
        val bandTop = top + 24 * hourHeight + 4f * density
        allDayBand.forEachIndexed { rowIndex, row ->
            val rowTop = bandTop + rowIndex * allDayRowHeight
            for (span in row) {
                val left = gutter + span.firstCol * colWidth + 2f * density
                val right = gutter + (span.lastCol + 1) * colWidth - 2f * density
                val rect = RectF(left, rowTop, right, rowTop + allDayRowHeight - 2f * density)
                blockPaint.color = palette.accent
                canvas.drawRoundRect(rect, 4f * density, 4f * density, blockPaint)
                titlePaint.color = palette.onAccent
                canvas.save()
                canvas.clipRect(rect)
                canvas.drawText(
                    span.occ.event.title.ifBlank { "(no title)" },
                    left + 6f * density, rect.centerY() + 4.5f * density, titlePaint)
                canvas.restore()
                hits += Hit(rect, span.occ)
            }
        }

        // Hour grid
        for (hour in 0..24) {
            val y = top + hour * hourHeight
            canvas.drawLine(gutter, y, width.toFloat(), y, linePaint)
            if (hour < 24) {
                canvas.drawText(hourGutterLabel(hour), 4f * density, y + 14f * density, hourTextPaint)
            }
        }
        // Column separators
        for (i in 0..days.size) {
            val x = gutter + i * colWidth
            canvas.drawLine(x, top, x, top + 24 * hourHeight, linePaint)
        }

        // Event blocks
        for (occ in occurrences) {
            if (isBanded(occ)) continue
            val colIndex = days.indexOfFirst { isSameDay(it, occ.start) }
            if (colIndex < 0) continue
            val dayStart = days[colIndex]
            val startMin = ((occ.start - dayStart) / 60_000L).toInt().coerceIn(0, 1440)
            val endMin = ((occ.end - dayStart) / 60_000L).toInt().coerceIn(startMin + 20, 1440)
            val left = gutter + colIndex * colWidth + 2f * density
            val right = gutter + (colIndex + 1) * colWidth - 2f * density
            val blockTop = top + startMin / 60f * hourHeight
            val blockBottom = top + endMin / 60f * hourHeight
            val rect = RectF(left, blockTop, right, blockBottom)
            blockPaint.color = adjustAlpha(palette.accent, if (palette.isDark) 0.5f else 0.85f)
            canvas.drawRoundRect(rect, 6f * density, 6f * density, blockPaint)
            titlePaint.color = palette.onAccent
            canvas.save()
            canvas.clipRect(rect)
            canvas.drawText(
                occ.event.title.ifBlank { "(no title)" },
                left + 6f * density, blockTop + 16f * density, titlePaint)
            canvas.restore()
            hits += Hit(rect, occ)
        }

        // Current-time indicator (Etar-style): accent line + dot across today's column.
        val now = System.currentTimeMillis()
        val nowCol = days.indexOfFirst { isSameDay(it, now) }
        if (nowCol >= 0) {
            val nowMin = ((now - days[nowCol]) / 60_000L).toInt().coerceIn(0, 1440)
            val y = top + nowMin / 60f * hourHeight
            val left = gutter + nowCol * colWidth
            val right = gutter + (nowCol + 1) * colWidth
            val nowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.accent
                strokeWidth = 2f * density
            }
            canvas.drawCircle(left + 3f * density, y, 4f * density, nowPaint)
            canvas.drawLine(left + 3f * density, y, right, y, nowPaint)
        }

        // Armed "+" slot (Etar-style two-tap add)
        if (pendingSlot > 0) {
            val col = days.indexOfFirst { isSameDay(it, pendingSlot) }
            if (col >= 0) {
                val minutes = ((pendingSlot - days[col]) / 60_000L).toInt().coerceIn(0, 1439)
                val cx = gutter + col * colWidth + colWidth / 2f
                val cy = top + (minutes / 60f + 0.5f) * hourHeight
                val r = 16f * density
                blockPaint.color = palette.accent
                canvas.drawCircle(cx, cy, r, blockPaint)
                val plus = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = palette.onAccent
                    strokeWidth = 2.5f * density
                    strokeCap = Paint.Cap.ROUND
                }
                canvas.drawLine(cx - r / 2.5f, cy, cx + r / 2.5f, cy, plus)
                canvas.drawLine(cx, cy - r / 2.5f, cx, cy + r / 2.5f, plus)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val x = event.x
        val y = event.y
        hits.firstOrNull { it.rect.contains(x, y) }?.let {
            onEventClick?.invoke(it.occ)
            return true
        }
        // Empty slot -> create
        val top = headerHeight
        if (y < top || y > top + 24 * hourHeight) return true
        val colWidth = (width - gutter) / days.size.coerceAtLeast(1)
        val colIndex = (((x - gutter) / colWidth).toInt()).coerceIn(0, days.size - 1)
        // Snap to whole hours (no half-hour slots): tapping yields 9:00, 10:00, …
        val hour = ((y - top) / hourHeight).toInt().coerceIn(0, 23)
        val slot = CalendarPrefs.calendar().apply {
            timeInMillis = days[colIndex]
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
        }.timeInMillis
        if (slot == pendingSlot) {
            pendingSlot = -1L
            onSlotClick?.invoke(slot)
        } else {
            pendingSlot = slot
            invalidate()
        }
        return true
    }

    companion object {
        fun midnight(epoch: Long): Long = CalendarPrefs.calendar().apply {
            timeInMillis = epoch
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        fun isSameDay(a: Long, b: Long): Boolean {
            val ca = CalendarPrefs.calendar().apply { timeInMillis = a }
            val cb = CalendarPrefs.calendar().apply { timeInMillis = b }
            return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
                ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
        }

        fun shortDow(dow: Int): String = when (dow) {
            Calendar.SUNDAY -> "Sun"; Calendar.MONDAY -> "Mon"; Calendar.TUESDAY -> "Tue"
            Calendar.WEDNESDAY -> "Wed"; Calendar.THURSDAY -> "Thu"; Calendar.FRIDAY -> "Fri"
            else -> "Sat"
        }

        fun adjustAlpha(color: Int, factor: Float): Int {
            val a = (255 * factor).toInt().coerceIn(0, 255)
            return (a shl 24) or (color and 0x00FFFFFF)
        }
    }
}
