package com.falseenvironment.jmapjolt

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** User-defined label backed by a JMAP keyword; name/color/order live locally. */
data class EmailLabel(
    val keyword: String,
    var name: String,
    var colorHex: String
)

// ---------------------------------------------------------------------------
// Persistence
// ---------------------------------------------------------------------------

internal fun MainActivity.labelsForAccount(accountEmail: String): List<EmailLabel> {
    if (accountEmail.isBlank()) return emptyList()
    val cached = accountLabelsCache[accountEmail]
    if (cached != null) return cached

    val key = "${MainActivity.KEY_LABELS_JSON}_$accountEmail"
    val raw = getSharedPreferences(MainActivity.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        .getString(key, null) ?: return emptyList()
    val list = mutableListOf<EmailLabel>()
    try {
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val kw = o.optString("keyword")
            if (kw.isBlank()) continue
            list.add(EmailLabel(kw, o.optString("name", kw), o.optString("color", "#3D8BFD")))
        }
    } catch (e: Exception) {
        Log.e(MainActivity.TAG, "labelsForAccount failed", e)
    }
    accountLabelsCache[accountEmail] = list
    return list
}

internal fun MainActivity.loadLabels() {
    labels.clear()
    val email = currentAccountEmail ?: return
    val list = labelsForAccount(email)
    labels.addAll(list)
}

internal fun MainActivity.saveLabels() {
    val email = currentAccountEmail ?: return
    val key = "${MainActivity.KEY_LABELS_JSON}_$email"
    val arr = JSONArray()
    labels.forEach { l ->
        arr.put(JSONObject().apply {
            put("keyword", l.keyword)
            put("name", l.name)
            put("color", l.colorHex)
        })
    }
    getSharedPreferences(MainActivity.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        .edit().putString(key, arr.toString()).apply()
    accountLabelsCache[email] = labels.toList()
}

internal fun MainActivity.labelByKeyword(keyword: String): EmailLabel? =
    labels.find { it.keyword == keyword }

/** First label of an email that the user still has configured, or null. */
internal fun MainActivity.firstLabelOf(email: DisplayEmail): EmailLabel? {
    val accountEmail = email.accountEmail.ifBlank { currentAccountEmail.orEmpty() }
    val accountLabels = labelsForAccount(accountEmail)
    return email.labels.firstNotNullOfOrNull { kw -> accountLabels.find { it.keyword == kw } }
}

/**
 * True if the active account may edit this email's labels. Labels are a per-account
 * JMAP concept, so an email can only be (un)labelled while its owning account is active.
 * Blank accountEmail means a single-account context → treated as owned.
 */
internal fun MainActivity.ownsEmail(email: DisplayEmail): Boolean {
    val owner = email.accountEmail
    if (owner.isBlank()) return true
    return owner.equals(currentAccountEmail.orEmpty(), ignoreCase = true)
}

internal fun MainActivity.ownsEmailId(emailId: String): Boolean {
    val email = emails.find { it.id == emailId }
        ?: baseEmails.find { it.id == emailId }
        ?: return true
    return ownsEmail(email)
}

/** All configured labels of an email, in the email's keyword order. */
internal fun MainActivity.labelsOf(email: DisplayEmail): List<EmailLabel> {
    val accountEmail = email.accountEmail.ifBlank { currentAccountEmail.orEmpty() }
    val accountLabels = labelsForAccount(accountEmail)
    return email.labels.mapNotNull { kw -> accountLabels.find { it.keyword == kw } }
}

/** JMAP keywords must be lowercase and free of forbidden chars (RFC 8621). */
internal fun labelNameToKeyword(name: String): String =
    name.trim().lowercase()
        .replace(Regex("[\\s()\\[\\]{}%*\"\\\\]+"), "-")
        .replace(Regex("^\\$+"), "")
        .take(64)

// ---------------------------------------------------------------------------
// Hue wheel (same wheel style as the accent color picker in ThemeHelper)
// ---------------------------------------------------------------------------

internal fun MainActivity.buildHueWheel(pendingHsv: FloatArray, diameterDp: Int): View {
    val dp = resources.displayMetrics.density
    val wheel = object : View(this) {
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val dotBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = Color.WHITE
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            val outerR = minOf(cx, cy) - 6 * dp
            val innerR = outerR * 0.60f
            val ringR = (outerR + innerR) / 2f
            val ringW = outerR - innerR

            canvas.save()
            canvas.rotate(-90f, cx, cy)
            val hueColors = IntArray(361) { i -> Color.HSVToColor(floatArrayOf(i.toFloat() % 360f, 1f, 1f)) }
            ringPaint.shader = SweepGradient(cx, cy, hueColors, null)
            ringPaint.strokeWidth = ringW
            canvas.drawCircle(cx, cy, ringR, ringPaint)
            canvas.restore()

            fillPaint.color = Color.HSVToColor(pendingHsv)
            canvas.drawCircle(cx, cy, innerR - 4 * dp, fillPaint)

            val rad = Math.toRadians((pendingHsv[0] - 90.0))
            val ix = cx + ringR * kotlin.math.cos(rad).toFloat()
            val iy = cy + ringR * kotlin.math.sin(rad).toFloat()
            dotFill.color = Color.WHITE
            canvas.drawCircle(ix, iy, ringW / 2f + 2.5f * dp, dotFill)
            dotFill.color = Color.HSVToColor(floatArrayOf(pendingHsv[0], 1f, 0.85f))
            canvas.drawCircle(ix, iy, ringW / 2f - 0.5f * dp, dotFill)
            dotBorder.strokeWidth = 2f * dp
            canvas.drawCircle(ix, iy, ringW / 2f + 2.5f * dp, dotBorder)
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (ev.action == MotionEvent.ACTION_DOWN || ev.action == MotionEvent.ACTION_MOVE) {
                val cx = width / 2f; val cy = height / 2f
                val dx = ev.x - cx; val dy = ev.y - cy
                var hue = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f
                if (hue < 0f) hue += 360f
                if (hue >= 360f) hue -= 360f
                pendingHsv[0] = hue
                invalidate()
                return true
            }
            return super.onTouchEvent(ev)
        }
    }
    val sz = (diameterDp * dp).toInt()
    wheel.layoutParams = LinearLayout.LayoutParams(sz, sz)
    return wheel
}

internal fun hsvHex(hsv: FloatArray) = "#%06X".format(0xFFFFFF and Color.HSVToColor(hsv))

/**
 * Squared card dialog (same look as the label/Move-to sheets) replacing the
 * default gray AlertDialog frame. [onPositive] returns true to dismiss.
 */
internal fun MainActivity.showCardDialog(
    title: String,
    content: View,
    positiveLabel: String? = null,
    onPositive: (() -> Boolean)? = null
): AlertDialog {
    val dp = resources.displayMetrics.density
    val secondaryColor = if (currentTheme == "light") "#757575".toColorInt() else "#BDBDBD".toColorInt()
    val accentInt = currentAccentColor.toColorInt()

    val outer = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16 * dp
            setColor(getDialogBackgroundColor())
        }
        elevation = 8 * dp
    }
    outer.addView(TextView(this).apply {
        text = title
        textSize = 13f
        setTextColor(secondaryColor)
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.08f
        setPadding((20 * dp).toInt(), (16 * dp).toInt(), (20 * dp).toInt(), (8 * dp).toInt())
    })
    outer.addView(View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        setBackgroundColor(0x22FFFFFF)
    })
    outer.addView(content)

    val dialog = AlertDialog.Builder(this).setView(outer).create()

    val btnRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        setPadding((12 * dp).toInt(), (4 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
    }
    fun textBtn(label: String, color: Int, bold: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 14f
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
        isClickable = true; isFocusable = true
        background = ContextCompat.getDrawable(
            this@showCardDialog,
            android.util.TypedValue().also {
                theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
            }.resourceId
        )
        setOnClickListener { onClick() }
    }
    btnRow.addView(textBtn("Cancel", secondaryColor, false) { dialog.dismiss() })
    if (positiveLabel != null) {
        btnRow.addView(textBtn(positiveLabel, accentInt, true) {
            if (onPositive?.invoke() != false) dialog.dismiss()
        })
    }
    outer.addView(btnRow)

    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    dialog.show()
    return dialog
}

/** Accent-color preset swatches that update [pendingHsv] and redraw [wheel]. */
internal fun MainActivity.buildPresetSwatchRow(pendingHsv: FloatArray, wheel: View): LinearLayout {
    val dp = resources.displayMetrics.density
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }
    val swatchViews = mutableListOf<View>()
    fun refresh() {
        val selected = hsvHex(pendingHsv)
        swatchViews.forEach { v ->
            val col = v.tag as String
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(col.toColorInt())
                if (col.equals(selected, ignoreCase = true)) setStroke((3 * dp).toInt(), Color.WHITE)
            }
        }
    }
    MainActivity.ACCENT_COLORS.forEach { color ->
        val sz = (34 * dp).toInt()
        val swatch = View(this).apply {
            tag = color
            layoutParams = LinearLayout.LayoutParams(sz, sz).also {
                it.setMargins((5 * dp).toInt(), 0, (5 * dp).toInt(), 0)
            }
            setOnClickListener {
                val hsv = FloatArray(3)
                Color.colorToHSV(color.toColorInt(), hsv)
                pendingHsv[0] = hsv[0]
                pendingHsv[1] = maxOf(hsv[1], 0.7f)
                pendingHsv[2] = maxOf(hsv[2], 0.8f)
                wheel.invalidate()
                refresh()
            }
        }
        swatchViews.add(swatch)
        row.addView(swatch)
    }
    // Repaint selection strokes whenever the wheel handles a touch too.
    wheel.setOnTouchListener { v, ev ->
        val handled = v.onTouchEvent(ev)
        if (handled) refresh()
        handled
    }
    refresh()
    return row
}

internal fun MainActivity.showLabelColorWheelDialog(
    title: String,
    initialHex: String,
    onApply: (String) -> Unit
) {
    val dp = resources.displayMetrics.density
    val pendingHsv = FloatArray(3).also { hsv ->
        Color.colorToHSV(
            runCatching { initialHex.toColorInt() }.getOrDefault("#3D8BFD".toColorInt()),
            hsv
        )
        if (hsv[1] < 0.4f) hsv[1] = 0.85f
        if (hsv[2] < 0.4f) hsv[2] = 0.95f
    }
    val wheel = buildHueWheel(pendingHsv, 224)
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val p = (16 * dp).toInt()
        setPadding(p, (14 * dp).toInt(), p, (4 * dp).toInt())
        addView(wheel.also { (it.layoutParams as LinearLayout.LayoutParams).bottomMargin = (18 * dp).toInt() })
        addView(buildPresetSwatchRow(pendingHsv, wheel))
    }
    showCardDialog(title, root, "Apply") {
        onApply(hsvHex(pendingHsv))
        true
    }
}

// ---------------------------------------------------------------------------
// Create label dialog (name + hue wheel)
// ---------------------------------------------------------------------------

internal fun MainActivity.showCreateLabelDialog(onCreated: (EmailLabel) -> Unit) {
    val dp = resources.displayMetrics.density
    val textColor = if (currentTheme == "light") "#212121".toColorInt() else Color.WHITE
    val hintColor = if (currentTheme == "light") "#9E9E9E".toColorInt() else "#616161".toColorInt()
    val pendingHsv = floatArrayOf(210f, 0.75f, 0.95f)

    val nameInput = EditText(this).apply {
        hint = "Label name"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        filters = arrayOf(android.text.InputFilter.LengthFilter(18))
        setTextColor(textColor)
        setHintTextColor(hintColor)
        backgroundTintList = ColorStateList.valueOf(hintColor)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = (16 * dp).toInt() }
    }
    val wheel = buildHueWheel(pendingHsv, 200)
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val p = (20 * dp).toInt()
        setPadding(p, (14 * dp).toInt(), p, (4 * dp).toInt())
        addView(nameInput)
        addView(wheel.also { (it.layoutParams as LinearLayout.LayoutParams).bottomMargin = (16 * dp).toInt() })
        addView(buildPresetSwatchRow(pendingHsv, wheel))
    }

    // Returning false keeps the dialog open on an empty/duplicate name.
    showCardDialog("New label", root, "Create") {
        val name = nameInput.text.toString().trim()
        if (name.isEmpty()) {
            nameInput.error = "Name required"
            return@showCardDialog false
        }
        val keyword = labelNameToKeyword(name)
        if (keyword.isEmpty()) {
            nameInput.error = "Invalid name"
            return@showCardDialog false
        }
        if (labels.any { it.keyword == keyword }) {
            nameInput.error = "Label already exists"
            return@showCardDialog false
        }
        val label = EmailLabel(keyword, name, hsvHex(pendingHsv))
        labels.add(label)
        saveLabels()
        rebuildDrawerMenuPublic()
        onCreated(label)
        true
    }
}

// ---------------------------------------------------------------------------
// Label picker (apply/remove labels on emails) — styled like the Move-to sheet
// ---------------------------------------------------------------------------

internal fun MainActivity.showLabelPicker(ids: List<String>) {
    if (ids.isEmpty()) return
    // Labels are per-account: block (un)labelling any email that isn't owned by the
    // active account, and point the user at the account they need to switch to.
    val foreign = ids.mapNotNull { id ->
        (emails.find { it.id == id } ?: baseEmails.find { it.id == id })
    }.firstOrNull { !ownsEmail(it) }
    if (foreign != null) {
        showThemedSnackbar(getString(R.string.label_wrong_account, foreign.accountEmail))
        return
    }
    val dp = resources.displayMetrics.density
    val bgColor = getDialogBackgroundColor()
    val textColor = if (currentTheme == "light") "#212121".toColorInt() else Color.WHITE
    val secondaryColor = if (currentTheme == "light") "#757575".toColorInt() else "#BDBDBD".toColorInt()
    val accentInt = currentAccentColor.toColorInt()

    var dialog: AlertDialog? = null

    val outer = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16 * dp
            setColor(bgColor)
        }
        elevation = 8 * dp
    }

    outer.addView(TextView(this).apply {
        text = "Label"
        textSize = 13f
        setTextColor(secondaryColor)
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.08f
        setPadding((20 * dp).toInt(), (16 * dp).toInt(), (20 * dp).toInt(), (8 * dp).toInt())
    })
    outer.addView(View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        setBackgroundColor(0x22FFFFFF)
    })

    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val scroll = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            minOf(labels.size + 1, 6) * (52 * dp).toInt()
        )
    }

    fun selectableBg() = ContextCompat.getDrawable(
        this,
        android.util.TypedValue().also {
            theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }.resourceId
    )

    fun targetEmails(): List<DisplayEmail> =
        ids.mapNotNull { id -> emails.find { it.id == id } ?: baseEmails.find { it.id == id } }

    labels.forEach { label ->
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (52 * dp).toInt()
            )
            setPadding((20 * dp).toInt(), 0, (20 * dp).toInt(), 0)
            isClickable = true
            isFocusable = true
            background = selectableBg()
        }
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_lucide_tag)
            imageTintList = ColorStateList.valueOf(label.colorHex.toColorInt())
            val sz = (20 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz).also { it.marginEnd = (16 * dp).toInt() }
            scaleType = ImageView.ScaleType.FIT_CENTER
        })
        row.addView(TextView(this).apply {
            text = label.name
            textSize = 15f
            setTextColor(textColor)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val check = ImageView(this).apply {
            setImageResource(R.drawable.ic_lucide_check)
            imageTintList = ColorStateList.valueOf(accentInt)
            val sz = (18 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            visibility = if (targetEmails().isNotEmpty() &&
                targetEmails().all { label.keyword in it.labels }) View.VISIBLE else View.INVISIBLE
        }
        row.addView(check)
        row.setOnClickListener {
            val applied = targetEmails().isNotEmpty() &&
                targetEmails().all { label.keyword in it.labels }
            val newState = !applied
            applyLabelToEmails(ids, label, newState)
            check.visibility = if (newState) View.VISIBLE else View.INVISIBLE
        }
        list.addView(row)
    }

    val createRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (52 * dp).toInt()
        )
        setPadding((20 * dp).toInt(), 0, (20 * dp).toInt(), 0)
        isClickable = true
        isFocusable = true
        background = selectableBg()
        setOnClickListener {
            dialog?.dismiss()
            showCreateLabelDialog { label -> applyLabelToEmails(ids, label, true) }
        }
    }
    createRow.addView(ImageView(this).apply {
        setImageResource(R.drawable.ic_lucide_plus)
        imageTintList = ColorStateList.valueOf(accentInt)
        val sz = (20 * dp).toInt()
        layoutParams = LinearLayout.LayoutParams(sz, sz).also { it.marginEnd = (16 * dp).toInt() }
        scaleType = ImageView.ScaleType.FIT_CENTER
    })
    createRow.addView(TextView(this).apply {
        text = "Create new label"
        textSize = 15f
        setTextColor(accentInt)
        typeface = Typeface.DEFAULT_BOLD
    })
    list.addView(createRow)

    scroll.addView(list)
    outer.addView(scroll)

    dialog = AlertDialog.Builder(this).setView(outer).create()
    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    dialog.show()
}

private fun MainActivity.rebuildLabelPicker(ids: List<String>, dialogRef: AlertDialog?) {
    dialogRef?.dismiss()
    showLabelPicker(ids)
}

internal fun MainActivity.showEditLabelDialog(label: EmailLabel, onSaved: () -> Unit) {
    val dp = resources.displayMetrics.density
    val textColor = if (currentTheme == "light") "#212121".toColorInt() else Color.WHITE
    val hintColor = if (currentTheme == "light") "#9E9E9E".toColorInt() else "#616161".toColorInt()

    val pendingHsv = FloatArray(3).also { hsv ->
        Color.colorToHSV(
            runCatching { label.colorHex.toColorInt() }.getOrDefault("#3D8BFD".toColorInt()),
            hsv
        )
    }

    val nameInput = EditText(this).apply {
        hint = "Label name"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        filters = arrayOf(android.text.InputFilter.LengthFilter(18))
        setText(label.name)
        setTextColor(textColor)
        setHintTextColor(hintColor)
        backgroundTintList = ColorStateList.valueOf(hintColor)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = (16 * dp).toInt() }
    }
    val wheel = buildHueWheel(pendingHsv, 200)
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val p = (20 * dp).toInt()
        setPadding(p, (14 * dp).toInt(), p, (4 * dp).toInt())
        addView(nameInput)
        addView(wheel.also { (it.layoutParams as LinearLayout.LayoutParams).bottomMargin = (16 * dp).toInt() })
        addView(buildPresetSwatchRow(pendingHsv, wheel))
    }

    showCardDialog("Edit label", root, "Save") {
        val name = nameInput.text.toString().trim()
        if (name.isEmpty()) {
            nameInput.error = "Name required"
            return@showCardDialog false
        }
        val newKeyword = labelNameToKeyword(name)
        if (newKeyword.isEmpty()) {
            nameInput.error = "Invalid name"
            return@showCardDialog false
        }
        if (newKeyword != label.keyword && labels.any { it.keyword == newKeyword }) {
            nameInput.error = "Label already exists"
            return@showCardDialog false
        }
        label.name = name
        label.colorHex = hsvHex(pendingHsv)
        saveLabels()
        rebuildDrawerMenuPublic()
        emailAdapter.notifyDataSetChanged()
        updateDetailLabelIcon()
        onSaved()
        true
    }
}

/** Optimistic local update + JMAP keyword set/remove for [ids]. */
internal fun MainActivity.applyLabelToEmails(ids: List<String>, label: EmailLabel, add: Boolean) {
    fun patched(current: List<String>): List<String> =
        if (add) (current + label.keyword).distinct() else current - label.keyword

    ids.forEach { id ->
        emails.find { it.id == id }?.let { it.labels = patched(it.labels) }
        baseEmails.find { it.id == id }?.let { it.labels = patched(it.labels) }
        // Patch every folder cache copy so the icon survives folder switches.
        folderCache.keys.toList().forEach { key ->
            folderCache[key]?.let { cached ->
                folderCache[key] = cached.map { em ->
                    if (em.id == id) em.copy(labels = patched(em.labels)) else em
                }
            }
        }
        currentDetailEmail?.let { det ->
            if (det.id == id) det.labels = patched(det.labels)
        }
    }
    emailAdapter.notifyDataSetChanged()
    updateDetailLabelIcon()
    saveEmailCache()
    val fallback = connectedAccount ?: return
    lifecycleScope.launch {
        ids.forEach { id ->
            val acc = resolveAccountForId(id) ?: fallback
            try {
                jmapClient.setKeyword(acc, id, label.keyword, add)
            } catch (e: Exception) {
                Log.e(MainActivity.TAG, "label keyword update failed", e)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Label editor (settings > Behavior): reorder, recolor, delete, create
// ---------------------------------------------------------------------------

internal fun MainActivity.showLabelEditorDialog() {
    val dp = resources.displayMetrics.density
    val bgColor = getDialogBackgroundColor()
    val textColor = if (currentTheme == "light") "#212121".toColorInt() else Color.WHITE
    val secondaryColor = if (currentTheme == "light") "#757575".toColorInt() else "#BDBDBD".toColorInt()
    val disabledColor = if (currentTheme == "light") "#CCCCCC".toColorInt() else "#555555".toColorInt()
    val accentInt = currentAccentColor.toColorInt()

    val outer = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16 * dp
            setColor(bgColor)
        }
        elevation = 8 * dp
    }
    outer.addView(TextView(this).apply {
        text = "Edit labels"
        textSize = 13f
        setTextColor(secondaryColor)
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.08f
        setPadding((20 * dp).toInt(), (16 * dp).toInt(), (20 * dp).toInt(), (8 * dp).toInt())
    })
    outer.addView(View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        setBackgroundColor(0x22FFFFFF)
    })

    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val scroll = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
    }

    fun iconBtn(res: Int, tint: Int, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
        val sz = (34 * dp).toInt()
        layoutParams = LinearLayout.LayoutParams(sz, sz)
        val p = (7 * dp).toInt()
        setPadding(p, p, p, p)
        scaleType = ImageView.ScaleType.FIT_CENTER
        isClickable = true; isFocusable = true
        background = ContextCompat.getDrawable(
            this@showLabelEditorDialog,
            android.util.TypedValue().also {
                theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true)
            }.resourceId
        )
        setOnClickListener { onClick() }
    }

    fun rebuildRows() {
        list.removeAllViews()
        if (labels.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No labels yet"
                textSize = 14f
                setTextColor(secondaryColor)
                gravity = Gravity.CENTER
                setPadding(0, (24 * dp).toInt(), 0, (12 * dp).toInt())
            })
        }
        labels.forEachIndexed { index, label ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (52 * dp).toInt()
                )
                setPadding((20 * dp).toInt(), 0, (12 * dp).toInt(), 0)
            }
            // Color dot: tap to recolor with the hue wheel.
            row.addView(View(this).apply {
                val sz = (24 * dp).toInt()
                layoutParams = LinearLayout.LayoutParams(sz, sz).also { it.marginEnd = (14 * dp).toInt() }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(label.colorHex.toColorInt())
                }
                isClickable = true; isFocusable = true
                setOnClickListener {
                    showLabelColorWheelDialog(label.name, label.colorHex) { hex ->
                        label.colorHex = hex
                        saveLabels()
                        rebuildDrawerMenuPublic()
                        emailAdapter.notifyDataSetChanged()
                        updateDetailLabelIcon()
                        rebuildRows()
                    }
                }
            })
            row.addView(TextView(this).apply {
                text = label.name
                textSize = 15f
                setTextColor(textColor)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(iconBtn(
                R.drawable.ic_lucide_chevron_down,
                if (index > 0) textColor else disabledColor
            ) {
                if (index > 0) {
                    labels.add(index - 1, labels.removeAt(index))
                    saveLabels(); rebuildDrawerMenuPublic(); rebuildRows()
                }
            }.apply { rotation = 180f })
            row.addView(iconBtn(
                R.drawable.ic_lucide_chevron_down,
                if (index < labels.size - 1) textColor else disabledColor
            ) {
                if (index < labels.size - 1) {
                    labels.add(index + 1, labels.removeAt(index))
                    saveLabels(); rebuildDrawerMenuPublic(); rebuildRows()
                }
            })
            row.addView(iconBtn(R.drawable.ic_lucide_pencil, accentInt) {
                showEditLabelDialog(label) { rebuildRows() }
            })
            row.addView(iconBtn(R.drawable.ic_lucide_trash, "#D32F2F".toColorInt()) {
                showThemedConfirmDialog(
                    title = "Delete label",
                    message = "Delete \"${label.name}\"?",
                    confirmLabel = "Delete",
                    isDangerous = true
                ) {
                    labels.remove(label)
                    saveLabels()
                    if (labelNavIds[selectedFolder] == label.keyword) {
                        selectedFolder = R.id.nav_inbox
                    }
                    rebuildDrawerMenuPublic()
                    emailAdapter.notifyDataSetChanged()
                    updateDetailLabelIcon()
                    rebuildRows()
                }
            })
            list.addView(row)
        }
        val createRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (52 * dp).toInt()
            )
            setPadding((20 * dp).toInt(), 0, (20 * dp).toInt(), 0)
            isClickable = true; isFocusable = true
            background = ContextCompat.getDrawable(
                this@showLabelEditorDialog,
                android.util.TypedValue().also {
                    theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
                }.resourceId
            )
            setOnClickListener {
                showCreateLabelDialog { rebuildRows() }
            }
        }
        createRow.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_lucide_plus)
            imageTintList = ColorStateList.valueOf(accentInt)
            val sz = (20 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz).also { it.marginEnd = (16 * dp).toInt() }
            scaleType = ImageView.ScaleType.FIT_CENTER
        })
        createRow.addView(TextView(this).apply {
            text = "Create new label"
            textSize = 15f
            setTextColor(accentInt)
            typeface = Typeface.DEFAULT_BOLD
        })
        list.addView(createRow)
    }
    rebuildRows()

    scroll.addView(list)
    outer.addView(scroll)

    val dialog = AlertDialog.Builder(this).setView(outer).create()
    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    dialog.show()
}
