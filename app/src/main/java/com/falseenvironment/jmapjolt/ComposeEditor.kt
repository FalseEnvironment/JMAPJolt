package com.falseenvironment.jmapjolt

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.text.Editable
import android.text.Html
import android.text.Layout
import android.text.Spannable
import android.text.TextWatcher
import android.util.Log
import android.text.style.AlignmentSpan
import android.text.style.BulletSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.chip.Chip
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The compose body editor: formatting toolbar, character and paragraph spans,
 * headings and lists. Sending, drafts and recipient handling stay in ComposeHelper.
 */

internal fun MainActivity.buildFormatToolbar() {
    formatToolbar.removeAllViews()
    formatButtons.clear()
    val dp = resources.displayMetrics.density
    val iconTint = when (currentTheme) {
        "light" -> "#555555".toColorInt()
        "oled"  -> Color.WHITE
        else    -> "#808080".toColorInt()
    }
    val dividerColor = when (currentTheme) {
        "light" -> "#C8C8C8".toColorInt()
        "oled"  -> "#333333".toColorInt()
        else    -> "#3A3A3A".toColorInt()
    }

    fun imgBtn(key: String, drawableRes: Int) {
        val v = ImageView(this).apply {
            setImageResource(drawableRes)
            imageTintList = ColorStateList.valueOf(iconTint)
            val size = (40 * dp).toInt()
            val pad  = (8 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(size, LinearLayout.LayoutParams.MATCH_PARENT)
            setPadding(pad, pad, pad, pad)
            scaleType = ImageView.ScaleType.FIT_CENTER
            isClickable = true; isFocusable = true
            setOnClickListener { toggleFormat(key) }
        }
        formatButtons[key] = v; formatToolbar.addView(v)
    }

    fun txtBtn(key: String, label: String) {
        val v = TextView(this).apply {
            text = label; textSize = 11f
            setTextColor(iconTint)
            setTypeface(null, Typeface.BOLD)
            val hPad = (10 * dp).toInt()
            setPadding(hPad, 0, hPad, 0)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT)
            isClickable = true; isFocusable = true
            setOnClickListener { toggleFormat(key) }
        }
        formatButtons[key] = v; formatToolbar.addView(v)
    }

    fun divider() {
        val v = View(this).apply {
            val vPad = (8 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams((1 * dp).toInt(), LinearLayout.LayoutParams.MATCH_PARENT)
                .also { it.setMargins(4, vPad, 4, vPad) }
            setBackgroundColor(dividerColor)
        }
        formatToolbar.addView(v)
    }

    imgBtn("bold",    R.drawable.ic_format_bold_24dp)
    imgBtn("italic",  R.drawable.ic_format_italic_24dp)
    imgBtn("under",   R.drawable.ic_format_underlined_24dp)
    imgBtn("strike",  R.drawable.ic_strikethrough_s_24dp)
    divider()
    txtBtn("h1", "H1")
    txtBtn("h2", "H2")
    divider()
    imgBtn("bullet",  R.drawable.ic_format_list_bulleted_24dp)
    imgBtn("number",  R.drawable.ic_format_list_numbered_24dp)
    divider()
    imgBtn("link",    R.drawable.ic_link_24dp)
    divider()
    imgBtn("align_l", R.drawable.ic_format_align_left_24dp)
    imgBtn("align_c", R.drawable.ic_format_align_center_24dp)
    imgBtn("align_r", R.drawable.ic_format_align_right_24dp)
}

internal fun MainActivity.updateFormatButtonStates() {
    val activeTint = ColorStateList.valueOf(currentAccentColor.toColorInt())
    val inactiveColor = when (currentTheme) {
        "light" -> "#555555".toColorInt()
        "oled"  -> Color.WHITE
        else    -> "#808080".toColorInt()
    }
    val inactiveTint = ColorStateList.valueOf(inactiveColor)
    for ((key, view) in formatButtons) {
        val on = when (key) {
            "bullet" -> composeListMode == 1
            "number" -> composeListMode == 2
            else -> key in activeFormats
        }
        when (view) {
            is ImageView -> view.imageTintList = if (on) activeTint else inactiveTint
            is TextView  -> view.setTextColor(if (on) currentAccentColor.toColorInt() else inactiveColor)
        }
    }
}

internal fun MainActivity.toggleFormat(key: String) {
    when (key) {
        "bold", "italic", "under", "strike" -> {
            val sel = getBodySelection()
            if (sel != null) applyCharFormat(key, sel.first, sel.second)
            else {
                if (key in activeFormats) activeFormats.remove(key) else activeFormats.add(key)
                updateFormatButtonStates()
            }
        }
        "h1"      -> toggleHeading(1)
        "h2"      -> toggleHeading(2)
        "bullet"  -> toggleListMode(1)
        "number"  -> toggleListMode(2)
        "link"    -> showLinkDialog()
        "align_l" -> setComposeAlignment(Layout.Alignment.ALIGN_NORMAL)
        "align_c" -> setComposeAlignment(Layout.Alignment.ALIGN_CENTER)
        "align_r" -> setComposeAlignment(Layout.Alignment.ALIGN_OPPOSITE)
    }
}

internal fun MainActivity.applyCharFormat(key: String, s: Int, e: Int) {
    val text = composeBodyInput.text
    when (key) {
        "bold" -> {
            val ex = text.getSpans(s, e, StyleSpan::class.java).filter { it.style == Typeface.BOLD }
            if (ex.isNotEmpty()) ex.forEach { text.removeSpan(it) } else text.setSpan(StyleSpan(Typeface.BOLD), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        "italic" -> {
            val ex = text.getSpans(s, e, StyleSpan::class.java).filter { it.style == Typeface.ITALIC }
            if (ex.isNotEmpty()) ex.forEach { text.removeSpan(it) } else text.setSpan(StyleSpan(Typeface.ITALIC), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        "under" -> {
            val ex = text.getSpans(s, e, UnderlineSpan::class.java)
            if (ex.isNotEmpty()) ex.forEach { text.removeSpan(it) } else text.setSpan(UnderlineSpan(), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        "strike" -> {
            val ex = text.getSpans(s, e, StrikethroughSpan::class.java)
            if (ex.isNotEmpty()) ex.forEach { text.removeSpan(it) } else text.setSpan(StrikethroughSpan(), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}

internal fun MainActivity.getBodySelection(): Pair<Int, Int>? {
    val s = composeBodyInput.selectionStart; val e = composeBodyInput.selectionEnd
    return if (s < e) Pair(s, e) else null
}

internal fun MainActivity.getLineRange(pos: Int): Pair<Int, Int> {
    val str = composeBodyInput.text.toString()
    val start = str.lastIndexOf('\n', pos - 1).let { if (it < 0) 0 else it + 1 }
    val end   = str.indexOf('\n', pos).let { if (it < 0) str.length else it }
    return Pair(start, end)
}

/** On/off heading toggle (type-ahead, like bold) — applies to a selection if one exists. */
internal fun MainActivity.toggleHeading(level: Int) {
    val key = "h$level"
    val other = if (level == 1) "h2" else "h1"
    val sizeMult = if (level == 1) 1.8f else 1.4f
    val otherMult = if (level == 1) 1.4f else 1.8f
    val text = composeBodyInput.text
    val (s, e) = getBodySelection() ?: Pair(-1, -1)
    if (key in activeFormats) {
        activeFormats.remove(key)
        if (e > s && s >= 0) {
            text.getSpans(s, e, RelativeSizeSpan::class.java).filter { it.sizeChange == sizeMult }.forEach { text.removeSpan(it) }
            text.getSpans(s, e, StyleSpan::class.java).filter { it.style == Typeface.BOLD }.forEach { text.removeSpan(it) }
        }
    } else {
        activeFormats.add(key)
        activeFormats.remove(other)
        if (e > s && s >= 0) {
            text.getSpans(s, e, RelativeSizeSpan::class.java).filter { it.sizeChange == otherMult }.forEach { text.removeSpan(it) }
            text.setSpan(RelativeSizeSpan(sizeMult), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(StyleSpan(Typeface.BOLD), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
    updateFormatButtonStates()
}

internal const val BULLET_PREFIX = "•  "
internal val NUMBER_MARKER_REGEX = Regex("^(\\d+)\\.\\s+")

/** Toggles a text-prefix list (1 = bullet, 2 = numbered) that continues on Enter. */
internal fun MainActivity.toggleListMode(mode: Int) {
    if (composeListMode == mode) {
        composeListMode = 0
        removeListMarkerFromCurrentLine()
        updateFormatButtonStates()
        return
    }
    composeListMode = mode
    composeListNextNumber = 1
    val text = composeBodyInput.text
    val pos = composeBodyInput.selectionStart.coerceAtLeast(0)
    val lineStart = text.toString().lastIndexOf('\n', pos - 1) + 1
    val lineEndNl = text.toString().indexOf('\n', lineStart)
    val lineEnd = if (lineEndNl < 0) text.length else lineEndNl
    val line = text.substring(lineStart, lineEnd)
    composeSelfEdit = true
    try {
        // Strip any existing marker on the line before applying the new one.
        when {
            line.startsWith(BULLET_PREFIX) -> text.delete(lineStart, lineStart + BULLET_PREFIX.length)
            NUMBER_MARKER_REGEX.find(line) != null ->
                text.delete(lineStart, lineStart + NUMBER_MARKER_REGEX.find(line)!!.value.length)
        }
        val marker = if (mode == 1) BULLET_PREFIX else "1.  "
        text.insert(lineStart, marker)
        if (mode == 2) composeListNextNumber = 2
        composeBodyInput.setSelection((lineStart + marker.length).coerceAtMost(text.length))
    } finally {
        composeSelfEdit = false
    }
    updateFormatButtonStates()
}

/** Current paragraph bounds, with a paragraph-safe end (text end or just past a '\n'). */
private fun MainActivity.currentParagraphBounds(): Pair<Int, Int> {
    val text = composeBodyInput.text
    val len = text.length
    val selS = composeBodyInput.selectionStart.coerceIn(0, len)
    val selE = composeBodyInput.selectionEnd.coerceIn(selS, len)
    val s = text.toString().lastIndexOf('\n', selS - 1) + 1
    val eNl = text.toString().indexOf('\n', selE)
    val e = if (eNl < 0) len else eNl
    val pe = if (e < len && text[e] == '\n') e + 1 else e
    return Pair(s, pe)
}

/** Ensures the current line is a real paragraph so a block span renders immediately (even if empty). */
private fun MainActivity.ensureRenderableParagraph() {
    val text = composeBodyInput.text
    val pos = composeBodyInput.selectionStart.coerceAtLeast(0)
    val lineStart = text.toString().lastIndexOf('\n', pos - 1) + 1
    if (lineStart >= text.length) {
        composeSelfEdit = true
        try {
            text.insert(lineStart, "\n")
            composeBodyInput.setSelection(lineStart)
        } finally { composeSelfEdit = false }
    }
}

private fun MainActivity.removeListMarkerFromCurrentLine() {
    val text = composeBodyInput.text
    val pos = composeBodyInput.selectionStart.coerceAtLeast(0)
    val lineStart = text.toString().lastIndexOf('\n', pos - 1) + 1
    val lineEndNl = text.toString().indexOf('\n', lineStart)
    val lineEnd = if (lineEndNl < 0) text.length else lineEndNl
    val line = text.substring(lineStart, lineEnd)
    composeSelfEdit = true
    try {
        when {
            line.startsWith(BULLET_PREFIX) -> text.delete(lineStart, lineStart + BULLET_PREFIX.length)
            NUMBER_MARKER_REGEX.find(line) != null ->
                text.delete(lineStart, lineStart + NUMBER_MARKER_REGEX.find(line)!!.value.length)
        }
    } finally { composeSelfEdit = false }
}

/** Re-applies the active paragraph alignment to the current paragraph. */
internal fun MainActivity.applyActiveParagraphFormats() {
    val text = composeBodyInput.text
    val (s, pe) = currentParagraphBounds()
    if (s < 0 || pe <= s) return

    val align = when {
        "align_c" in activeFormats -> Layout.Alignment.ALIGN_CENTER
        "align_r" in activeFormats -> Layout.Alignment.ALIGN_OPPOSITE
        "align_l" in activeFormats -> Layout.Alignment.ALIGN_NORMAL
        else -> null
    }
    text.getSpans(s, pe, AlignmentSpan::class.java).forEach { text.removeSpan(it) }
    if (align != null) {
        text.setSpan(AlignmentSpan.Standard(align), s, pe, Spannable.SPAN_PARAGRAPH)
    }
}

/** Centered rounded dialog (app style) asking for display text + URL, then inserts an anchor. */
internal fun MainActivity.showLinkDialog() {
    val dp = resources.displayMetrics.density
    val dialogBg = getDialogBackgroundColor()
    val textColor = if (currentTheme == "light") "#212121".toColorInt() else Color.WHITE
    val secondaryColor = if (currentTheme == "light") "#757575".toColorInt() else "#9E9E9E".toColorInt()
    val accentColor = currentAccentColor.toColorInt()

    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val p = (22 * dp).toInt()
        setPadding(p, p, p, (14 * dp).toInt())
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 20 * dp
            setColor(dialogBg)
        }
    }
    root.addView(TextView(this).apply {
        text = "Insert link"
        textSize = 18f
        setTypeface(null, Typeface.BOLD)
        setTextColor(textColor)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = (16 * dp).toInt() }
    })

    fun field(hint: String): EditText = EditText(this).apply {
        this.hint = hint
        setText("")
        setTextColor(textColor)
        setHintTextColor(secondaryColor)
        backgroundTintList = ColorStateList.valueOf(secondaryColor)
        textSize = 15f
        maxLines = 1
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = (12 * dp).toInt() }
    }
    val nameInput = field("Text to display")
    val urlInput = field("https://example.com").apply { inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI }
    // Pre-fill the display text with the current selection.
    val sel = getBodySelection()
    if (sel != null) nameInput.setText(composeBodyInput.text.substring(sel.first, sel.second))
    root.addView(nameInput)
    root.addView(urlInput)

    val btnRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }
    root.addView(btnRow)

    val dialog = AlertDialog.Builder(this).setView(root).create()

    fun makeButton(label: String, color: Int, bold: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
            setPadding((14 * dp).toInt(), (10 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
        }

    btnRow.addView(makeButton("Cancel", secondaryColor, false) { dialog.dismiss() })
    btnRow.addView(makeButton("Insert", accentColor, true) {
        val rawUrl = urlInput.text.toString().trim()
        if (rawUrl.isBlank()) { dialog.dismiss(); return@makeButton }
        val finalUrl = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(rawUrl)) rawUrl else "https://$rawUrl"
        val display = nameInput.text.toString().trim().ifBlank { rawUrl }
        val span = android.text.SpannableString(display).apply {
            setSpan(android.text.style.URLSpan(finalUrl), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val body = composeBodyInput.text
        val s = sel?.first ?: composeBodyInput.selectionStart.coerceAtLeast(0)
        val e = sel?.second ?: s
        if (e > s) body.replace(s, e, span) else body.insert(s, span)
        composeBodyInput.setSelection(s + span.length)
        dialog.dismiss()
    })

    dialog.show()
    dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
    dialog.window?.attributes?.let { lp ->
        lp.width = (resources.displayMetrics.widthPixels * 0.88f).toInt()
        dialog.window?.attributes = lp
    }
}

/** On/off alignment toggle (radio: left / center / right). */
internal fun MainActivity.setComposeAlignment(alignment: Layout.Alignment) {
    val key = when (alignment) {
        Layout.Alignment.ALIGN_CENTER -> "align_c"
        Layout.Alignment.ALIGN_OPPOSITE -> "align_r"
        else -> "align_l"
    }
    if (key in activeFormats) activeFormats.remove(key)
    else {
        activeFormats.removeAll(listOf("align_l", "align_c", "align_r"))
        activeFormats.add(key)
    }
    applyActiveParagraphFormats()
    updateFormatButtonStates()
}

internal class FormatTextWatcher(private val activity: MainActivity) : TextWatcher {
    private var insertStart = -1
    private var insertCount = 0
    private var newlineInserted = false
    override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
        insertStart = if (before == 0 && count > 0) start else -1
        insertCount = count
        newlineInserted = before == 0 && count == 1 && start < s.length && s[start] == '\n'
    }
    override fun afterTextChanged(s: Editable) {
        if (activity.composeSelfEdit) return
        // List continuation: pressing Enter inside an active list emits the next marker.
        if (newlineInserted && activity.composeListMode != 0) {
            newlineInserted = false
            handleListNewline(s, insertStart)
            return
        }
        if (insertStart < 0 || activity.activeFormats.isEmpty()) return
        val iS = insertStart; val iE = insertStart + insertCount; insertStart = -1
        val af = activity.activeFormats
        if ("bold"   in af) s.setSpan(StyleSpan(Typeface.BOLD),   iS, iE, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if ("italic" in af) s.setSpan(StyleSpan(Typeface.ITALIC), iS, iE, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if ("under"  in af) s.setSpan(UnderlineSpan(),            iS, iE, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if ("strike" in af) s.setSpan(StrikethroughSpan(),        iS, iE, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if ("h1" in af) {
            s.setSpan(RelativeSizeSpan(1.8f), iS, iE, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            s.setSpan(StyleSpan(Typeface.BOLD), iS, iE, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else if ("h2" in af) {
            s.setSpan(RelativeSizeSpan(1.4f), iS, iE, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            s.setSpan(StyleSpan(Typeface.BOLD), iS, iE, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        // Re-apply paragraph alignment now that the line has content.
        if ("align_l" in af || "align_c" in af || "align_r" in af) {
            activity.composeSelfEdit = true
            try { activity.applyActiveParagraphFormats() } finally { activity.composeSelfEdit = false }
        }
    }

    private fun handleListNewline(s: Editable, newlinePos: Int) {
        val prevLineStart = s.toString().lastIndexOf('\n', newlinePos - 1) + 1
        val prevLine = s.substring(prevLineStart, newlinePos)
        val bullet = activity.composeListMode == 1
        val markerMatch = if (bullet) prevLine.startsWith(BULLET_PREFIX)
                          else NUMBER_MARKER_REGEX.containsMatchIn(prevLine)
        val content = when {
            bullet && markerMatch -> prevLine.removePrefix(BULLET_PREFIX)
            !bullet && markerMatch -> prevLine.substring(NUMBER_MARKER_REGEX.find(prevLine)!!.value.length)
            else -> prevLine
        }
        activity.composeSelfEdit = true
        try {
            if (markerMatch && content.isBlank()) {
                // Enter on an empty item ends the list and removes the dangling marker.
                s.delete(prevLineStart, newlinePos)
                activity.composeListMode = 0
                activity.composeBodyInput.setSelection((prevLineStart + 1).coerceAtMost(s.length))
                activity.updateFormatButtonStates()
            } else {
                val marker = if (bullet) BULLET_PREFIX else "${activity.composeListNextNumber}.  "
                s.insert(newlinePos + 1, marker)
                if (!bullet) activity.composeListNextNumber++
                activity.composeBodyInput.setSelection((newlinePos + 1 + marker.length).coerceAtMost(s.length))
            }
        } finally {
            activity.composeSelfEdit = false
        }
    }
}
