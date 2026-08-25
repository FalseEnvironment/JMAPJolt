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
    hideContactsScreen()
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
    refreshSettingsAccountRow()
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

    // Sections are always-expanded M3 grouped-list cards now; headers are static
    // labels (no more accordion expand/collapse).
    settingsCalendarChevron = findViewById(R.id.settingsCalendarChevron)
    settingsImportIcsRow = findViewById(R.id.settingsImportIcsRow)
    settingsExportIcsRow = findViewById(R.id.settingsExportIcsRow)
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
    settingsCalTimeFormatDropdown.setOnClickListener {
        val formats = listOf(
            CalendarPrefs.TimeFormat.SYSTEM,
            CalendarPrefs.TimeFormat.H24,
            CalendarPrefs.TimeFormat.H12)
        val options = formats.map { getString(calTimeFormatLabel(it)) }
        showSettingsDropdown(
            settingsCalTimeFormatDropdown,
            options,
            formats.indexOf(CalendarPrefs.timeFormat(this)).coerceAtLeast(0)
        ) { idx ->
            CalendarPrefs.setTimeFormat(this, formats[idx])
            updateCalTimeFormatUi()
            // Re-render every surface that prints a clock time with the new pattern.
            calendarPanelView?.refresh()
            CalendarWidgetProvider.refreshAll(applicationContext)
            CalendarWeekWidgetProvider.refreshAll(applicationContext)
        }
    }
    updateCalTimeFormatUi()
    settingsCalTimeZoneDropdown.setOnClickListener {
        // "Automatic" first, then the curated offset list (negative → UTC → positive).
        val ids = listOf<String?>(null) + TimeZones.entries.map { it.zoneId }
        val options = listOf(getString(R.string.settings_cal_timezone_auto)) + TimeZones.labels()
        val current = ids.indexOf(CalendarPrefs.timeZoneId(this)).coerceAtLeast(0)
        showSettingsDropdown(settingsCalTimeZoneDropdown, options, current) { idx ->
            CalendarPrefs.setTimeZone(this, ids[idx])
            updateCalTimeZoneUi()
            calendarPanelView?.refresh()
            CalendarWidgetProvider.refreshAll(applicationContext)
            CalendarWeekWidgetProvider.refreshAll(applicationContext)
            // Midnight moved with the zone: re-arm the day-roll alarm on the new one.
            WidgetDayRollReceiver.schedule(applicationContext)
        }
    }
    updateCalTimeZoneUi()
    settingsAccountProfileRow.setOnClickListener {
        val email = currentAccountEmail ?: savedAccounts.firstOrNull()?.email
        if (email != null) showEditProfileDialog(email)
    }
    settingsAccountAddRow.setOnClickListener { showAddAccountDialog() }
    refreshSettingsAccountRow()
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
    contactsEnabledSwitch.isChecked = ContactsPrefs.isEnabled(this)
    settingsContactsOptions.visibility =
        if (ContactsPrefs.isEnabled(this)) View.VISIBLE else View.GONE
    contactsEnabledSwitch.setOnCheckedChangeListener { _, enabled ->
        ContactsPrefs.setEnabled(this, enabled)
        settingsContactsOptions.visibility = if (enabled) View.VISIBLE else View.GONE
        if (!enabled && contactsPanelView?.visibility == View.VISIBLE) showMailboxScreen()
        navigationView.post { rebuildDrawerMenu() }
    }
    settingsContactsShowDropdown.setOnClickListener {
        val values = ContactsPrefs.Show.entries
        val options = values.map { getString(contactsShowLabel(it)) }
        showSettingsDropdown(
            settingsContactsShowDropdown, options, values.indexOf(ContactsPrefs.show(this))
        ) { idx ->
            ContactsPrefs.setShow(this, values[idx])
            updateContactsShowUi()
            contactsPanelView?.applyShowPreference()
        }
    }
    updateContactsShowUi()
    settingsImportVcfRow.setOnClickListener {
        runCatching { importVcfLauncher.launch(arrayOf("text/vcard", "text/x-vcard", "*/*")) }
    }
    settingsExportVcfRow.setOnClickListener {
        runCatching { exportVcfLauncher.launch("contacts-${System.currentTimeMillis()}.vcf") }
    }
    settingsInfoRow.setOnClickListener { showAboutDialog() }
}

/** Picked .vcf files above this are rejected so a crafted file cannot exhaust memory. */
private const val MAX_VCF_IMPORT_CHARS = 20 * 1024 * 1024

/** Reads at most [limit] chars; returns null when the stream keeps going past the limit. */
private fun java.io.Reader.readBounded(limit: Int): String? {
    val buf = CharArray(8192)
    val out = StringBuilder()
    while (true) {
        val n = read(buf)
        if (n < 0) return out.toString()
        if (out.length + n > limit) return null
        out.append(buf, 0, n)
    }
}

/**
 * Imports every card in the picked .vcf into the backend new contacts default to. DAVx5-backed
 * imports land in the system provider, which DAVx5 then pushes over CardDAV on its next sync.
 */
internal fun MainActivity.doImportVcf(uri: android.net.Uri) {
    val repository = ContactsRepository(applicationContext)
    val source = when (ContactsPrefs.provider(this)) {
        ContactsPrefs.Provider.DAVX5 -> ContactSource.DAVX5
        ContactsPrefs.Provider.JMAP -> ContactSource.JMAP
    }
    lifecycleScope.launch {
        val saved = withContext(Dispatchers.IO) {
            runCatching {
                val text = contentResolver.openInputStream(uri)?.bufferedReader()
                    ?.use { it.readBounded(MAX_VCF_IMPORT_CHARS) } ?: return@runCatching -1
                if (!ContactsVcf.looksLikeVcf(text)) return@runCatching -1
                ContactsVcf.parse(text, source).count { repository.save(it) != null }
            }.getOrDefault(-1)
        }
        if (saved >= 0) {
            ContactsCache.contacts = null
            repository.warmCache()
            contactsPanelView?.refresh()
            showInAppMessage(getString(R.string.contacts_import_done, saved))
        } else showInAppMessage(getString(R.string.contacts_import_failed))
    }
}

internal fun MainActivity.doExportVcf(uri: android.net.Uri) {
    val repository = ContactsRepository(applicationContext)
    lifecycleScope.launch {
        val count = withContext(Dispatchers.IO) {
            runCatching {
                val contacts = repository.loadAll()
                val vcf = ContactsVcf.toVcf(contacts)
                contentResolver.openOutputStream(uri)?.use { it.write(vcf.toByteArray()) }
                contacts.size
            }.getOrDefault(-1)
        }
        if (count >= 0) showInAppMessage(getString(R.string.contacts_export_done, count))
        else showInAppMessage(getString(R.string.contacts_export_failed))
    }
}

internal fun MainActivity.requestCalendarPermissions(onResult: () -> Unit) {
    calendarPermissionCallback = onResult
    calendarPermissionLauncher.launch(arrayOf(
        android.Manifest.permission.READ_CALENDAR,
        android.Manifest.permission.WRITE_CALENDAR
    ))
}

internal fun calTimeFormatLabel(format: CalendarPrefs.TimeFormat): Int = when (format) {
    CalendarPrefs.TimeFormat.H24 -> R.string.settings_cal_time_format_24h
    CalendarPrefs.TimeFormat.H12 -> R.string.settings_cal_time_format_12h
    CalendarPrefs.TimeFormat.SYSTEM -> R.string.settings_cal_time_format_system
}

internal fun MainActivity.updateCalTimeFormatUi() {
    settingsCalTimeFormatText.text = getString(calTimeFormatLabel(CalendarPrefs.timeFormat(this)))
}

private fun contactsShowLabel(show: ContactsPrefs.Show): Int = when (show) {
    ContactsPrefs.Show.BOTH -> R.string.settings_contacts_show_both
    ContactsPrefs.Show.JMAP_ONLY -> R.string.settings_contacts_show_jmap
    ContactsPrefs.Show.DAVX5_ONLY -> R.string.settings_contacts_show_davx5
}

internal fun MainActivity.updateContactsShowUi() {
    settingsContactsShowText.text = getString(contactsShowLabel(ContactsPrefs.show(this)))
}

internal fun MainActivity.updateCalTimeZoneUi() {
    settingsCalTimeZoneText.text = CalendarPrefs.zoneLabel(this)
}

/** Mirrors the drawer profile entry into the Settings > Account card. */
internal fun MainActivity.refreshSettingsAccountRow() {
    // Called from dialogs that can outlive the settings screen binding, so tolerate
    // the views not being wired up yet.
    val row = findViewById<LinearLayout>(R.id.settingsAccountProfileRow) ?: return
    val email = currentAccountEmail ?: savedAccounts.firstOrNull()?.email
    if (email.isNullOrBlank()) {
        // Signed out: only "Add account" is actionable.
        row.visibility = View.GONE
        return
    }
    row.visibility = View.VISIBLE
    val sizePx = (36 * resources.displayMetrics.density).toInt()
    findViewById<ImageView>(R.id.settingsAccountAvatar)?.setImageBitmap(
        buildAccountAvatar(email, sizePx)
    )
    findViewById<TextView>(R.id.settingsAccountName)?.let { nameView ->
        nameView.text = getAccountDisplayName(email)
        // The dot only carries meaning when the unified inbox can mix accounts.
        applyAccountColorDot(nameView, email, savedAccounts.size >= 2)
    }
    findViewById<TextView>(R.id.settingsAccountEmail)?.text = email
}

/**
 * Puts a small disc in the account's unified-inbox color before [nameView]'s text,
 * or removes it when [show] is false (single-account setups, where the color says nothing).
 */
internal fun MainActivity.applyAccountColorDot(nameView: TextView, email: String, show: Boolean) {
    if (!show) {
        nameView.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
        return
    }
    val dp = resources.displayMetrics.density
    val sizePx = (10 * dp).toInt()
    val dot = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(getAccountColor(email))
        setSize(sizePx, sizePx)
        setBounds(0, 0, sizePx, sizePx)
    }
    nameView.setCompoundDrawablesRelative(dot, null, null, null)
    nameView.compoundDrawablePadding = (6 * dp).toInt()
}

internal fun MainActivity.updateCalProviderUi() {
    val isDavx5 = CalendarPrefs.provider(this) == CalendarPrefs.Provider.DAVX5
    val accent = currentAccentColor.toColorInt()
    settingsCalProviderText.text = getString(
        if (isDavx5) R.string.settings_cal_provider_davx5
        else R.string.settings_cal_provider_jmap)
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
    showThemedSnackbar(text)
}


internal fun MainActivity.showAboutDialog() {
        val activity = this
    val dp = resources.displayMetrics.density
    val bgColor = getDialogBackgroundColor()
    val textColor = if (currentTheme == "light") "#212121".toColorInt() else Color.WHITE
    val subColor = if (currentTheme == "light") "#757575".toColorInt() else "#BDBDBD".toColorInt()
    val accentInt = currentAccentColor.toColorInt()

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

    val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
    val offThumb = if (currentTheme == "light") Color.parseColor("#BDBDBD") else Color.parseColor("#757575")
    val offTrack = if (currentTheme == "light") Color.parseColor("#DDDDDD") else Color.parseColor("#444444")
    val accentAlpha = Color.argb(77, Color.red(accentInt), Color.green(accentInt), Color.blue(accentInt))
    val thumbStates = android.content.res.ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(accentInt, offThumb)
    )
    val trackStates = android.content.res.ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(accentAlpha, offTrack)
    )
    val rippleStates = android.content.res.ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(accentAlpha, Color.argb(40, Color.red(offThumb), Color.green(offThumb), Color.blue(offThumb)))
    )
    val debugSwitch = androidx.appcompat.widget.SwitchCompat(activity).apply {
        isChecked = prefs.getBoolean("debug_mode", false)
        textOff = ""
        textOn = ""
        thumbTintList = thumbStates
        trackTintList = trackStates
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            background = android.graphics.drawable.RippleDrawable(
                rippleStates, null, null
            )
        }
    }
    view.addView(LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = (12 * dp).toInt() }
        addView(TextView(activity).apply {
            text = "Debug mode"
            setTextColor(textColor)
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(debugSwitch)
    })
    debugSwitch.setOnCheckedChangeListener { _, enabled ->
        prefs.edit().putBoolean("debug_mode", enabled).apply()
        status.visibility = if (enabled) android.view.View.VISIBLE else android.view.View.GONE
    }

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
    status.visibility = if (prefs.getBoolean("debug_mode", false)) android.view.View.VISIBLE else android.view.View.GONE
    loadImagesSwitch.isChecked = prefs.getBoolean("load_images", false)
    loadFaviconsSwitch.isChecked = prefs.getBoolean("load_favicons", false)
    markReadDelaySeconds = prefs.getInt(MainActivity.KEY_MARK_READ_DELAY_SECONDS, 0)
        .coerceIn(0, MainActivity.MARK_READ_DELAY_MAX_SECONDS)
    updateSettingsDropdownDisplays()
}

internal fun MainActivity.saveGeneralPreferences() {
    val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit()
        .putBoolean("load_images", loadImagesSwitch.isChecked)
        .putBoolean("load_favicons", loadFaviconsSwitch.isChecked)
        .putInt(MainActivity.KEY_MARK_READ_DELAY_SECONDS, markReadDelaySeconds)
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
    findViewById<LinearLayout>(R.id.settingsEditFoldersRow).setOnClickListener {
        showFolderEditorDialog()
    }
    settingsEditFoldersButton.apply {
        setOnClickListener { showFolderEditorDialog() }
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

/** Preset choices for the "mark as read after" delay; the last slot is always "Custom…". */
private val MARK_READ_DELAY_PRESETS = listOf(0, 1, 3, 5, 10, 15, 30, 60)

internal fun MainActivity.markReadDelayLabel(seconds: Int): String =
    if (seconds == 0) getString(R.string.mark_read_delay_instant)
    else getString(R.string.mark_read_delay_custom_seconds, seconds)

internal fun MainActivity.setupMarkReadDelaySpinner() {
    markReadDelayDropdown.setOnClickListener {
        val options = MARK_READ_DELAY_PRESETS.map { markReadDelayLabel(it) } +
            getString(R.string.mark_read_delay_custom)
        val presetIdx = MARK_READ_DELAY_PRESETS.indexOf(markReadDelaySeconds)
        val currentIdx = if (presetIdx >= 0) presetIdx else options.lastIndex
        showSettingsDropdown(markReadDelayDropdown, options, currentIdx) { idx ->
            if (idx == options.lastIndex) {
                showMarkReadDelayCustomDialog()
            } else {
                markReadDelaySeconds = MARK_READ_DELAY_PRESETS[idx]
                updateSettingsDropdownDisplays()
                saveGeneralPreferences()
            }
        }
    }
}

/** Free-form entry for a delay outside the presets, clamped to 1–60s. */
internal fun MainActivity.showMarkReadDelayCustomDialog() {
    val dp = resources.displayMetrics.density
    val textColor = if (currentTheme == "light") "#212121".toColorInt() else Color.WHITE
    val secondaryColor = if (currentTheme == "light") "#757575".toColorInt() else "#9E9E9E".toColorInt()
    val accentInt = currentAccentColor.toColorInt()

    val input = android.widget.EditText(this).apply {
        inputType = android.text.InputType.TYPE_CLASS_NUMBER
        hint = getString(R.string.mark_read_delay_dialog_hint)
        setText(
            (if (markReadDelaySeconds in 1..MainActivity.MARK_READ_DELAY_MAX_SECONDS) markReadDelaySeconds else 20).toString()
        )
        setTextColor(textColor)
        setHintTextColor(secondaryColor)
        backgroundTintList = android.content.res.ColorStateList.valueOf(secondaryColor)
        textSize = 15f
        maxLines = 1
    }
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val p = (22 * dp).toInt()
        setPadding(p, p, p, (14 * dp).toInt())
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 20 * dp
            setColor(getDialogBackgroundColor())
        }
        addView(TextView(this@showMarkReadDelayCustomDialog).apply {
            text = getString(R.string.mark_read_delay_dialog_title)
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(textColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (16 * dp).toInt() }
        })
        addView(input)
    }
    val dialog = androidx.appcompat.app.AlertDialog.Builder(this).setView(root).create()
    dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
    val btnRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = (8 * dp).toInt() }
    }
    fun btn(label: String, color: Int, bold: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14f; setTextColor(color)
        if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
        setPadding((14 * dp).toInt(), (10 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }
    btnRow.addView(btn(getString(R.string.action_cancel), secondaryColor, false) { dialog.dismiss() })
    btnRow.addView(btn(getString(android.R.string.ok), accentInt, true) {
        val seconds = input.text.toString().toIntOrNull()?.coerceIn(1, MainActivity.MARK_READ_DELAY_MAX_SECONDS)
        if (seconds != null) {
            markReadDelaySeconds = seconds
            updateSettingsDropdownDisplays()
            saveGeneralPreferences()
        }
        dialog.dismiss()
    })
    root.addView(btnRow)
    dialog.show()
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
    markReadDelayDropdownText.text = markReadDelayLabel(markReadDelaySeconds)
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

    // Long lists (time zones) must not run off screen: cap the popup and let it scroll.
    val scroller = android.widget.ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        clipToOutline = true
        addView(container)
    }
    val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
    container.measure(unspecified, unspecified)
    val maxHeight = (resources.displayMetrics.heightPixels * 0.55f).toInt()
    val popupHeight =
        if (container.measuredHeight > maxHeight) maxHeight
        else android.view.ViewGroup.LayoutParams.WRAP_CONTENT

    val pw = android.widget.PopupWindow(
        scroller,
        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
        popupHeight,
        true
    ).apply {
        elevation = 10 * dp
        isOutsideTouchable = true
    }
    popupRef = pw
    pw.showAsDropDown(anchor, 0, (4 * dp).toInt())
    // Open on the current choice instead of the top of a long list.
    if (popupHeight != android.view.ViewGroup.LayoutParams.WRAP_CONTENT) {
        scroller.post {
            val row = container.getChildAt(currentIdx) ?: return@post
            scroller.scrollTo(0, (row.top - maxHeight / 3).coerceAtLeast(0))
        }
    }
    // Entrance: scale-in from the anchor corner with a fade (MD3 menu motion).
    scroller.alpha = 0f
    scroller.scaleX = 0.86f
    scroller.scaleY = 0.78f
    scroller.post {
        scroller.pivotX = scroller.width * 0.85f
        scroller.pivotY = 0f
        scroller.animate()
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

