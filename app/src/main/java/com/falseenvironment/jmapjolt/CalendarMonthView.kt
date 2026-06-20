package com.falseenvironment.jmapjolt

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import java.util.Calendar

/**
 * Month grid (6 weeks x 7 days). Renders the day number, a subtle today highlight, and up to
 * three event dots per cell. Tapping a cell reports the selected day.
 */
class CalendarMonthView(context: Context) : View(context) {

    var palette: CalendarTheme.Palette = CalendarTheme.palette(context)
    /** Any instant within the displayed month. */
    var monthAnchor: Long = System.currentTimeMillis()
        set(value) { field = value; rebuild(); invalidate() }
    var occurrences: List<EventOccurrence> = emptyList()
        set(value) { field = value; countByDay(); invalidate() }
    var selectedDay: Long = CalendarTimelineView.midnight(System.currentTimeMillis())
        set(value) { field = value; invalidate() }

    var onDaySelected: ((Long) -> Unit)? = null
    /** Etar-style: first tap arms a "+" in the cell, tapping it again creates an event. */
    var onAddRequested: ((Long) -> Unit)? = null
    private var plusDay: Long = 0L

    private val density = resources.displayMetrics.density
    private val headerHeight = 24f * density
    private val dayNumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13f * density }
    private val dowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f * density; isFakeBoldText = true }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val cellStarts = LongArray(42)
    private val counts = HashMap<Long, Int>()

    init { rebuild() }

    private fun rebuild() {
        val cal = Calendar.getInstance().apply {
            timeInMillis = monthAnchor
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        // back up to first day of week
        while (cal.get(Calendar.DAY_OF_WEEK) != cal.firstDayOfWeek) {
            cal.add(Calendar.DAY_OF_MONTH, -1)
        }
        for (i in 0 until 42) {
            cellStarts[i] = cal.timeInMillis
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
    }

    private fun countByDay() {
        counts.clear()
        for (occ in occurrences) {
            val key = CalendarTimelineView.midnight(occ.start)
            counts[key] = (counts[key] ?: 0) + 1
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(palette.background)
        val cols = 7
        val cellW = width / cols.toFloat()
        val gridTop = headerHeight
        val rows = 6
        val cellH = (height - gridTop) / rows

        // weekday header
        dowPaint.color = palette.secondaryText
        val firstDow = Calendar.getInstance().firstDayOfWeek
        for (i in 0 until cols) {
            val dow = ((firstDow - 1 + i) % 7) + 1
            canvas.drawText(CalendarTimelineView.shortDow(dow).take(2),
                i * cellW + 6f * density, 16f * density, dowPaint)
        }

        linePaint.color = CalendarTimelineView.adjustAlpha(palette.secondaryText, 0.18f)
        val thisMonth = Calendar.getInstance().apply { timeInMillis = monthAnchor }.get(Calendar.MONTH)
        val today = CalendarTimelineView.midnight(System.currentTimeMillis())

        for (idx in 0 until 42) {
            val row = idx / cols
            val col = idx % cols
            val x = col * cellW
            val y = gridTop + row * cellH
            val dayStart = cellStarts[idx]
            val cal = Calendar.getInstance().apply { timeInMillis = dayStart }
            val inMonth = cal.get(Calendar.MONTH) == thisMonth

            if (dayStart == selectedDay) {
                cellPaint.color = CalendarTimelineView.adjustAlpha(palette.accent, 0.18f)
                canvas.drawRect(x, y, x + cellW, y + cellH, cellPaint)
            }
            canvas.drawRect(x, y + cellH, x + cellW, y + cellH + density, linePaint)

            val isToday = dayStart == today
            dayNumPaint.color = when {
                isToday -> palette.accent
                inMonth -> palette.text
                else -> CalendarTimelineView.adjustAlpha(palette.secondaryText, 0.6f)
            }
            dayNumPaint.isFakeBoldText = isToday
            canvas.drawText(cal.get(Calendar.DAY_OF_MONTH).toString(),
                x + 6f * density, y + 18f * density, dayNumPaint)

            val n = counts[dayStart] ?: 0
            if (n > 0) {
                dotPaint.color = palette.accent
                val dots = n.coerceAtMost(3)
                for (d in 0 until dots) {
                    canvas.drawCircle(
                        x + 10f * density + d * 9f * density,
                        y + cellH - 10f * density, 3f * density, dotPaint)
                }
            }

            if (dayStart == plusDay) {
                val cx = x + cellW / 2f
                val cy = y + cellH / 2f + 4f * density
                val r = 14f * density
                dotPaint.color = palette.accent
                canvas.drawCircle(cx, cy, r, dotPaint)
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
        if (event.y < headerHeight) return true
        val cellW = width / 7f
        val cellH = (height - headerHeight) / 6
        val col = (event.x / cellW).toInt().coerceIn(0, 6)
        val row = ((event.y - headerHeight) / cellH).toInt().coerceIn(0, 5)
        val idx = row * 7 + col
        if (idx in 0 until 42) {
            val day = cellStarts[idx]
            if (day == plusDay) {
                onAddRequested?.invoke(day)
                plusDay = 0L
            } else {
                plusDay = day
                selectedDay = day
                onDaySelected?.invoke(day)
            }
            invalidate()
        }
        return true
    }
}
