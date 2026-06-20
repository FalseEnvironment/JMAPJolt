package com.falseenvironment.jmapjolt

import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Calendar UI hosted inside [MainActivity] so the app's real navigation drawer (all
 * categories, accounts, labels) stays available. Same month/week/day/agenda views, swipe
 * navigation, FAB and overflow as the former standalone CalendarActivity — the hamburger here
 * opens Main's drawer instead of a private one.
 */
class CalendarPanel(private val activity: MainActivity) : FrameLayout(activity) {

    private enum class Mode { MONTH, WEEK, DAY, AGENDA }

    private val palette: CalendarTheme.Palette = CalendarTheme.palette(activity)
    private val scope get() = activity.lifecycleScope

    private var mode = Mode.MONTH
    private var anchor = System.currentTimeMillis()
    private var selectedDay = CalendarTimelineView.midnight(System.currentTimeMillis())
    private var unsupportedToastShown = false

    private val backStack = ArrayDeque<Triple<Mode, Long, Long>>()

    private lateinit var titleView: TextView
    private lateinit var content: FrameLayout
    private val switcherButtons = mutableMapOf<Mode, TextView>()

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    init {
        setBackgroundColor(palette.background)
        addView(buildRoot())
        render()
    }

    /** Called when the panel becomes visible. */
    fun onShown() {
        maybePromptExactAlarm()
        if (CalendarPrefs.provider(activity) == CalendarPrefs.Provider.DAVX5 &&
            !CalendarProvider.hasReadPermission(activity)) {
            activity.requestCalendarPermissions { render() }
        }
        triggerSync()
    }

    /** Android back: pop view history; returns false when there is nothing left to pop. */
    fun onBackPressed(): Boolean {
        val prev = backStack.removeLastOrNull() ?: return false
        mode = prev.first; anchor = prev.second; selectedDay = prev.third
        render()
        return true
    }

    fun refresh() = render()

    private fun pushHistory() {
        backStack.addLast(Triple(mode, anchor, selectedDay))
        if (backStack.size > 32) backStack.removeFirst()
    }

    // ---- layout ---------------------------------------------------------------------------

    private fun buildRoot(): View {
        val outer = FrameLayout(activity).apply {
            setBackgroundColor(palette.background)
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        col.addView(buildHeader())
        col.addView(buildSwitcher())
        content = SwipeFrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        col.addView(content)
        outer.addView(col)
        return outer
    }

    private fun buildHeader(): View {
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(palette.accent)
            setPadding(dp(8), dp(10), dp(8), dp(10))
        }
        val menu = iconButton(R.drawable.ic_menu_24dp, palette.onAccent) { activity.openMainDrawer() }
        titleView = TextView(activity).apply {
            setTextColor(palette.onAccent)
            textSize = 18f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(8), 0, dp(8), 0)
        }
        val today = TextView(activity).apply {
            text = "Today"
            setTextColor(palette.onAccent)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener {
                anchor = System.currentTimeMillis()
                selectedDay = CalendarTimelineView.midnight(anchor)
                render()
            }
        }
        val overflow = TextView(activity).apply {
            text = "⋮"
            textSize = 22f
            setTextColor(palette.onAccent)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(2), dp(10), dp(2))
            setOnClickListener { showOverflowMenu(it) }
        }
        bar.addView(menu); bar.addView(titleView); bar.addView(today); bar.addView(overflow)
        return bar
    }

    private fun showOverflowMenu(anchorView: View) {
        val menu = android.widget.PopupMenu(activity, anchorView)
        menu.menu.add(0, 1, 0, "Go to…")
        menu.menu.add(0, 2, 1, "Import calendar .ics")
        menu.menu.add(0, 3, 2, "Add CalDAV account")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { showGoToDatePicker(); true }
                2 -> { activity.launchCalendarIcsImport(); true }
                3 -> { CalendarDavx5.launch(activity); true }
                else -> false
            }
        }
        menu.show()
    }

    private fun showGoToDatePicker() {
        val cal = Calendar.getInstance().apply { timeInMillis = anchor }
        android.app.DatePickerDialog(
            activity,
            { _, year, month, day ->
                val picked = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year); set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, day)
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                pushHistory(); anchor = picked; selectedDay = picked; render()
            },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun buildSwitcher(): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(palette.surface)
        }
        for (m in Mode.values()) {
            val tv = TextView(activity).apply {
                text = m.name.lowercase().replaceFirstChar { it.uppercase() }
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(12))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { if (m != mode) { pushHistory(); mode = m; render() } }
            }
            switcherButtons[m] = tv
            row.addView(tv)
        }
        return row
    }

    /**
     * Interactive paging: the current period follows the finger and the adjacent period is
     * dragged in alongside it; on release it settles to the neighbour (commit) or snaps back.
     * Month drags vertically, week/day horizontally. Agenda doesn't page.
     */
    private inner class SwipeFrameLayout(ctx: android.content.Context) : FrameLayout(ctx) {
        private var downX = 0f
        private var downY = 0f
        private var dragging = false
        private val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
        private val vertical get() = mode == Mode.MONTH
        private val pageable get() = mode != Mode.AGENDA

        private var current: View? = null
        private var neighbor: View? = null
        private var neighborDir = 0

        override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean {
            if (!pageable) return false
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; dragging = false }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = kotlin.math.abs(ev.x - downX)
                    val dy = kotlin.math.abs(ev.y - downY)
                    if (vertical) {
                        if (dy > slop * 2 && dy > dx * 1.5f) { startDrag(); return true }
                    } else {
                        if (dx > slop * 2 && dx > dy * 1.5f) { startDrag(); return true }
                    }
                }
            }
            return dragging
        }

        private fun startDrag() {
            dragging = true
            current = getChildAt(0)
            neighbor = null
            neighborDir = 0
        }

        override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
            if (!pageable) return false
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y }
                android.view.MotionEvent.ACTION_MOVE -> if (dragging) onDragMove(ev)
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> if (dragging) endDrag(ev)
            }
            return true
        }

        private fun onDragMove(ev: android.view.MotionEvent) {
            val delta = if (vertical) ev.y - downY else ev.x - downX
            val size = (if (vertical) height else width).toFloat()
            if (size == 0f) return
            // Negative delta (up/left) → forward (dir +1); positive → backward (dir -1).
            val dir = if (delta < 0) 1 else -1
            if (dir != neighborDir) {
                neighbor?.let { removeView(it) }
                neighbor = buildNeighbor(dir).also { addView(it) }
                neighborDir = dir
            }
            current?.let { if (vertical) it.translationY = delta else it.translationX = delta }
            neighbor?.let {
                val base = dir * size
                if (vertical) it.translationY = base + delta else it.translationX = base + delta
            }
        }

        private fun endDrag(ev: android.view.MotionEvent) {
            dragging = false
            val delta = if (vertical) ev.y - downY else ev.x - downX
            val size = (if (vertical) height else width).toFloat()
            val cur = current
            val nb = neighbor
            val commit = nb != null && kotlin.math.abs(delta) > size * 0.25f
            val dur = 220L
            val interp = android.view.animation.DecelerateInterpolator(1.8f)

            if (commit) {
                applyShift(neighborDir)
                titleView.text = periodLabel()
                cur?.animate()?.apply {
                    val target = -neighborDir * size
                    if (vertical) translationY(target) else translationX(target)
                    duration = dur; interpolator = interp
                    withEndAction { cur.let { removeView(it) } }
                }?.start()
                nb?.animate()?.apply {
                    if (vertical) translationY(0f) else translationX(0f)
                    duration = dur; interpolator = interp
                }?.start()
            } else {
                cur?.animate()?.apply {
                    if (vertical) translationY(0f) else translationX(0f)
                    duration = dur; interpolator = interp
                }?.start()
                nb?.animate()?.apply {
                    val target = neighborDir * size
                    if (vertical) translationY(target) else translationX(target)
                    duration = dur; interpolator = interp
                    withEndAction { nb.let { removeView(it) } }
                }?.start()
            }
            current = null; neighbor = null; neighborDir = 0
        }
    }

    private fun iconButton(res: Int, tint: Int, onClick: () -> Unit): ImageView =
        ImageView(activity).apply {
            setImageResource(res)
            setColorFilter(tint, PorterDuff.Mode.SRC_IN)
            val p = dp(8); setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setOnClickListener { onClick() }
        }

    // ---- rendering ------------------------------------------------------------------------

    private fun render() {
        for ((m, tv) in switcherButtons) {
            val active = m == mode
            tv.setTextColor(if (active) palette.accent else palette.secondaryText)
            tv.setTypeface(null, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        titleView.text = periodLabel()
        content.removeAllViews()
        content.addView(buildCurrentContent())
    }

    private fun buildCurrentContent(): View = when (mode) {
        Mode.MONTH -> buildMonth()
        Mode.WEEK -> buildTimeline(weekDays())
        Mode.DAY -> buildTimeline(listOf(selectedDay))
        Mode.AGENDA -> buildAgenda()
    }

    /** Builds the content for the period [dir] steps away without committing the cursor. */
    private fun buildNeighbor(dir: Int): View {
        val savedAnchor = anchor
        val savedSelected = selectedDay
        applyShift(dir)
        val view = buildCurrentContent()
        anchor = savedAnchor
        selectedDay = savedSelected
        return view
    }

    /** Advances the cursor by [dir] periods for the current mode (no rendering). */
    private fun applyShift(dir: Int) {
        val cal = Calendar.getInstance().apply { timeInMillis = anchor }
        when (mode) {
            Mode.MONTH -> cal.add(Calendar.MONTH, dir)
            Mode.WEEK -> cal.add(Calendar.DAY_OF_MONTH, 7 * dir)
            Mode.DAY -> { cal.timeInMillis = selectedDay; cal.add(Calendar.DAY_OF_MONTH, dir) }
            Mode.AGENDA -> cal.add(Calendar.DAY_OF_MONTH, 30 * dir)
        }
        anchor = cal.timeInMillis
        if (mode == Mode.DAY) selectedDay = CalendarTimelineView.midnight(anchor)
    }

    private fun buildMonth(): View {
        val (from, to) = monthRange()
        return CalendarMonthView(activity).apply {
            palette = this@CalendarPanel.palette
            monthAnchor = anchor
            occurrences = CalendarRepository.occurrences(activity, from, to)
            selectedDay = this@CalendarPanel.selectedDay
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            onDaySelected = { day ->
                this@CalendarPanel.selectedDay = day
                this@CalendarPanel.anchor = day
            }
            onAddRequested = { day ->
                val start = Calendar.getInstance().apply {
                    timeInMillis = day
                    set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                openEditor(null, start)
            }
        }
    }

    private fun buildTimeline(days: List<Long>): View {
        val timeline = CalendarTimelineView(activity).apply {
            palette = this@CalendarPanel.palette
            this.days = days
            fitHeight = true
            occurrences = CalendarRepository.occurrences(activity, days.first(), days.last() + 86_400_000L)
            onEventClick = { occ -> openEditor(occ.event) }
            onSlotClick = { slot -> openEditor(null, slot) }
        }
        timeline.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        return timeline
    }

    private fun buildAgenda(): View {
        val from = CalendarTimelineView.midnight(anchor)
        val to = from + 60L * 86_400_000L
        val occs = CalendarRepository.occurrences(activity, from, to)
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(80))
        }
        if (occs.isEmpty()) {
            list.addView(TextView(activity).apply {
                text = "No upcoming events"
                setTextColor(palette.secondaryText)
                setPadding(dp(8), dp(24), dp(8), dp(8))
            })
        }
        val dayFmt = SimpleDateFormat("EEEE, d MMMM", Locale.ENGLISH)
        val timeFmt = SimpleDateFormat("HH:mm", Locale.ENGLISH)
        var lastDay = -1L
        for (occ in occs) {
            val day = CalendarTimelineView.midnight(occ.start)
            if (day != lastDay) {
                lastDay = day
                list.addView(TextView(activity).apply {
                    text = dayFmt.format(Date(day))
                    setTextColor(palette.accent)
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(dp(4), dp(16), dp(4), dp(6))
                })
            }
            list.addView(agendaRow(occ, timeFmt))
        }
        return ScrollView(activity).apply {
            addView(list)
            setBackgroundColor(palette.background)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    private fun agendaRow(occ: EventOccurrence, timeFmt: SimpleDateFormat): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(10), dp(8), dp(10))
            setOnClickListener { openEditor(occ.event) }
        }
        val bar = View(activity).apply {
            setBackgroundColor(palette.accent)
            layoutParams = LinearLayout.LayoutParams(dp(4), ViewGroup.LayoutParams.MATCH_PARENT)
                .apply { marginEnd = dp(10) }
        }
        val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(activity).apply {
            text = occ.event.title
            setTextColor(palette.text)
            textSize = 15f
        })
        val time = if (occ.event.allDay) "All day"
            else "${timeFmt.format(Date(occ.start))} – ${timeFmt.format(Date(occ.end))}"
        val sub = buildString {
            append(time)
            if (occ.event.location.isNotBlank()) append(" · ").append(occ.event.location)
            if (occ.event.recurrence != null) append("  ⟳")
            if (occ.event.reminderMinutes != null) append("  🔔")
        }
        texts.addView(TextView(activity).apply {
            text = sub
            setTextColor(palette.secondaryText)
            textSize = 12f
        })
        row.addView(bar); row.addView(texts)
        return row
    }

    // ---- navigation / labels --------------------------------------------------------------

    private fun periodLabel(): String {
        val cal = Calendar.getInstance().apply { timeInMillis = anchor }
        return when (mode) {
            Mode.MONTH -> SimpleDateFormat("MMMM yyyy", Locale.ENGLISH).format(cal.time)
            Mode.WEEK -> {
                val ws = weekDays().first(); val we = weekDays().last()
                val f = SimpleDateFormat("d MMM", Locale.ENGLISH)
                "${f.format(Date(ws))} – ${f.format(Date(we))}"
            }
            Mode.DAY -> SimpleDateFormat("EEE, d MMM yyyy", Locale.ENGLISH).format(Date(selectedDay))
            Mode.AGENDA -> "Agenda"
        }
    }

    private fun weekDays(): List<Long> {
        val cal = Calendar.getInstance().apply { timeInMillis = anchor }
        while (cal.get(Calendar.DAY_OF_WEEK) != cal.firstDayOfWeek) cal.add(Calendar.DAY_OF_MONTH, -1)
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        return (0 until 7).map { (cal.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, it) }.timeInMillis }
    }

    private fun monthRange(): Pair<Long, Long> {
        val cal = Calendar.getInstance().apply {
            timeInMillis = anchor; set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }
        val from = cal.timeInMillis - 7L * 86_400_000L
        cal.add(Calendar.MONTH, 1)
        val to = cal.timeInMillis + 7L * 86_400_000L
        return from to to
    }

    // ---- editor + persistence -------------------------------------------------------------

    private fun openEditor(existing: CalendarEvent?, slotStart: Long? = null) {
        val defaultStart = slotStart ?: defaultNewStart()
        val calId = CalendarRepository.defaultCalendarId(activity)
        CalendarEventEditor.show(
            context = activity,
            palette = palette,
            existing = existing,
            defaultStart = defaultStart,
            calendarId = calId,
            onSave = { event -> CalendarRepository.upsert(activity, event); afterChange() },
            onDelete = { event -> CalendarRepository.delete(activity, event); afterChange() }
        )
    }

    private fun defaultNewStart(): Long = Calendar.getInstance().apply {
        timeInMillis = selectedDay
        val now = Calendar.getInstance()
        if (CalendarTimelineView.isSameDay(selectedDay, now.timeInMillis)) {
            set(Calendar.HOUR_OF_DAY, now.get(Calendar.HOUR_OF_DAY) + 1)
        } else set(Calendar.HOUR_OF_DAY, 9)
        set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun afterChange() {
        CalendarReminderScheduler.reschedule(activity)
        render()
        triggerSync()
    }

    // ---- sync + permissions ---------------------------------------------------------------

    private fun triggerSync() {
        if (CalendarPrefs.provider(activity) != CalendarPrefs.Provider.JMAP) return
        val account = CalendarAccount.current(activity) ?: return
        scope.launch {
            CalendarSync.sync(activity.applicationContext, account)
            CalendarReminderScheduler.reschedule(activity.applicationContext)
            render()
        }
    }

    private fun showInAppMessage(text: String) {
        val snack = com.google.android.material.snackbar.Snackbar.make(
            this, text, com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
        snack.view.setBackgroundColor(palette.surface)
        snack.setTextColor(palette.text)
        snack.setActionTextColor(palette.accent)
        snack.show()
    }

    private fun maybePromptExactAlarm() {
        val hasReminders = CalendarStore.active(activity).any { it.reminderMinutes != null }
        if (!hasReminders || CalendarReminderScheduler.canScheduleExact(activity)) return
        runCatching { activity.startActivity(CalendarReminderScheduler.requestExactAlarmIntent()) }
    }
}
