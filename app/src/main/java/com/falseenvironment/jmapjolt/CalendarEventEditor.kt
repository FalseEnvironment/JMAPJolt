package com.falseenvironment.jmapjolt

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID

/** Builds and shows the add/edit event dialog, theme-aware, returning the result via callbacks. */
object CalendarEventEditor {

    private val reminderOptions = listOf(
        "No reminder" to null,
        "At start time" to 0,
        "5 minutes before" to 5,
        "10 minutes before" to 10,
        "15 minutes before" to 15,
        "30 minutes before" to 30,
        "1 hour before" to 60,
        "1 day before" to 1440
    )

    private val repeatOptions = listOf<Pair<String, RecurrenceFreq?>>(
        "Does not repeat" to null,
        "Daily" to RecurrenceFreq.DAILY,
        "Weekly" to RecurrenceFreq.WEEKLY,
        "Monthly" to RecurrenceFreq.MONTHLY,
        "Yearly" to RecurrenceFreq.YEARLY
    )

    /** Mix [color] toward [towards] by [ratio] (0..1). */
    private fun blend(color: Int, towards: Int, ratio: Float): Int {
        val r = (Color.red(color) * (1 - ratio) + Color.red(towards) * ratio).toInt()
        val g = (Color.green(color) * (1 - ratio) + Color.green(towards) * ratio).toInt()
        val b = (Color.blue(color) * (1 - ratio) + Color.blue(towards) * ratio).toInt()
        return Color.rgb(r, g, b)
    }

    fun show(
        context: Context,
        palette: CalendarTheme.Palette,
        existing: CalendarEvent?,
        defaultStart: Long,
        calendarId: String,
        onSave: (CalendarEvent) -> Unit,
        onDelete: (CalendarEvent) -> Unit
    ) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }

        fun label(text: String) = TextView(context).apply {
            this.text = text
            setTextColor(palette.secondaryText)
            textSize = 12f
            setPadding(0, dp(10), 0, dp(2))
        }

        // Rounded input boxes matching the login fields (theme-aware fill + subtle border).
        fun styledEdit(hint: String, value: String, lines: Int = 1) = EditText(context).apply {
            this.hint = hint
            setText(value)
            setTextColor(palette.text)
            setHintTextColor(palette.secondaryText)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(palette.background)
                setStroke(dp(1), CalendarTimelineView.adjustAlpha(palette.secondaryText, 0.4f))
            }
            setPadding(dp(16), dp(13), dp(16), dp(13))
            gravity = if (lines > 1) Gravity.TOP or Gravity.START else Gravity.CENTER_VERTICAL
            inputType = if (lines > 1)
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE else InputType.TYPE_CLASS_TEXT
            setLines(lines)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

        val titleEdit = styledEdit("Title", existing?.title ?: "")
        val descEdit = styledEdit("Description", existing?.description ?: "", lines = 2)
        val preservedLocation = existing?.location ?: ""

        // working state — independent start and end instants (supports multi-day events)
        val cal = Calendar.getInstance().apply { timeInMillis = existing?.start ?: defaultStart }
        val endCal = Calendar.getInstance().apply {
            timeInMillis = cal.timeInMillis + (existing?.durationMinutes ?: 60) * 60_000L
        }
        var allDay = existing?.allDay ?: false

        val startDateBtn = Button(context)
        val startTimeBtn = Button(context)
        val endDateBtn = Button(context)
        val endTimeBtn = Button(context)
        listOf(startDateBtn, startTimeBtn, endDateBtn, endTimeBtn).forEach {
            it.setTextColor(palette.accent)
            it.setBackgroundColor(Color.TRANSPARENT)
            it.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            it.isAllCaps = false
            it.minWidth = 0
            it.minimumWidth = 0
        }

        val dateFmt = SimpleDateFormat("EEE, d MMM yyyy", Locale.ENGLISH)
        val timeFmt = SimpleDateFormat("HH:mm", Locale.ENGLISH)
        fun refresh() {
            startDateBtn.text = dateFmt.format(cal.time)
            startTimeBtn.text = timeFmt.format(cal.time)
            endDateBtn.text = dateFmt.format(endCal.time)
            endTimeBtn.text = timeFmt.format(endCal.time)
            startTimeBtn.visibility = if (allDay) View.GONE else View.VISIBLE
            endTimeBtn.visibility = if (allDay) View.GONE else View.VISIBLE
        }

        // Reject any change that puts the end at/before the start; revert the offending field.
        fun applyOrReject(target: Calendar, set: () -> Unit, undo: () -> Unit) {
            set()
            if (endCal.timeInMillis <= cal.timeInMillis) {
                undo()
                snackbar(root, palette, "End must be after start")
            }
            refresh()
        }

        startDateBtn.setOnClickListener {
            DatePickerDialog(context, { _, y, m, d ->
                val prevY = cal.get(Calendar.YEAR); val prevM = cal.get(Calendar.MONTH)
                val prevD = cal.get(Calendar.DAY_OF_MONTH)
                applyOrReject(cal,
                    set = { cal.set(Calendar.YEAR, y); cal.set(Calendar.MONTH, m); cal.set(Calendar.DAY_OF_MONTH, d) },
                    undo = { cal.set(Calendar.YEAR, prevY); cal.set(Calendar.MONTH, prevM); cal.set(Calendar.DAY_OF_MONTH, prevD) })
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }
        startTimeBtn.setOnClickListener {
            TimePickerDialog(context, { _, h, min ->
                val prevH = cal.get(Calendar.HOUR_OF_DAY); val prevMin = cal.get(Calendar.MINUTE)
                applyOrReject(cal,
                    set = { cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, min)
                        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0) },
                    undo = { cal.set(Calendar.HOUR_OF_DAY, prevH); cal.set(Calendar.MINUTE, prevMin) })
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
        }
        endDateBtn.setOnClickListener {
            DatePickerDialog(context, { _, y, m, d ->
                val prevY = endCal.get(Calendar.YEAR); val prevM = endCal.get(Calendar.MONTH)
                val prevD = endCal.get(Calendar.DAY_OF_MONTH)
                applyOrReject(endCal,
                    set = { endCal.set(Calendar.YEAR, y); endCal.set(Calendar.MONTH, m); endCal.set(Calendar.DAY_OF_MONTH, d) },
                    undo = { endCal.set(Calendar.YEAR, prevY); endCal.set(Calendar.MONTH, prevM); endCal.set(Calendar.DAY_OF_MONTH, prevD) })
            }, endCal.get(Calendar.YEAR), endCal.get(Calendar.MONTH), endCal.get(Calendar.DAY_OF_MONTH)).show()
        }
        endTimeBtn.setOnClickListener {
            TimePickerDialog(context, { _, h, min ->
                val prevH = endCal.get(Calendar.HOUR_OF_DAY); val prevMin = endCal.get(Calendar.MINUTE)
                applyOrReject(endCal,
                    set = { endCal.set(Calendar.HOUR_OF_DAY, h); endCal.set(Calendar.MINUTE, min)
                        endCal.set(Calendar.SECOND, 0); endCal.set(Calendar.MILLISECOND, 0) },
                    undo = { endCal.set(Calendar.HOUR_OF_DAY, prevH); endCal.set(Calendar.MINUTE, prevMin) })
            }, endCal.get(Calendar.HOUR_OF_DAY), endCal.get(Calendar.MINUTE), true).show()
        }

        // Animated, accent-tinted toggle matching the settings switches.
        val darkAccent = blend(palette.accent, Color.BLACK, 0.5f)
        val lightAccent = blend(palette.accent, Color.WHITE, 0.3f)
        val thumbTint = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(lightAccent, darkAccent)
        )
        val trackTint = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(palette.accent, darkAccent)
        )
        val allDaySwitch = androidx.appcompat.widget.SwitchCompat(context).apply {
            text = "All day"
            setTextColor(palette.text)
            textSize = 15f
            isChecked = allDay
            thumbTintList = thumbTint
            trackTintList = trackTint
            // Accent-colored toggle halo/ripple (previously an uncolored grey animation).
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(
                    (0x55 shl 24) or (palette.accent and 0x00FFFFFF)),
                null,
                android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    setColor(Color.WHITE)
                }
            )
            setOnCheckedChangeListener { _, checked -> allDay = checked; refresh() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(22) }
        }

        fun spinner(options: List<String>, selected: Int): Spinner {
            val sp = Spinner(context)
            val adapter = object : ArrayAdapter<String>(
                context, android.R.layout.simple_spinner_item, options
            ) {
                override fun getView(p: Int, cv: View?, parent: android.view.ViewGroup): View =
                    (super.getView(p, cv, parent) as TextView).apply {
                        setTextColor(palette.text); textSize = 15f
                    }
                override fun getDropDownView(p: Int, cv: View?, parent: android.view.ViewGroup): View =
                    (super.getDropDownView(p, cv, parent) as TextView).apply {
                        setTextColor(palette.text)
                        setBackgroundColor(palette.surface)
                        setPadding(dp(16), dp(12), dp(16), dp(12))
                    }
            }
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            sp.adapter = adapter
            sp.setSelection(selected)
            // Theme the dropdown popup background and accent the closed-state arrow.
            sp.setPopupBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(palette.surface)
            })
            sp.background?.setColorFilter(palette.accent, PorterDuff.Mode.SRC_IN)
            return sp
        }

        val reminderIdx = reminderOptions.indexOfFirst { it.second == existing?.reminderMinutes }
            .let { if (it < 0) 0 else it }
        val reminderSpinner = spinner(reminderOptions.map { it.first }, reminderIdx)

        val repeatIdx = repeatOptions.indexOfFirst { it.second == existing?.recurrence?.freq }
            .let { if (it < 0) 0 else it }
        val repeatSpinner = spinner(repeatOptions.map { it.first }, repeatIdx)

        root.addView(titleEdit)
        root.addView(descEdit)
        root.addView(allDaySwitch)
        fun dateTimeRow(dateBtn: Button, timeBtn: Button) = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(dateBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(timeBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        }
        root.addView(label("Start")); root.addView(dateTimeRow(startDateBtn, startTimeBtn))
        root.addView(label("End")); root.addView(dateTimeRow(endDateBtn, endTimeBtn))
        root.addView(label("Reminder")); root.addView(reminderSpinner)
        root.addView(label("Repeat")); root.addView(repeatSpinner)
        refresh()

        // Squared, themed floating card (no default AlertDialog title), matching the app's
        // other custom dialogs (e.g. the sign-out confirmation).
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(10), 0, 0)
        }
        root.addView(btnRow)

        val scroll = ScrollView(context).apply {
            addView(root)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(6).toFloat()
                setColor(palette.surface)
            }
        }

        val dialog = AlertDialog.Builder(context).setView(scroll).create()

        fun textButton(text: String, color: Int, bold: Boolean, onClick: () -> Unit) =
            TextView(context).apply {
                this.text = text
                textSize = 14f
                setTextColor(color)
                if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(dp(16), dp(10), dp(16), dp(8))
                isClickable = true; isFocusable = true
                setOnClickListener { onClick() }
            }

        fun commit() {
            if (allDay) {
                listOf(cal, endCal).forEach {
                    it.set(Calendar.HOUR_OF_DAY, 0); it.set(Calendar.MINUTE, 0)
                    it.set(Calendar.SECOND, 0); it.set(Calendar.MILLISECOND, 0)
                }
            }
            // End is guaranteed > start by the pickers; clamp defensively.
            val durationMin = (((endCal.timeInMillis - cal.timeInMillis) / 60_000L).toInt())
                .coerceAtLeast(if (allDay) 1440 else 1)
            val freq = repeatOptions[repeatSpinner.selectedItemPosition].second
            val event = (existing ?: CalendarEvent(
                id = UUID.randomUUID().toString(),
                calendarId = calendarId,
                title = "",
                start = cal.timeInMillis,
                durationMinutes = durationMin
            )).copy(
                title = titleEdit.text.toString().trim().ifBlank { "(no title)" },
                location = preservedLocation,
                description = descEdit.text.toString().trim(),
                start = cal.timeInMillis,
                durationMinutes = durationMin,
                allDay = allDay,
                reminderMinutes = reminderOptions[reminderSpinner.selectedItemPosition].second,
                recurrence = freq?.let { RecurrenceRule(freq = it) },
                updatedAt = System.currentTimeMillis()
            )
            onSave(event)
            dialog.dismiss()
        }

        if (existing != null) {
            btnRow.addView(textButton("Delete", Color.parseColor("#E5484D"), false) {
                onDelete(existing); dialog.dismiss()
            })
        }
        btnRow.addView(textButton("Cancel", palette.secondaryText, false) { dialog.dismiss() })
        btnRow.addView(textButton("Save", palette.accent, true) { commit() })

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dialog.window?.attributes?.let { lp ->
            lp.width = (context.resources.displayMetrics.widthPixels * 0.92f).toInt()
            dialog.window?.attributes = lp
        }
    }

    /** Bottom in-app snackbar matching the app's themed style (surface fill, themed text). */
    private fun snackbar(anchor: View, palette: CalendarTheme.Palette, text: String) {
        val snack = com.google.android.material.snackbar.Snackbar.make(
            anchor, text, com.google.android.material.snackbar.Snackbar.LENGTH_SHORT)
        snack.view.setBackgroundColor(Color.parseColor("#F6C7C7"))
        snack.setTextColor(Color.parseColor("#7A1F1F"))
        snack.setActionTextColor(Color.parseColor("#7A1F1F"))
        snack.show()
    }
}
