package com.falseenvironment.jmapjolt

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
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
    currentAccentColor = prefs.getString(MainActivity.KEY_ACCENT_COLOR, "#1976D2") ?: "#1976D2"
    themeIdx = when (currentTheme) {
        "light" -> 1
        "oled" -> 2
        else -> 0
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
                "light" -> arrayOf("#FFFFFF", "#F5F5F5", "#212121", "#757575")
                "oled" -> arrayOf("#000000", "#000000", "#FFFFFF", "#BDBDBD")
                else -> arrayOf("#1F1F1F", "#2A2A2A", "#FFFFFF", "#BDBDBD")
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
    updateContainerTextColors(loginContainer, textInt, secondaryTextInt)
    updateContainerTextColors(mailboxContainer, textInt, secondaryTextInt)
    updateContainerTextColors(settingsContainer, textInt, secondaryTextInt)

    settingsContainer.setBackgroundColor(bgInt)
    onboardingContainer.setBackgroundColor(bgInt)
    loginContainer.setBackgroundColor(bgInt)
    styleLoginInputs()
    mailboxContainer.setBackgroundColor(bgInt)

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
        detailFrom.setTextColor(currentAccentColor.toColorInt())
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
    val padV = (14 * d).toInt()
    val hintColor = "#9E9E9E".toColorInt()
    listOf(emailInput, passwordInput, serverUrlInput).forEach {
        it.backgroundTintList = null
        it.setBackgroundResource(R.drawable.input_field_bg)
        it.setPadding(padH, padV, padH, padV)
        it.setHintTextColor(hintColor)
    }
}

/** Rounded accent-colored button used for primary actions (login, grant, settings). */
internal fun MainActivity.styleAccentButton(button: Button) {
    val d = resources.displayMetrics.density
    button.background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 14 * d
        setColor(currentAccentColor.toColorInt())
    }
    button.setTextColor(getOnAccentColor())
    button.isAllCaps = false
    button.stateListAnimator = null
    button.alpha = if (button.isEnabled) 1f else 0.5f
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
        cornerRadius = 28 * d
        setColor(darkenColor(accentInt, 0.78f))
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        window.statusBarColor = accentInt
    }
    if (isShowingEmailDetail) {
        detailFrom.setTextColor(accentInt)
    }
    // Switch tint: accent when ON, gray when OFF
    val offColor = if (currentTheme == "light") "#BDBDBD".toColorInt() else "#555555".toColorInt()
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
    val dropdownBg = darkenColor(accentInt, 0.82f)
    val dropdownStroke = darkenColor(accentInt, 0.55f)
    val dropdownCorner = d * 18
    for (dropdown in listOf(swipeLeftDropdown, swipeRightDropdown, themeDropdown)) {
        dropdown.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dropdownCorner
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
    var pendingColor = currentAccentColor

    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val p = (16 * dp).toInt()
        setPadding(p, (14 * dp).toInt(), p, (8 * dp).toInt())
    }

    // Full-width color preview bar
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
    refreshPreview(runCatching { pendingColor.toColorInt() }.getOrDefault("#1976D2".toColorInt()))

    // Swatches row
    val swatchRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER
    }
    val swatchViews = mutableListOf<View>()

    fun refreshSwatches(selected: String) {
        swatchViews.forEach { v ->
            val col = v.tag as String
            v.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(col.toColorInt())
                if (col.equals(selected, ignoreCase = true))
                    setStroke((2.5 * dp).toInt(), Color.WHITE)
            }
        }
    }

    MainActivity.ACCENT_COLORS.forEach { color ->
        val sz = (32 * dp).toInt()
        val swatch = View(this).apply {
            tag = color
            layoutParams = LinearLayout.LayoutParams(sz, sz).also {
                it.setMargins((6 * dp).toInt(), 0, (6 * dp).toInt(), 0)
            }
            setOnClickListener {
                pendingColor = color
                refreshPreview(color.toColorInt())
                refreshSwatches(color)
            }
        }
        swatchViews.add(swatch)
        swatchRow.addView(swatch)
    }
    refreshSwatches(pendingColor)

    root.addView(previewBar)
    root.addView(swatchRow)

    AlertDialog.Builder(this)
        .setTitle("Accent color")
        .setView(root)
        .setPositiveButton("Apply") { _, _ ->
            currentAccentColor = pendingColor
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
    "light" -> "#EEEEEE".toColorInt()
    "oled"  -> "#121212".toColorInt()
    else    -> "#252525".toColorInt()
}
