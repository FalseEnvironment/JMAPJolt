package com.falseenvironment.jmapjolt

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import java.util.Calendar

/**
 * Draws the week grid of [CalendarWeekWidgetProvider] into a bitmap.
 *
 * RemoteViews can't host a custom View, so the same picture the in-app
 * [CalendarTimelineView] paints is rendered offscreen and handed to an ImageView. The
 * layout is tuned for the small canvas of a home-screen widget: the hour window shrinks
 * to the hours that actually carry events, all-day items collapse into a band above the
 * grid, and titles are dropped when a block is too short to read.
 */
object CalendarWeekRenderer {

    /** Bitmaps travel through a 1 MB binder transaction; stay well inside it. */
    private const val MAX_WIDTH_PX = 1400
    private const val MAX_HEIGHT_PX = 1400
    private const val DAY_MS = 86_400_000L

    /** Hour window shown when the week is empty or only has all-day events. */
    private const val DEFAULT_FIRST_HOUR = 8
    private const val DEFAULT_LAST_HOUR = 20
    private const val MIN_HOURS = 6

    data class Week(val days: List<Long>, val occurrences: List<EventOccurrence>)

    /** The seven day-start instants of the week containing [anchor], in the calendar zone. */
    fun weekDays(anchor: Long): List<Long> {
        val cal = CalendarPrefs.calendar().apply { timeInMillis = anchor }
        while (cal.get(Calendar.DAY_OF_WEEK) != cal.firstDayOfWeek) cal.add(Calendar.DAY_OF_MONTH, -1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return (0 until 7).map {
            (cal.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, it) }.timeInMillis
        }
    }

    fun week(context: Context, anchor: Long): Week {
        val days = weekDays(anchor)
        val from = days.first()
        val to = days.last() + DAY_MS
        val occurrences = runCatching { CalendarRepository.occurrences(context, from, to) }
            .getOrDefault(emptyList())
        return Week(days, occurrences)
    }

    fun render(context: Context, week: Week, widthPx: Int, heightPx: Int): Bitmap? {
        val w = widthPx.coerceIn(1, MAX_WIDTH_PX)
        val h = heightPx.coerceIn(1, MAX_HEIGHT_PX)
        if (w < 8 || h < 8) return null

        val palette = CalendarTheme.palette(context)
        val density = context.resources.displayMetrics.density
        val bitmap = createBitmap(w, h) ?: return null
        val canvas = Canvas(bitmap)
        canvas.drawColor(palette.background)

        val dp = { value: Float -> value * density }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = CalendarTimelineView.adjustAlpha(palette.secondaryText, 0.22f)
            strokeWidth = dp(1f)
        }
        val hourPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.secondaryText
            textSize = dp(8f)
        }
        val dayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.text
            textSize = dp(9f)
            isFakeBoldText = true
        }
        val blockPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(8f) }

        val gutter = dp(20f)
        val headerHeight = dp(14f)
        val use24h = CalendarPrefs.use24Hour(context)

        val banded = week.occurrences.filter { isBanded(it) }
        val timed = week.occurrences.filter { !isBanded(it) }
        val bandRowHeight = if (banded.isEmpty()) 0f else dp(11f)
        val bandRows = if (banded.isEmpty()) 0 else 1
        val bandHeight = bandRows * bandRowHeight

        val gridTop = headerHeight + bandHeight
        val gridBottom = h.toFloat()
        val gridHeight = (gridBottom - gridTop).coerceAtLeast(1f)
        val colWidth = (w - gutter) / 7f

        val (firstHour, lastHour) = hourWindow(timed, week.days)
        val hours = (lastHour - firstHour).coerceAtLeast(1)
        val hourHeight = gridHeight / hours

        drawDayHeaders(canvas, week.days, dayPaint, palette, gutter, colWidth, dp(10f))
        if (banded.isNotEmpty()) {
            drawAllDayBand(
                canvas, banded, week.days, blockPaint, titlePaint, palette,
                gutter, colWidth, headerHeight, bandRowHeight, density
            )
        }

        // Hour lines + gutter labels.
        for (hour in firstHour..lastHour) {
            val y = gridTop + (hour - firstHour) * hourHeight
            canvas.drawLine(gutter, y, w.toFloat(), y, linePaint)
            if (hour < lastHour && hourHeight > dp(9f)) {
                canvas.drawText(hourLabel(hour, use24h), dp(2f), y + dp(8f), hourPaint)
            }
        }
        // Column separators.
        for (i in 0..7) {
            val x = gutter + i * colWidth
            canvas.drawLine(x, gridTop, x, gridBottom, linePaint)
        }

        drawEventBlocks(
            canvas, timed, week.days, blockPaint, titlePaint, palette,
            gutter, colWidth, gridTop, hourHeight, firstHour, density
        )
        drawNowLine(canvas, week.days, palette, gutter, colWidth, gridTop, hourHeight, firstHour, density)
        return bitmap
    }

    // ---- pieces -----------------------------------------------------------------

    private fun drawDayHeaders(
        canvas: Canvas,
        days: List<Long>,
        paint: Paint,
        palette: CalendarTheme.Palette,
        gutter: Float,
        colWidth: Float,
        baseline: Float
    ) {
        days.forEachIndexed { index, day ->
            val cal = CalendarPrefs.calendar().apply { timeInMillis = day }
            val label = "%s %d".format(
                CalendarTimelineView.shortDow(cal.get(Calendar.DAY_OF_WEEK)).take(2),
                cal.get(Calendar.DAY_OF_MONTH)
            )
            val isToday = CalendarTimelineView.isSameDay(day, System.currentTimeMillis())
            paint.color = if (isToday) palette.accent else palette.text
            val x = gutter + index * colWidth + (colWidth - paint.measureText(label)) / 2f
            canvas.drawText(label, x.coerceAtLeast(gutter), baseline, paint)
        }
    }

    /** All-day and multi-day items as accent chips spanning their columns. */
    private fun drawAllDayBand(
        canvas: Canvas,
        banded: List<EventOccurrence>,
        days: List<Long>,
        blockPaint: Paint,
        titlePaint: Paint,
        palette: CalendarTheme.Palette,
        gutter: Float,
        colWidth: Float,
        top: Float,
        rowHeight: Float,
        density: Float
    ) {
        val weekEnd = days.last() + DAY_MS
        banded.forEach { occ ->
            // All-day instants are UTC-based; shift them onto the local day they cover.
            val offset = if (occ.event.allDay)
                CalendarPrefs.zone().getOffset(occ.start).toLong() else 0L
            val start = occ.start - offset
            val end = occ.end - offset
            if (end <= days.first() || start >= weekEnd) return@forEach
            val firstCol = days.indexOfLast { it <= start }.coerceAtLeast(0)
            val lastCol = days.indexOfLast { it < end }.coerceAtLeast(firstCol)
            val rect = RectF(
                gutter + firstCol * colWidth + density,
                top + density,
                gutter + (lastCol + 1) * colWidth - density,
                top + rowHeight - density
            )
            blockPaint.color = eventColor(palette)
            canvas.drawRoundRect(rect, 3f * density, 3f * density, blockPaint)
            titlePaint.color = palette.onAccent
            canvas.save()
            canvas.clipRect(rect)
            canvas.drawText(
                occ.event.title.ifBlank { "(no title)" },
                rect.left + 3f * density, rect.centerY() + 3f * density, titlePaint
            )
            canvas.restore()
        }
    }

    private fun drawEventBlocks(
        canvas: Canvas,
        timed: List<EventOccurrence>,
        days: List<Long>,
        blockPaint: Paint,
        titlePaint: Paint,
        palette: CalendarTheme.Palette,
        gutter: Float,
        colWidth: Float,
        gridTop: Float,
        hourHeight: Float,
        firstHour: Int,
        density: Float
    ) {
        days.forEachIndexed { col, day ->
            val ofDay = timed.filter { CalendarTimelineView.isSameDay(it.start, day) }
                .sortedBy { it.start }
            // Side-by-side lanes for events that overlap in time.
            val lanes = ArrayList<Long>()
            val laneOf = HashMap<EventOccurrence, Int>()
            ofDay.forEach { occ ->
                val free = lanes.indexOfFirst { it <= occ.start }
                val lane = if (free >= 0) free.also { lanes[it] = occ.end }
                else lanes.size.also { lanes.add(occ.end) }
                laneOf[occ] = lane
            }
            val laneCount = lanes.size.coerceAtLeast(1)

            ofDay.forEach { occ ->
                val startMin = ((occ.start - day) / 60_000f - firstHour * 60f)
                val endMin = ((occ.end - day) / 60_000f - firstHour * 60f)
                val top = gridTop + startMin / 60f * hourHeight
                val bottom = (gridTop + endMin / 60f * hourHeight)
                    .coerceAtLeast(top + 3f * density)
                val lane = laneOf[occ] ?: 0
                val laneWidth = (colWidth - 2f * density) / laneCount
                val left = gutter + col * colWidth + density + lane * laneWidth
                val rect = RectF(left, top, left + laneWidth - density, bottom)
                blockPaint.color = eventColor(palette)
                canvas.drawRoundRect(rect, 3f * density, 3f * density, blockPaint)
                if (rect.height() >= 10f * density && laneWidth >= 18f * density) {
                    titlePaint.color = palette.onAccent
                    canvas.save()
                    canvas.clipRect(rect)
                    canvas.drawText(
                        occ.event.title.ifBlank { "(no title)" },
                        rect.left + 2f * density, rect.top + 8f * density, titlePaint
                    )
                    canvas.restore()
                }
            }
        }
    }

    private fun drawNowLine(
        canvas: Canvas,
        days: List<Long>,
        palette: CalendarTheme.Palette,
        gutter: Float,
        colWidth: Float,
        gridTop: Float,
        hourHeight: Float,
        firstHour: Int,
        density: Float
    ) {
        val now = System.currentTimeMillis()
        val col = days.indexOfFirst { CalendarTimelineView.isSameDay(it, now) }
        if (col < 0) return
        val minutes = (now - days[col]) / 60_000f - firstHour * 60f
        val y = gridTop + minutes / 60f * hourHeight
        if (y < gridTop || y > canvas.height) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.accent
            strokeWidth = 1.5f * density
        }
        val left = gutter + col * colWidth
        canvas.drawCircle(left + 2f * density, y, 2.5f * density, paint)
        canvas.drawLine(left, y, left + colWidth, y, paint)
    }

    // ---- helpers ----------------------------------------------------------------

    /**
     * Hour range worth showing: the default working window widened to cover every timed
     * event of the week, so nothing is cropped and empty nights aren't wasted pixels.
     * When the displayed week contains today, the current hour is included too — otherwise
     * the now-line would sit outside the grid and never show.
     */
    private fun hourWindow(timed: List<EventOccurrence>, days: List<Long>): Pair<Int, Int> {
        var first = DEFAULT_FIRST_HOUR
        var last = DEFAULT_LAST_HOUR
        val now = System.currentTimeMillis()
        days.firstOrNull { CalendarTimelineView.isSameDay(it, now) }?.let { today ->
            val nowHour = ((now - today) / 3_600_000L).toInt()
            if (nowHour < first) first = nowHour
            if (nowHour + 1 > last) last = nowHour + 1
        }
        timed.forEach { occ ->
            val day = days.firstOrNull { CalendarTimelineView.isSameDay(it, occ.start) } ?: return@forEach
            val startHour = ((occ.start - day) / 3_600_000L).toInt()
            val endHour = (((occ.end - day) + 3_599_999L) / 3_600_000L).toInt()
            if (startHour < first) first = startHour
            if (endHour > last) last = endHour
        }
        first = first.coerceIn(0, 23)
        last = last.coerceIn(first + 1, 24)
        if (last - first < MIN_HOURS) {
            last = (first + MIN_HOURS).coerceAtMost(24)
            first = (last - MIN_HOURS).coerceAtLeast(0)
        }
        return first to last
    }

    private fun isBanded(occ: EventOccurrence): Boolean =
        occ.event.allDay || !CalendarTimelineView.isSameDay(occ.start, occ.end - 1)

    /**
     * Event blocks follow the app accent, exactly like the in-app week timeline — a
     * per-calendar colour here would ignore the theme the user picked in Settings.
     */
    private fun eventColor(palette: CalendarTheme.Palette): Int =
        CalendarTimelineView.adjustAlpha(palette.accent, if (palette.isDark) 0.75f else 0.9f)

    private fun hourLabel(hour: Int, use24h: Boolean): String {
        if (use24h) return "%02d".format(hour)
        val h12 = if (hour % 12 == 0) 12 else hour % 12
        return "$h12${if (hour < 12) "a" else "p"}"
    }

    private fun createBitmap(w: Int, h: Int): Bitmap? =
        runCatching { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }.getOrNull()
}
