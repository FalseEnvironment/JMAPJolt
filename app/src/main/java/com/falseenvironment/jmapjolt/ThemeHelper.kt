package com.falseenvironment.jmapjolt

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SweepGradient
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.toColorInt
import androidx.core.widget.CompoundButtonCompat

internal fun MainActivity.loadThemePreference() {
    val prefs = getSharedPreferences(MainActivity.PREFS_NAME, android.content.Context.MODE_PRIVATE)
    currentTheme = prefs.getString("app_theme", "gray") ?: "gray"
    val storedAccent = prefs.getString(MainActivity.KEY_ACCENT_COLOR, "#3D8BFD") ?: "#3D8BFD"
    // Migrate accents saved with the pre-refinement palette to their new tones.
    currentAccentColor = MainActivity.LEGACY_ACCENT_MAP[storedAccent.uppercase()] ?: storedAccent
    themeIdx = when (currentTheme) {
        "light"  -> 1
        "oled"   -> 2
        "violet" -> 3
        else     -> 0
    }
}

internal fun MainActivity.saveThemePreference() {
    getSharedPreferences(MainActivity.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("app_theme", currentTheme)
            .apply()
}

internal fun MainActivity.applyTheme() {
    val themeColors =
            when (currentTheme) {
                "light"  -> arrayOf("#F6F6F8", "#FFFFFF",  "#1B1B1F", "#5F5F66")
                "oled"   -> arrayOf("#000000", "#0B0B0D",  "#ECECF1", "#90909A")
                "violet" -> arrayOf("#160E24", "#1E1430",  "#ECECF1", "#9B7DC8")
                else     -> arrayOf("#212126", "#2A2A30",  "#ECECF1", "#90909A")
            }
    val bgColor = themeColors[0]
    val toolbarColor = themeColors[1]
    val textColor = themeColors[2]
    val secondaryTextColor = themeColors[3]

    val bgInt = bgColor.toColorInt()
    val toolbarInt = toolbarColor.toColorInt()
    val textInt = textColor.toColorInt()
    val secondaryTextInt = secondaryTextColor.toColorInt()

    drawerLayout.setBackgroundColor(bgInt)
    toolbar.setBackgroundColor(toolbarInt)
    toolbar.setTitleTextColor(textInt)
    drawerToggle.drawerArrowDrawable.color = textInt
    applyNavIconTint(textInt)
    customTopBar.setBackgroundColor(bgInt)
    folderLabel.setTextColor(secondaryTextInt)
    status.setBackgroundColor(toolbarInt)
    status.setTextColor(textInt)

    emptyStateView.setTextColor(secondaryTextInt)

    // Navigation drawer – force background aggressively
    navigationView.setBackgroundColor(bgInt)
    navigationView.background = android.graphics.drawable.ColorDrawable(bgInt)
    val navTextColors =
            ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(textInt, secondaryTextInt)
            )
    navigationView.itemTextColor = navTextColors
    navigationView.itemIconTintList = navTextColors
    navigationView.itemBackground = null

    val header = navigationView.getHeaderView(0)
    header.setBackgroundColor(bgInt)
    header.findViewById<TextView>(R.id.drawerAccountName)?.setTextColor(textInt)
    findViewById<TextView>(R.id.drawerVersionText)?.setTextColor(secondaryTextInt)

    // Update all main containers
    updateContainerTextColors(onboardingContainer, textInt, secondaryTextInt)
    onboardingBottomBar.setBackgroundColor(bgInt)
    updateContainerTextColors(loginContainer, textInt, secondaryTextInt)
    updateContainerTextColors(mailboxContainer, textInt, secondaryTextInt)
    updateContainerTextColors(settingsContainer, textInt, secondaryTextInt)

    settingsContainer.setBackgroundColor(bgInt)
    onboardingContainer.setBackgroundColor(bgInt)
    loginContainer.setBackgroundColor(bgInt)
    styleLoginInputs()
    mailboxContainer.setBackgroundColor(bgInt)
    composeContainer.setBackgroundColor(bgInt)
    updateContainerTextColors(composeContainer, textInt, secondaryTextInt)
    val fmtBg = when (currentTheme) {
        "light"  -> "#E8E8EC".toColorInt()
        "oled"   -> "#080808".toColorInt()
        "violet" -> "#0E0A1A".toColorInt()
        else     -> "#1C1C22".toColorInt()
    }
    formatToolbarRow.setBackgroundColor(fmtBg)

    // Recursively update text colors in settings container
    updateContainerTextColors(settingsContainer, textInt, secondaryTextInt)

    // Tint settings info row icons with primary text color (white on dark, black on light)
    val infoTint = ColorStateList.valueOf(textInt)
    settingsInfoIcon.imageTintList = infoTint
    settingsInfoArrow.imageTintList = infoTint
    val chevronTint = ColorStateList.valueOf(secondaryTextInt)
    settingsGeneralChevron.imageTintList = chevronTint
    settingsThemeChevron.imageTintList = chevronTint
    settingsUnifiedPushChevron.imageTintList = chevronTint

    // Update detail view header if visible
    if (isShowingEmailDetail) {
        emailDetailContainer.getChildAt(0).setBackgroundColor(toolbarInt)
        detailFrom.setTextColor(textInt)
        detailSubject.setTextColor(textInt)
        detailDate.setTextColor(secondaryTextInt)
        detailToText.setTextColor(secondaryTextInt)
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        window.statusBarColor = toolbarInt
        if (currentTheme == "light" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        } else {
            window.decorView.systemUiVisibility = 0
        }
    }

    applyAccentColor()
    emailAdapter.notifyDataSetChanged()
}

internal fun MainActivity.getOnAccentColor(): Int {
    val a = currentAccentColor.toColorInt()
    val lum = Color.red(a) * 0.299 + Color.green(a) * 0.587 + Color.blue(a) * 0.114
    return if (lum > 140) Color.BLACK else Color.WHITE
}

internal fun MainActivity.styleLoginInputs() {
    val d = resources.displayMetrics.density
    val padH = (16 * d).toInt()
    val padV = (10 * d).toInt()
    val hintColor = "#9E9E9E".toColorInt()
    listOf(emailInput, passwordInput, serverUrlInput).forEach {
        it.backgroundTintList = null
        it.setBackgroundResource(R.drawable.input_field_bg)
        it.setPadding(padH, padV, padH, padV)
        it.setHintTextColor(hintColor)
    }
}

/** Pill-shaped accent button for primary actions (MD3 filled button shape). */
internal fun MainActivity.styleAccentButton(button: Button) {
    val d = resources.displayMetrics.density
    button.background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 999 * d
        setColor(currentAccentColor.toColorInt())
    }
    button.setTextColor(getOnAccentColor())
    button.isAllCaps = false
    button.stateListAnimator = null
    button.alpha = if (button.isEnabled) 1f else 0.4f
    button.setPadding(
        (24 * d).toInt(), (14 * d).toInt(),
        (24 * d).toInt(), (14 * d).toInt()
    )
    button.letterSpacing = 0.01f
}

internal fun MainActivity.applyAccentColor() {
    val accentInt = currentAccentColor.toColorInt()
    val onAccent = getOnAccentColor()
    fabCompose.backgroundTintList = ColorStateList.valueOf(accentInt)
    toolbar.setBackgroundColor(accentInt)
    toolbar.setTitleTextColor(onAccent)
    drawerToggle.drawerArrowDrawable.color = onAccent
    applyNavIconTint(onAccent)
    topBarAccentArea.setBackgroundColor(accentInt)
    val hintAlpha = if (currentTheme == "light") 0.55f else 0.65f
    searchBarTitle.setTextColor(android.graphics.Color.argb(
        (255 * hintAlpha).toInt(),
        android.graphics.Color.red(onAccent),
        android.graphics.Color.green(onAccent),
        android.graphics.Color.blue(onAccent)
    ))
    val d = resources.displayMetrics.density
    searchBarContainer.background = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        cornerRadius = 12 * d
        setColor(darkenColor(accentInt, 0.78f))
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        window.statusBarColor = accentInt
    }
    // Switch tint: accent when ON, gray when OFF
    val offColor = if (currentTheme == "light") "#C7C7CC".toColorInt() else "#48484A".toColorInt()
    val switchTint = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(accentInt, offColor)
    )
    listOf(loadImagesSwitch, loadFaviconsSwitch, unifiedPushSwitch, sseSwitch).forEach {
        CompoundButtonCompat.setButtonTintList(it, switchTint)
    }
    // Primary accent buttons (settings test, login, onboarding)
    styleAccentButton(loginButton)
    onboardingNextFab.backgroundTintList = ColorStateList.valueOf(accentInt)
    updateAccentColorPreview()
    // Selection bar icons and count text
    val selIconTint = ColorStateList.valueOf(onAccent)
    listOf(selectionCloseBtn, selectionArchiveBtn, selectionDeleteBtn, selectionReadBtn, selectionMoreBtn).forEach {
        it.imageTintList = selIconTint
    }
    selectionCountText.setTextColor(onAccent)
    // Top-bar send button (shown during compose) — always white on the accent bar
    topBarSendButton.imageTintList = ColorStateList.valueOf(Color.WHITE)
    // Settings dropdown backgrounds
    // MD3-style tonal pill for the dropdown triggers.
    val dropdownBg = darkenColor(accentInt, 0.78f)
    val dropdownStroke = darkenColor(accentInt, 1.15f)
    for (dropdown in listOf(swipeLeftDropdown, swipeRightDropdown, themeDropdown)) {
        dropdown.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999 * d
            setColor(dropdownBg)
            setStroke(d.toInt(), dropdownStroke)
        }
        for (i in 0 until dropdown.childCount) {
            val child = dropdown.getChildAt(i)
            if (child is ImageView) child.imageTintList = ColorStateList.valueOf(Color.WHITE)
            if (child is TextView) child.setTextColor(Color.WHITE)
        }
    }
    updateSettingsDropdownDisplays()
}

internal fun MainActivity.saveAccentColorPreference() {
    getSharedPreferences(MainActivity.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        .edit().putString(MainActivity.KEY_ACCENT_COLOR, currentAccentColor).apply()
}

internal fun MainActivity.updateAccentColorPreview() {
    val dp = resources.displayMetrics.density
    val bg = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        cornerRadius = 5 * dp
        setColor(currentAccentColor.toColorInt())
    }
    accentColorPreview.background = bg
}

internal fun MainActivity.showAccentColorDialog() {
    val dp = resources.displayMetrics.density

    // Parse current accent into HSV; ensure vibrant defaults
    val pendingHsv = FloatArray(3).also { hsv ->
        Color.colorToHSV(
            runCatching { currentAccentColor.toColorInt() }.getOrDefault("#3D8BFD".toColorInt()),
            hsv
        )
        if (hsv[1] < 0.4f) hsv[1] = 0.85f
        if (hsv[2] < 0.4f) hsv[2] = 0.95f
    }
    fun pendingColorHex() = "#%06X".format(0xFFFFFF and Color.HSVToColor(pendingHsv))

    // Mutable callback so wheelView can trigger swatch refresh before swatchViews is populated
    var onSwatchRefresh: ((String) -> Unit) = {}

    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = android.view.Gravity.CENTER_HORIZONTAL
        val p = (16 * dp).toInt()
        setPadding(p, (14 * dp).toInt(), p, (12 * dp).toInt())
    }

    // --- Circular hue wheel ---
    val wheelDiameter = (224 * dp).toInt()
    val wheelView = object : View(this) {
        private val ringPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val fillPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val dotFill     = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val dotBorder   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = Color.WHITE
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            val outerR = minOf(cx, cy) - 6 * dp
            val innerR = outerR * 0.60f
            val ringR  = (outerR + innerR) / 2f
            val ringW  = outerR - innerR

            // Rotate so hue 0 (red) sits at 12-o-clock
            canvas.save()
            canvas.rotate(-90f, cx, cy)
            val hueColors = IntArray(361) { i -> Color.HSVToColor(floatArrayOf(i.toFloat() % 360f, 1f, 1f)) }
            ringPaint.shader = SweepGradient(cx, cy, hueColors, null)
            ringPaint.strokeWidth = ringW
            canvas.drawCircle(cx, cy, ringR, ringPaint)
            canvas.restore()

            // Center circle — current pending color
            fillPaint.color = Color.HSVToColor(pendingHsv)
            canvas.drawCircle(cx, cy, innerR - 4 * dp, fillPaint)

            // Indicator dot on ring at selected hue
            val rad = Math.toRadians((pendingHsv[0] - 90.0))
            val ix  = cx + ringR * kotlin.math.cos(rad).toFloat()
            val iy  = cy + ringR * kotlin.math.sin(rad).toFloat()
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
                val dx = ev.x - cx;  val dy = ev.y - cy
                var hue = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f
                if (hue < 0f) hue += 360f
                if (hue >= 360f) hue -= 360f
                pendingHsv[0] = hue
                invalidate()
                onSwatchRefresh(pendingColorHex())
                return true
            }
            return super.onTouchEvent(ev)
        }
    }
    wheelView.layoutParams = LinearLayout.LayoutParams(wheelDiameter, wheelDiameter).also {
        it.bottomMargin = (20 * dp).toInt()
    }

    // --- Preset swatches ---
    val swatchRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER
    }
    val swatchViews = mutableListOf<View>()

    onSwatchRefresh = { selected ->
        swatchViews.forEach { v ->
            val col = v.tag as String
            val sel = col.equals(selected, ignoreCase = true)
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(col.toColorInt())
                if (sel) setStroke((3 * dp).toInt(), Color.WHITE)
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
                wheelView.invalidate()
                onSwatchRefresh(color)
            }
        }
        swatchViews.add(swatch)
        swatchRow.addView(swatch)
    }
    onSwatchRefresh(pendingColorHex())

    root.addView(wheelView)
    root.addView(swatchRow)

    AlertDialog.Builder(this)
        .setTitle("Accent color")
        .setView(root)
        .setPositiveButton("Apply") { _, _ ->
            currentAccentColor = pendingColorHex()
            saveAccentColorPreference()
            applyAccentColor()
            emailAdapter.notifyDataSetChanged()
        }
        .setNegativeButton("Cancel", null)
        .show()
}

internal fun MainActivity.showAccountColorDialog(email: String, onColorSet: () -> Unit) {
    val dp = resources.displayMetrics.density
    var pendingColor = getAccountColor(email)

    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val p = (16 * dp).toInt()
        setPadding(p, (14 * dp).toInt(), p, (8 * dp).toInt())
    }

    val previewBar = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (44 * dp).toInt()
        ).also { it.bottomMargin = (16 * dp).toInt() }
    }
    fun refreshPreview(color: Int) {
        previewBar.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 8 * dp
            setColor(color)
        }
    }
    refreshPreview(pendingColor)

    val swatchRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER
    }
    val swatchViews = mutableListOf<View>()

    fun refreshSwatches(selected: Int) {
        swatchViews.forEach { v ->
            val col = v.tag as Int
            v.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(col)
                if (col == selected) setStroke((2.5 * dp).toInt(), Color.WHITE)
            }
        }
    }

    MainActivity.ACCENT_COLORS.forEach { colorHex ->
        val colorInt = colorHex.toColorInt()
        val sz = (32 * dp).toInt()
        val swatch = View(this).apply {
            tag = colorInt
            layoutParams = LinearLayout.LayoutParams(sz, sz).also {
                it.setMargins((6 * dp).toInt(), 0, (6 * dp).toInt(), 0)
            }
            setOnClickListener {
                pendingColor = colorInt
                refreshPreview(colorInt)
                refreshSwatches(colorInt)
            }
        }
        swatchViews.add(swatch)
        swatchRow.addView(swatch)
    }
    refreshSwatches(pendingColor)

    root.addView(previewBar)
    root.addView(swatchRow)

    AlertDialog.Builder(this)
        .setTitle(email.substringBefore("@"))
        .setView(root)
        .setPositiveButton("Apply") { _, _ ->
            val prefs = getSharedPreferences(MainActivity.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            prefs.edit().putInt("account_color_$email", pendingColor).apply()
            emailAdapter.notifyDataSetChanged()
            renderAccountHeader()
            onColorSet()
        }
        .setNegativeButton("Cancel", null)
        .show()
}

/** Re-tints the toolbar navigation icon (hamburger or back-arrow) to [color]. */
internal fun MainActivity.applyNavIconTint(color: Int) {
    toolbar.navigationIcon?.let { icon ->
        androidx.core.graphics.drawable.DrawableCompat.setTint(
                androidx.core.graphics.drawable.DrawableCompat.wrap(icon).mutate(),
                color
        )
    }
    searchBarMenuIcon.imageTintList = ColorStateList.valueOf(color)
}

internal fun MainActivity.handleNavigationClick() {
    when {
        composeContainer.visibility == View.VISIBLE -> attemptLeaveCompose()
        settingsContainer.visibility == View.VISIBLE -> {
            if (currentSettingsSection != MainActivity.SettingsSection.ROOT) attemptLeaveSettingsSubmenu()
            else showMailboxScreen()
        }
        isShowingEmailDetail -> closeEmailDetail()
    }
}

internal fun MainActivity.setDrawerIndicator(enabled: Boolean) {
    drawerToggle.isDrawerIndicatorEnabled = enabled
    searchBarMenuIcon.setImageResource(
        if (enabled) R.drawable.ic_menu_24dp else R.drawable.ic_arrow_back_24dp
    )
    searchBarMenuIcon.imageTintList = ColorStateList.valueOf(getOnAccentColor())
}

internal fun MainActivity.updateCustomTopBar(title: String, inMailbox: Boolean = false) {
    folderLabel.text = title
    folderLabel.visibility = if (title.isNotBlank()) View.VISIBLE else View.GONE
}

internal fun MainActivity.updateContainerTextColors(view: View, primaryColor: Int, secondaryColor: Int) {
    when (view) {
        is EditText -> {
            view.setTextColor(primaryColor)
            view.setHintTextColor(secondaryColor)
            view.backgroundTintList = ColorStateList.valueOf(secondaryColor)
        }
        is CompoundButton -> {
            view.setTextColor(primaryColor)
            CompoundButtonCompat.setButtonTintList(view, ColorStateList.valueOf(primaryColor))
        }
        is Button -> {
            view.setTextColor(primaryColor)
        }
        is TextView -> {
            view.setTextColor(primaryColor)
        }
        is ViewGroup -> {
            for (i in 0 until view.childCount) {
                updateContainerTextColors(view.getChildAt(i), primaryColor, secondaryColor)
            }
        }
    }
}

internal fun MainActivity.tintActionModeBar() {
    val accent = currentAccentColor.toColorInt()
    val onAccent = getOnAccentColor()
    window.decorView.post {
        fun android.view.View.tintImages() {
            if (this is ImageView) imageTintList = ColorStateList.valueOf(onAccent)
            if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).tintImages()
        }
        fun android.view.View.applyAccent(): Boolean {
            if (javaClass.name.let { it.contains("ActionBarContextView") || it.contains("ActionModeBar") }) {
                setBackgroundColor(accent)
                if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).tintImages()
                return true
            }
            if (this is ViewGroup) for (i in 0 until childCount) { if (getChildAt(i).applyAccent()) return true }
            return false
        }
        window.decorView.applyAccent()
    }
}

internal fun MainActivity.darkenColor(color: Int, factor: Float = 0.72f): Int = Color.rgb(
    (Color.red(color) * factor).toInt().coerceIn(0, 255),
    (Color.green(color) * factor).toInt().coerceIn(0, 255),
    (Color.blue(color) * factor).toInt().coerceIn(0, 255)
)

internal fun MainActivity.getDialogBackgroundColor(): Int = when (currentTheme) {
    "light"  -> "#F0EEEE".toColorInt()
    "oled"   -> "#0A0A0A".toColorInt()
    "violet" -> "#140B22".toColorInt()
    else     -> "#242429".toColorInt()
}

// ---------------------------------------------------------------------------
// Screen transition helpers
// ---------------------------------------------------------------------------

/**
 * Animate a container into view (forward navigation: enter from right).
 * Call AFTER setting visibility = VISIBLE.
 */
internal fun android.view.View.animateScreenIn() {
    alpha = 0f
    translationX = width * 0.06f
    animate()
        .alpha(1f)
        .translationX(0f)
        .setDuration(300)
        .setInterpolator(android.view.animation.DecelerateInterpolator(2f))
        .start()
}

/**
 * Animate a container out of view (forward navigation: exit to left), then hide it.
 */
internal fun android.view.View.animateScreenOut(onEnd: (() -> Unit)? = null) {
    animate()
        .alpha(0f)
        .setDuration(160)
        .setInterpolator(android.view.animation.AccelerateInterpolator(1.5f))
        .withEndAction {
            visibility = android.view.View.GONE
            alpha = 1f
            onEnd?.invoke()
        }
        .start()
}

/**
 * Animate a container back into view (back navigation: fade in).
 * Call AFTER setting visibility = VISIBLE.
 */
internal fun android.view.View.animateScreenInBack() {
    alpha = 0f
    translationX = 0f
    animate()
        .alpha(1f)
        .setDuration(250)
        .setInterpolator(android.view.animation.DecelerateInterpolator(2f))
        .start()
}

/**
 * Animate the detail container out (back nav: slide right), then hide it.
 */
internal fun android.view.View.animateScreenOutBack(onEnd: (() -> Unit)? = null) {
    animate()
        .alpha(0f)
        .translationX(width * 0.06f)
        .setDuration(200)
        .setInterpolator(android.view.animation.AccelerateInterpolator(1.5f))
        .withEndAction {
            visibility = android.view.View.GONE
            alpha = 1f
            translationX = 0f
            onEnd?.invoke()
        }
        .start()
}

/** Scale-pulse feedback for icon buttons (star, etc.). */
internal fun android.view.View.animateTap() {
    animate().scaleX(0.75f).scaleY(0.75f).setDuration(80)
        .withEndAction {
            animate().scaleX(1f).scaleY(1f)
                .setDuration(160)
                .setInterpolator(android.view.animation.OvershootInterpolator(2f))
                .start()
        }.start()
}

/** Show FAB with scale-in animation. */
internal fun android.view.View.animateFabIn() {
    if (visibility == android.view.View.VISIBLE) return
    scaleX = 0f; scaleY = 0f; alpha = 0f
    visibility = android.view.View.VISIBLE
    animate().scaleX(1f).scaleY(1f).alpha(1f)
        .setStartDelay(120)
        .setDuration(250)
        .setInterpolator(android.view.animation.OvershootInterpolator(1.5f))
        .start()
}

/** Hide FAB with scale-out animation. */
internal fun android.view.View.animateFabOut() {
    if (visibility != android.view.View.VISIBLE) return
    animate().scaleX(0f).scaleY(0f).alpha(0f)
        .setDuration(150)
        .setInterpolator(android.view.animation.AccelerateInterpolator(1.5f))
        .withEndAction { visibility = android.view.View.GONE; scaleX = 1f; scaleY = 1f; alpha = 1f }
        .start()
}
