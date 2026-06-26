package com.falseenvironment.jmapjolt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.Html
import android.text.Layout
import android.text.Spannable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.text.style.AlignmentSpan
import android.text.style.BulletSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.Log
import android.util.Patterns
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.Menu
import android.widget.HorizontalScrollView
import android.widget.PopupMenu
import android.graphics.PorterDuff
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import androidx.appcompat.widget.SwitchCompat
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.core.view.GravityCompat
import androidx.core.widget.CompoundButtonCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.viewpager2.widget.ViewPager2
import coil.load
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.unifiedpush.android.connector.INSTANCE_DEFAULT
import org.unifiedpush.android.connector.UnifiedPush

internal fun MainActivity.showSettingsScreen() {
    hideCalendarScreen()
    onboardingContainer.visibility = View.GONE
    loginContainer.visibility = View.GONE
    mailboxContainer.visibility = View.GONE
    settingsContainer.visibility = View.VISIBLE
    settingsContainer.animateScreenIn()
    fabCompose.animateFabOut()
    customTopBar.visibility = View.VISIBLE
    currentSettingsSection = MainActivity.SettingsSection.ROOT
    invalidateOptionsMenu()
    setDrawerIndicator(true)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)
    drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
    drawerToggle.syncState()
    updateTopBarState()
    showSettingsMenuRoot()
    loadUnifiedPushPreferences()
    rebuildDrawerMenu()
}

internal fun MainActivity.bindSettingsMenuNavigation() {
        val activity = this
    loadImagesSwitch.setOnCheckedChangeListener { _, isChecked ->
        saveGeneralPreferences()
        if (isShowingEmailDetail) {
            detailWebView.settings.blockNetworkImage = !isChecked
            if (isChecked) detailWebView.reload()
        }
    }
    loadFaviconsSwitch.setOnCheckedChangeListener { _, isChecked ->
        if (suppressFaviconToggle) return@setOnCheckedChangeListener
        if (isChecked) {
            // Hold the switch off until the user confirms; the dialog is async, so a
            // bare isChecked=false here would re-enter this listener and save "off".
            suppressFaviconToggle = true
            loadFaviconsSwitch.isChecked = false
            suppressFaviconToggle = false
            showThemedConfirmDialog(
                title = "Auto-load favicons",
                message = "This feature uses DuckDuckGo's external service (icons.duckduckgo.com) to fetch favicons for email senders. No personal data is sent, only the domain name.",
                confirmLabel = "Enable"
            ) {
                suppressFaviconToggle = true
                loadFaviconsSwitch.isChecked = true
                suppressFaviconToggle = false
                saveGeneralPreferences()
                emailAdapter.loadFaviconsEnabled = true
                emailAdapter.notifyDataSetChanged()
            }
        } else {
            saveGeneralPreferences()
            emailAdapter.loadFaviconsEnabled = false
            emailAdapter.notifyDataSetChanged()
        }
    }

    settingsGeneralHeader.setOnClickListener {
        toggleSettingsSection(settingsGeneralContent, settingsGeneralChevron)
    }
    settingsLabelsHeader.setOnClickListener {
        toggleSettingsSection(settingsLabelsContent, settingsLabelsChevron)
    }
    settingsThemeHeader.setOnClickListener {
        toggleSettingsSection(settingsThemeContent, settingsThemeChevron)
    }
    settingsUnifiedPushHeader.setOnClickListener {
        toggleSettingsSection(settingsUnifiedPushContent, settingsUnifiedPushChevron)
    }
    val settingsCalendarHeader = findViewById<LinearLayout>(R.id.settingsCalendarHeader)
    val settingsCalendarContent = findViewById<LinearLayout>(R.id.settingsCalendarContent)
    settingsCalendarChevron = findViewById(R.id.settingsCalendarChevron)
    settingsImportIcsRow = findViewById(R.id.settingsImportIcsRow)
    settingsExportIcsRow = findViewById(R.id.settingsExportIcsRow)
    settingsCalendarHeader.setOnClickListener {
        toggleSettingsSection(settingsCalendarContent, settingsCalendarChevron)
    }
    settingsCalProviderDropdown.setOnClickListener {
        val options = listOf(
            getString(R.string.settings_cal_provider_jmap),
            getString(R.string.settings_cal_provider_davx5))
        val current = if (CalendarPrefs.provider(this) == CalendarPrefs.Provider.DAVX5) 1 else 0
        showSettingsDropdown(settingsCalProviderDropdown, options, current) { idx ->
            val chosen = if (idx == 1) CalendarPrefs.Provider.DAVX5 else CalendarPrefs.Provider.JMAP
            CalendarPrefs.setProvider(this, chosen)
            updateCalProviderUi()
            onCalendarProviderChosen(chosen)
        }
    }
    settingsCalAddProviderButton.setOnClickListener { CalendarDavx5.launch(activity) }
    calendarEnabledSwitch.isChecked = CalendarPrefs.isEnabled(this)
    calendarEnabledSwitch.setOnCheckedChangeListener { _, enabled ->
        CalendarPrefs.setEnabled(this, enabled)
        findViewById<LinearLayout>(R.id.settingsCalOptions).visibility =
            if (enabled) View.VISIBLE else View.GONE
        if (!enabled && calendarPanelView?.visibility == View.VISIBLE) showMailboxScreen()
        navigationView.post { rebuildDrawerMenu() }
    }
    findViewById<LinearLayout>(R.id.settingsCalOptions).visibility =
        if (CalendarPrefs.isEnabled(this)) View.VISIBLE else View.GONE
    updateCalProviderUi()
    settingsImportIcsRow.setOnClickListener {
        runCatching { importIcsLauncher.launch(arrayOf("text/calendar", "*/*")) }
    }
    settingsExportIcsRow.setOnClickListener {
        runCatching { exportIcsLauncher.launch("calendar-${System.currentTimeMillis()}.ics") }
    }
    settingsInfoRow.setOnClickListener { showAboutDialog() }
}

internal fun MainActivity.requestCalendarPermissions(onResult: () -> Unit) {
    calendarPermissionCallback = onResult
    calendarPermissionLauncher.launch(arrayOf(
        android.Manifest.permission.READ_CALENDAR,
        android.Manifest.permission.WRITE_CALENDAR
    ))
}

internal fun MainActivity.updateCalProviderUi() {
    val isDavx5 = CalendarPrefs.provider(this) == CalendarPrefs.Provider.DAVX5
    val accent = currentAccentColor.toColorInt()
    settingsCalProviderText.text = getString(
        if (isDavx5) R.string.settings_cal_provider_davx5
        else R.string.settings_cal_provider_jmap)
    findViewById<TextView>(R.id.settingsCalProviderHint)?.text = getString(
        if (isDavx5) R.string.settings_cal_provider_hint_davx5
        else R.string.settings_cal_provider_hint_jmap)
    settingsCalAddProviderButton.visibility = if (isDavx5) View.VISIBLE else View.GONE
    val accountText = findViewById<TextView>(R.id.settingsCalProviderAccount)
    val connected = if (isDavx5 && CalendarProvider.hasReadPermission(this)) {
        CalendarProvider.calendars(this)
            .map { it.accountName }
            .filter { it.isNotBlank() && !it.equals("LOCAL", ignoreCase = true) }
            .distinct()
    } else emptyList()
    if (connected.isEmpty()) {
        accountText?.visibility = View.GONE
    } else {
        accountText?.text = getString(R.string.settings_cal_connected, connected.joinToString(", "))
        accountText?.visibility = View.VISIBLE
    }
}

internal fun MainActivity.onCalendarProviderChosen(provider: CalendarPrefs.Provider) {
    when (provider) {
        CalendarPrefs.Provider.DAVX5 ->
            if (!CalendarProvider.hasReadPermission(this)) {
                requestCalendarPermissions { updateCalProviderUi() }
            }
        CalendarPrefs.Provider.JMAP -> {
            val account = CalendarAccount.current(this) ?: run {
                showInAppMessage(getString(R.string.calendar_jmap_unsupported)); return
            }
            lifecycleScope.launch {
                val result = CalendarSync.sync(applicationContext, account)
                if (!result.supported) showInAppMessage(getString(R.string.calendar_jmap_unsupported))
            }
        }
    }
}

internal fun MainActivity.doImportIcs(uri: android.net.Uri) {
    lifecycleScope.launch {
        val count = withContext(Dispatchers.IO) {
            runCatching {
                val text = contentResolver.openInputStream(uri)?.bufferedReader()
                    ?.use { it.readText() } ?: return@runCatching 0
                val events = CalendarIcs.parse(text, "local")
                events.forEach { CalendarStore.upsert(applicationContext, it) }
                events.size
            }.getOrDefault(-1)
        }
        if (count >= 0) {
            CalendarReminderScheduler.reschedule(applicationContext)
            calendarPanelView?.refresh()
            showInAppMessage("Imported $count event(s)")
        } else showInAppMessage("Import failed")
    }
}

internal fun MainActivity.doExportIcs(uri: android.net.Uri) {
    lifecycleScope.launch {
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val ics = CalendarIcs.toIcs(CalendarStore.active(applicationContext))
                contentResolver.openOutputStream(uri)?.use { it.write(ics.toByteArray()) }
                true
            }.getOrDefault(false)
        }
        showInAppMessage(if (ok) "Calendar exported" else "Export failed")
    }
}

internal fun MainActivity.showInAppMessage(text: String) {
    com.google.android.material.snackbar.Snackbar.make(
        findViewById(android.R.id.content), text,
        com.google.android.material.snackbar.Snackbar.LENGTH_LONG
    ).show()
}

internal fun MainActivity.toggleSettingsSection(content: LinearLayout, chevron: ImageView) {
    val open = content.visibility != View.VISIBLE
    chevron.animate().rotation(if (open) 180f else 0f).setDuration(220).start()
    if (open) {
        // Expand: measure target height, then grow from 0 with a fade.
        content.visibility = View.VISIBLE
        content.measure(
            View.MeasureSpec.makeMeasureSpec(settingsContainer.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val target = content.measuredHeight
        content.layoutParams.height = 0
        content.alpha = 0f
        android.animation.ValueAnimator.ofInt(0, target).apply {
            duration = 260
            interpolator = android.view.animation.DecelerateInterpolator(2f)
            addUpdateListener {
                content.layoutParams.height = it.animatedValue as Int
                content.alpha = it.animatedFraction
                content.requestLayout()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    content.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    content.alpha = 1f
                    content.requestLayout()
                }
            })
            start()
        }
    } else {
        // Collapse: shrink from current height to 0 with a fade.
        val start = content.height
        android.animation.ValueAnimator.ofInt(start, 0).apply {
            duration = 220
            interpolator = android.view.animation.AccelerateInterpolator(1.5f)
            addUpdateListener {
                content.layoutParams.height = it.animatedValue as Int
                content.alpha = 1f - it.animatedFraction
                content.requestLayout()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    content.visibility = View.GONE
                    content.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    content.alpha = 1f
                    content.requestLayout()
                }
            })
            start()
        }
    }
}

internal fun MainActivity.showAboutDialog() {
        val activity = this
    val dp = resources.displayMetrics.density
    val bgColor = getDialogBackgroundColor()
    val textColor = if (currentTheme == "light") "#212121".toColorInt() else Color.WHITE
    val subColor = if (currentTheme == "light") "#757575".toColorInt() else "#BDBDBD".toColorInt()

    val view = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding((28 * dp).toInt(), (28 * dp).toInt(), (28 * dp).toInt(), (12 * dp).toInt())
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 20 * dp
            setColor(bgColor)
        }

        addView(ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams((72 * dp).toInt(), (72 * dp).toInt()).also {
                it.bottomMargin = (16 * dp).toInt()
            }
            setImageResource(R.mipmap.ic_launcher_foreground)
            scaleType = ImageView.ScaleType.FIT_CENTER
        })
        addView(TextView(activity).apply {
            text = getString(R.string.app_name)
            setTextColor(textColor)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (4 * dp).toInt() }
        })
        addView(TextView(activity).apply {
            text = "v${packageManager.getPackageInfo(packageName, 0).versionName}"
            setTextColor(subColor)
            textSize = 14f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (24 * dp).toInt() }
        })
        val accentInt = currentAccentColor.toColorInt()
        addView(TextView(activity).apply {
            text = getString(R.string.about_source_code)
            setTextColor(accentInt)
            textSize = 15f
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = android.util.TypedValue().also {
                theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
            }.resourceId.let { ContextCompat.getDrawable(activity, it) }
            setPadding((8 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (8 * dp).toInt() }
            setOnClickListener {
                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://github.com/FalseEnvironment/JMAPJolt")))
            }
        })
    }

    val dialog = AlertDialog.Builder(this)
        .setView(view)
        .create()
    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

    view.addView(Button(this).apply {
        text = getString(R.string.about_close)
        isAllCaps = false
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24 * dp
            setColor(currentAccentColor.toColorInt())
        }
        setTextColor(Color.WHITE)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        setOnClickListener { dialog.dismiss() }
    })

    dialog.show()
}

internal fun MainActivity.loadGeneralPreferences() {
    val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
    loadImagesSwitch.isChecked = prefs.getBoolean("load_images", false)
    loadFaviconsSwitch.isChecked = prefs.getBoolean("load_favicons", false)
}

internal fun MainActivity.saveGeneralPreferences() {
    val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit()
        .putBoolean("load_images", loadImagesSwitch.isChecked)
        .putBoolean("load_favicons", loadFaviconsSwitch.isChecked)
        .apply()
}

internal fun MainActivity.showSettingsMenuRoot() {
    settingsMenuContainer.visibility = View.VISIBLE
    settingsGeneralContainer.visibility = View.VISIBLE
    settingsSwipeContainer.visibility = View.GONE
    settingsUnifiedPushContainer.visibility = View.VISIBLE
    settingsThemeContainer.visibility = View.VISIBLE
    currentSettingsSection = MainActivity.SettingsSection.ROOT
    setDrawerIndicator(true)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)
    drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
    drawerToggle.syncState()
    applyNavIconTint(getOnAccentColor())
    invalidateOptionsMenu()
    updateTopBarState()
}

internal fun MainActivity.bindSettingsActions() {
    accentColorRow.setOnClickListener { showAccentColorDialog() }
    findViewById<LinearLayout>(R.id.settingsEditLabelsRow).setOnClickListener {
        showLabelEditorDialog()
    }
    settingsEditLabelsButton.apply {
        setOnClickListener { showLabelEditorDialog() }
    }

    unifiedPushSwitch.setOnCheckedChangeListener { _: CompoundButton, enabled: Boolean ->
        saveUnifiedPushEnabled(enabled)
        if (enabled) {
            registerUnifiedPushAuto("")
            sendUnifiedPushTestNotification()
        } else {
            UnifiedPush.unregisterApp(this, INSTANCE_DEFAULT)
            EmailSyncWorker.cancel(this)
            showThemedSnackbar(getString(R.string.settings_unifiedpush_disabled))
        }
    }

    sseSwitch.setOnCheckedChangeListener { _: CompoundButton, enabled: Boolean ->
        JmapEventSourceService.setEnabled(this, enabled)
        if (enabled && connectedAccount != null) {
            JmapEventSourceService.start(this)
        } else {
            JmapEventSourceService.stop(this)
        }
    }

}

internal fun MainActivity.setupSwipeSpinners() {
    val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
    swipeRightActionIdx = MainActivity.SwipeAction.valueOf(
        prefs.getString(MainActivity.KEY_SWIPE_RIGHT_ACTION, MainActivity.SwipeAction.DELETE.name) ?: MainActivity.SwipeAction.DELETE.name
    ).ordinal
    swipeLeftActionIdx = MainActivity.SwipeAction.valueOf(
        prefs.getString(MainActivity.KEY_SWIPE_LEFT_ACTION, MainActivity.SwipeAction.ARCHIVE.name) ?: MainActivity.SwipeAction.ARCHIVE.name
    ).ordinal

    val swipeOptions = MainActivity.SwipeAction.entries.map { labelForSwipeAction(it) }
    swipeRightDropdown.setOnClickListener {
        showSettingsDropdown(swipeRightDropdown, swipeOptions, swipeRightActionIdx) { idx ->
            swipeRightActionIdx = idx
            updateSettingsDropdownDisplays()
            saveSwipePreferences()
        }
    }
    swipeLeftDropdown.setOnClickListener {
        showSettingsDropdown(swipeLeftDropdown, swipeOptions, swipeLeftActionIdx) { idx ->
            swipeLeftActionIdx = idx
            updateSettingsDropdownDisplays()
            saveSwipePreferences()
        }
    }
}

internal fun MainActivity.setupThemeSpinner() {
    val themeOptions = listOf(
        getString(R.string.settings_theme_gray),
        getString(R.string.settings_theme_light),
        getString(R.string.settings_theme_oled),
        getString(R.string.settings_theme_violet)
    )
    themeDropdown.setOnClickListener {
        showSettingsDropdown(themeDropdown, themeOptions, themeIdx) { idx ->
            themeIdx = idx
            val newTheme = when (idx) { 1 -> "light"; 2 -> "oled"; 3 -> "violet"; else -> "gray" }
            if (newTheme != currentTheme) {
                currentTheme = newTheme
                saveThemePreference()
                applyTheme()
            }
        }
    }
}

internal fun MainActivity.getRightSwipeAction(): MainActivity.SwipeAction = MainActivity.SwipeAction.entries[swipeRightActionIdx]
internal fun MainActivity.getLeftSwipeAction(): MainActivity.SwipeAction = MainActivity.SwipeAction.entries[swipeLeftActionIdx]

internal fun MainActivity.updateSettingsDropdownDisplays() {
    val swipeLabels = MainActivity.SwipeAction.entries.map { labelForSwipeAction(it) }
    swipeLeftDropdownText.text = swipeLabels.getOrElse(swipeLeftActionIdx) { "" }
    swipeRightDropdownText.text = swipeLabels.getOrElse(swipeRightActionIdx) { "" }
    val themeLabels = listOf(
        getString(R.string.settings_theme_gray),
        getString(R.string.settings_theme_light),
        getString(R.string.settings_theme_oled),
        getString(R.string.settings_theme_violet)
    )
    themeDropdownText.text = themeLabels.getOrElse(themeIdx) { "" }
}

internal fun MainActivity.showSettingsDropdown(
    anchor: View,
    options: List<String>,
    currentIdx: Int,
    icons: List<Int>? = null,
    onSelected: (Int) -> Unit
) {
    val activity = this
    val dp = resources.displayMetrics.density
    val popupBg = getDialogBackgroundColor()
    val isLight = currentTheme == "light"
    val contentColor = if (isLight) "#212121".toColorInt() else Color.WHITE
    val selectedTint = if (isLight) 0x14000000 else 0x33FFFFFF

    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16 * dp
            setColor(popupBg)
        }
        val vp = (6 * dp).toInt()
        setPadding(vp, vp, vp, vp)
        elevation = 8 * dp
    }

    var popupRef: android.widget.PopupWindow? = null

    options.forEachIndexed { idx, label ->
        container.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val rowW = (208 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(rowW, (46 * dp).toInt()).also {
                if (idx > 0) it.topMargin = (2 * dp).toInt()
            }
            val hp = (14 * dp).toInt()
            setPadding(hp, 0, hp, 0)
            // Selected row: rounded tonal pill with a check mark.
            if (idx == currentIdx) {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 11 * dp
                    setColor(selectedTint)
                }
            }
            icons?.getOrNull(idx)?.let { iconRes ->
                addView(ImageView(activity).apply {
                    setImageResource(iconRes)
                    imageTintList = ColorStateList.valueOf(contentColor)
                    val sz = (18 * dp).toInt()
                    layoutParams = LinearLayout.LayoutParams(sz, sz).also {
                        it.marginEnd = (12 * dp).toInt()
                    }
                })
            }
            addView(TextView(activity).apply {
                text = label
                textSize = 14f
                setTextColor(contentColor)
                if (idx == currentIdx) typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (idx == currentIdx) {
                addView(ImageView(activity).apply {
                    setImageResource(R.drawable.ic_lucide_check)
                    imageTintList = ColorStateList.valueOf(contentColor)
                    val sz = (18 * dp).toInt()
                    layoutParams = LinearLayout.LayoutParams(sz, sz).also {
                        it.marginStart = (8 * dp).toInt()
                    }
                })
            }
            setOnClickListener {
                // Quick tap pulse, then dismiss and apply.
                animateTap()
                postDelayed({ popupRef?.dismiss(); onSelected(idx) }, 120)
            }
        })
    }

    val pw = android.widget.PopupWindow(
        container,
        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
        true
    ).apply {
        elevation = 10 * dp
        isOutsideTouchable = true
    }
    popupRef = pw
    pw.showAsDropDown(anchor, 0, (4 * dp).toInt())
    // Entrance: scale-in from the anchor corner with a fade (MD3 menu motion).
    container.alpha = 0f
    container.scaleX = 0.86f
    container.scaleY = 0.78f
    container.post {
        container.pivotX = container.width * 0.85f
        container.pivotY = 0f
        container.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(200)
            .setInterpolator(android.view.animation.DecelerateInterpolator(2.5f))
            .start()
    }
}

internal fun MainActivity.saveSwipePreferences() {
    getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(MainActivity.KEY_SWIPE_RIGHT_ACTION, getRightSwipeAction().name)
            .putString(MainActivity.KEY_SWIPE_LEFT_ACTION, getLeftSwipeAction().name)
            .apply()
}

internal fun MainActivity.labelForSwipeAction(action: MainActivity.SwipeAction): String =
    when (action) {
        MainActivity.SwipeAction.DELETE -> getString(R.string.swipe_action_delete)
        MainActivity.SwipeAction.ARCHIVE -> getString(R.string.swipe_action_archive)
        MainActivity.SwipeAction.MARK_READ -> getString(R.string.swipe_action_read)
        MainActivity.SwipeAction.MARK_SPAM -> getString(R.string.swipe_action_spam)
    }

