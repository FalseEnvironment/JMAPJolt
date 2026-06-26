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

internal fun MainActivity.showAddAccountDialog() {
    val activity = this
    val dp = resources.displayMetrics.density
    val dialogBg = getDialogBackgroundColor()
    val accentInt = currentAccentColor.toColorInt()
    val textColor = if (currentTheme == "light") "#212121".toColorInt() else Color.WHITE
    val hintColor = if (currentTheme == "light") "#9E9E9E".toColorInt() else "#616161".toColorInt()
    val secondaryColor = if (currentTheme == "light") "#757575".toColorInt() else "#9E9E9E".toColorInt()

    val view = layoutInflater.inflate(R.layout.dialog_add_account, null)
    val dialogEmail = view.findViewById<EditText>(R.id.dialogEmailInput)
    val dialogPassword = view.findViewById<EditText>(R.id.dialogPasswordInput)
    val dialogServerUrl = view.findViewById<EditText>(R.id.dialogServerUrlInput)

    val root = view as LinearLayout
    root.background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 20 * dp
        setColor(dialogBg)
    }
    (root.getChildAt(0) as? TextView)?.setTextColor(textColor)
    // The XML input_field_bg drawable is a hardcoded dark grey that ignores the theme
    // (hints become grey-on-grey and unreadable). Give each field a theme-aware surface.
    val fieldFill = when (currentTheme) {
        "light"  -> "#FFFFFF".toColorInt()
        "oled"   -> "#141414".toColorInt()
        "violet" -> "#241634".toColorInt()
        else     -> "#2E2E34".toColorInt()
    }
    val fieldStroke = if (currentTheme == "light") "#D0D0D4".toColorInt() else "#454552".toColorInt()
    // A clearer hint colour so the field labels (Email Address / Password / JMAP URL) read well.
    val fieldHint = if (currentTheme == "light") "#8A8A90".toColorInt() else "#B0B0BA".toColorInt()
    listOf(dialogEmail, dialogPassword, dialogServerUrl).forEach {
        it.setTextColor(textColor)
        it.setHintTextColor(fieldHint)
        it.backgroundTintList = null
        it.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14 * dp
            setColor(fieldFill)
            setStroke((1 * dp).toInt(), fieldStroke)
        }
        it.setPadding((16 * dp).toInt(), (14 * dp).toInt(), (16 * dp).toInt(), (14 * dp).toInt())
        it.compoundDrawableTintList = ColorStateList.valueOf(fieldHint)
    }

    val btnRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = (4 * dp).toInt() }
        setPadding(0, 0, (8 * dp).toInt(), (6 * dp).toInt())
    }
    root.addView(btnRow)

    val dialog = AlertDialog.Builder(this)
        .setView(view)
        .create()

    val cancelBtn = TextView(this).apply {
        text = getString(R.string.action_cancel)
        textSize = 14f
        setTextColor(secondaryColor)
        setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
        isClickable = true; isFocusable = true
        setOnClickListener { dialog.dismiss() }
    }
    val loginBtn = TextView(this).apply {
        text = getString(R.string.login_button)
        textSize = 14f
        setTextColor(accentInt)
        setTypeface(null, android.graphics.Typeface.BOLD)
        setPadding((16 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt())
        isClickable = true; isFocusable = true
    }
    btnRow.addView(cancelBtn)
    btnRow.addView(loginBtn)

    loginBtn.setOnClickListener {
        val email = dialogEmail.text.toString()
        val password = dialogPassword.text.toString()
        val url = dialogServerUrl.text.toString()

        if (email.isBlank() || password.isBlank() || url.isBlank()) {
            android.widget.Toast.makeText(this, "Please fill in all fields", android.widget.Toast.LENGTH_SHORT).show()
            return@setOnClickListener
        }

        lifecycleScope.launch(Dispatchers.Main) {
            loginBtn.isEnabled = false
            val result = jmapClient.connect(email, password, url)
            if (result.success && result.connectedAccount != null) {
                val newAccount = result.connectedAccount
                val entry = AccountEntry(
                    email = newAccount.email,
                    password = newAccount.password,
                    serverUrl = url,
                    sessionUrl = newAccount.sessionUrl,
                    apiUrl = newAccount.apiUrl,
                    accountId = newAccount.accountId
                )
                val idx = savedAccounts.indexOfFirst { it.email.equals(newAccount.email, ignoreCase = true) }
                if (idx >= 0) savedAccounts[idx] = entry else savedAccounts.add(entry)
                currentAccountEmail = newAccount.email
                loadLabels()
                saveAccounts()
                connectedAccount = newAccount
                refreshInboxNow()
                renderAccountHeader()
                dialog.dismiss()
            } else {
                android.widget.Toast.makeText(activity, result.errorMessage ?: "Failed to connect", android.widget.Toast.LENGTH_LONG).show()
                loginBtn.isEnabled = true
            }
        }
    }

    dialog.show()
    dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
    dialog.window?.attributes?.let { lp ->
        lp.width = (resources.displayMetrics.widthPixels * 0.9f).toInt()
        dialog.window?.attributes = lp
    }
}

internal fun MainActivity.bindDrawerNavigation() {
    navigationView.setNavigationItemSelectedListener { item ->
        if (item.itemId == R.id.nav_calendar) {
            showCalendarScreen()
        } else if (item.itemId == R.id.nav_settings) {
            showSettingsScreen()
        } else {
            selectedFolder = item.itemId
            if (composeContainer.visibility == View.VISIBLE) hideCompose()
            showMailboxScreen()
            applyFolderFilterAndRefresh()
            navigationView.post { rebuildDrawerMenu() }
        }
        drawerLayout.closeDrawer(GravityCompat.START)
        true
    }
}

internal fun MainActivity.updateTopBarState() {
    val inMailbox = mailboxContainer.visibility == View.VISIBLE
    val title = when {
        settingsContainer.visibility == View.VISIBLE ->
            when (currentSettingsSection) {
                MainActivity.SettingsSection.GENERAL -> getString(R.string.settings_general)
                MainActivity.SettingsSection.SWIPE -> getString(R.string.settings_swipe_actions)
                MainActivity.SettingsSection.UNIFIED_PUSH -> getString(R.string.settings_unifiedpush)
                MainActivity.SettingsSection.THEME -> getString(R.string.settings_theme)
                MainActivity.SettingsSection.ROOT -> getString(R.string.settings_title)
            }
        inMailbox -> getCurrentMailboxTitle()
        else -> getString(R.string.app_name)
    }
    supportActionBar?.title = title
    updateCustomTopBar(title, inMailbox = inMailbox)
}

internal fun MainActivity.bindPullToRefresh() {
    mailSwipeRefresh.setOnChildScrollUpCallback { _, _ ->
        emailsRecyclerView.canScrollVertically(-1)
    }
    // Pull must travel further before triggering refresh: a slightly diagonal
    // swipe-to-delete/archive on the top rows would otherwise start a refresh.
    mailSwipeRefresh.setDistanceToTriggerSync(
        (MainActivity.PULL_TO_REFRESH_TRIGGER_DP * resources.displayMetrics.density).toInt()
    )
    mailSwipeRefresh.setOnRefreshListener {
        status.text = getString(R.string.mailbox_refreshing)
        refreshInboxNow { mailSwipeRefresh.isRefreshing = false }
    }
}

internal fun MainActivity.showThemedSnackbar(
    message: String,
    actionLabel: String? = null,
    actionIcon: Int? = null,
    action: (() -> Unit)? = null
) {
    val hasAction = actionLabel != null && action != null
    val sb = Snackbar.make(
        drawerLayout, message,
        if (hasAction) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT
    )
    val dp = resources.displayMetrics.density
    val isLight = currentTheme == "light"
    val bg = if (isLight) "#FFFFFF".toColorInt() else "#2A2A2E".toColorInt()
    val fg = if (isLight) "#212121".toColorInt() else Color.WHITE
    val accent = currentAccentColor.toColorInt()
    sb.setTextColor(fg)
    sb.setActionTextColor(accent)
    sb.view.background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 12 * dp
        setColor(bg)
    }
    if (hasAction) {
        sb.setAction(actionLabel) { action() }
        if (actionIcon != null) {
            sb.view.post {
                val actionView = sb.view.findViewById<Button>(
                    com.google.android.material.R.id.snackbar_action
                )
                actionView?.let { btn ->
                    val d = ContextCompat.getDrawable(this, actionIcon)?.mutate()
                    d?.setTint(accent)
                    val size = (18 * dp).toInt()
                    d?.setBounds(0, 0, size, size)
                    btn.setCompoundDrawables(d, null, null, null)
                    btn.compoundDrawablePadding = (6 * dp).toInt()
                }
            }
        }
    }
    sb.show()
}

internal fun MainActivity.attemptLeaveSettingsSubmenu() {
    if (currentSettingsSection == MainActivity.SettingsSection.ROOT) return
    showSettingsMenuRoot()
}

internal fun MainActivity.refreshInboxNow(onDone: (() -> Unit)? = null) {
    val account = connectedAccount
    if (account == null) {
        Log.w(MainActivity.TAG,"refreshInboxNow: no connected account")
        status.text = getString(R.string.status_sync_not_connected)
        onDone?.invoke()
        return
    }
    Log.d(MainActivity.TAG,"refreshInboxNow: starting fetch")
    startPeriodicSync()
    mailSwipeRefresh.isRefreshing = false
    onDone?.invoke()
}

internal fun MainActivity.registerUnifiedPushAuto(manualDistributor: String) {
    try {
        val distributors = UnifiedPush.getDistributors(this, arrayListOf())
        val preferred =
                distributors.firstOrNull {
                    it.contains("ntfy", ignoreCase = true) ||
                            it.contains("sunup", ignoreCase = true)
                }
                        ?: distributors.firstOrNull()

        val selected =
                when {
                    manualDistributor.isNotBlank() && normalizeUnifiedPushLink(manualDistributor) == null ->
                            manualDistributor
                    !preferred.isNullOrBlank() -> preferred
                    else -> null
                }

        if (!selected.isNullOrBlank()) {
            UnifiedPush.saveDistributor(this, selected)
        }

        UnifiedPush.registerApp(this, INSTANCE_DEFAULT, arrayListOf(), packageName)
        // Schedule the periodic fallback immediately so background sync works
        // even with no distributor installed or before an endpoint arrives.
        EmailSyncWorker.schedule(this)
        status.text = getString(R.string.settings_unifiedpush_registered, selected ?: "auto")
    } catch (_: Throwable) {
        status.text = getString(R.string.settings_unifiedpush_failed)
    }
}

internal fun MainActivity.rebuildDrawerMenuPublic() = rebuildDrawerMenu()

internal fun MainActivity.rebuildDrawerMenu() {
    val menu = navigationView.menu
    menu.clear()
    // Per-item icon colors (labels): disable the global tint and tint manually.
    navigationView.itemIconTintList = null
    // Theme-aware icon color: dark on light theme, light on dark themes. A hardcoded light
    // tint here previously turned drawer icons white after a rebuild on the light theme.
    val defaultIconTint = when (currentTheme) {
        "light" -> "#1B1B1F".toColorInt()
        else    -> "#E0E0E0".toColorInt()
    }

    var menuIndex = 0
    if (savedAccounts.size > 1) {
        val unifiedTitle: CharSequence = if (selectedFolder == R.id.nav_unified_inbox) {
            android.text.SpannableString("Unified Inbox").apply {
                setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, length, 0)
            }
        } else {
            "Unified Inbox"
        }
        menu.add(0, R.id.nav_unified_inbox, menuIndex++, unifiedTitle)
            .setIcon(R.drawable.ic_lucide_inbox)
            .icon?.mutate()?.setTint(defaultIconTint)
    }

    categoryOrder.forEachIndexed { index, id ->
        val name = categoryNames[id] ?: getDefaultCategoryTitle(id)
        val title: CharSequence = if (id == selectedFolder) {
            android.text.SpannableString(name).apply {
                setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, name.length, 0)
            }
        } else {
            name
        }
        val item = menu.add(0, id, menuIndex + index, title)
        item.setIcon(getCategoryIcon(id))
        item.icon?.mutate()?.setTint(defaultIconTint)
        item.isCheckable = true
    }

    // User labels: colored tag icons, ordered; long-press drag reorders them.
    var orderIdx = menuIndex + categoryOrder.size
    val knownKeywords = labels.map { it.keyword }.toSet()
    labelNavIds.keys.retainAll { labelNavIds[it] in knownKeywords }
    labels.forEach { label ->
        val navId = labelNavIds.entries.find { it.value == label.keyword }?.key
            ?: View.generateViewId().also { labelNavIds[it] = label.keyword }
        val title: CharSequence = if (navId == selectedFolder) {
            android.text.SpannableString(label.name).apply {
                setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, length, 0)
            }
        } else {
            label.name
        }
        val item = menu.add(0, navId, orderIdx++, title)
        item.setIcon(R.drawable.ic_lucide_tag)
        item.icon?.mutate()?.setTint(label.colorHex.toColorInt())
        item.isCheckable = true
    }

    val calendarEnabled = CalendarPrefs.isEnabled(this)
    val calendarItem = if (calendarEnabled) {
        menu.add(0, R.id.nav_calendar, orderIdx, getString(R.string.calendar_title)).apply {
            setIcon(R.drawable.ic_lucide_calendar)
            icon?.mutate()?.setTint(defaultIconTint)
            isCheckable = true
        }
    } else null

    val settingsItem =
            menu.add(
                    0,
                    R.id.nav_settings,
                    orderIdx + 1,
                    getString(R.string.settings_title)
            )
    settingsItem.setIcon(R.drawable.ic_lucide_settings)
    settingsItem.icon?.mutate()?.setTint(defaultIconTint)
    settingsItem.isCheckable = true

    // In the settings screen the accent highlight belongs on Settings, not the
    // previously selected folder (which stays remembered in selectedFolder).
    if (settingsContainer.visibility == View.VISIBLE) {
        settingsItem.isChecked = true
    } else if (calendarItem != null && calendarPanelView?.visibility == View.VISIBLE) {
        calendarItem.isChecked = true
    } else {
        menu.findItem(selectedFolder)?.isChecked = true
    }
    attachLabelDrag()

    val dp = resources.displayMetrics.density
    val accentInt = currentAccentColor.toColorInt()
    val r = android.graphics.Color.red(accentInt)
    val g = android.graphics.Color.green(accentInt)
    val b = android.graphics.Color.blue(accentInt)
    val cornerR = 999 * dp
    // Round only the trailing (right) corners; flat at the left screen edge -> tab/arrow shape.
    // Inset on the right so the accent bar does not run the full item width.
    val rightInset = (40 * dp).toInt()
    fun accentShape(alpha: Int): android.graphics.drawable.Drawable {
        val shape = android.graphics.drawable.GradientDrawable().apply {
            this.shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(android.graphics.Color.argb(alpha, r, g, b))
            cornerRadii = floatArrayOf(0f, 0f, cornerR, cornerR, cornerR, cornerR, 0f, 0f)
        }
        return android.graphics.drawable.InsetDrawable(shape, 0, 0, rightInset, 0)
    }
    val stateList = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_checked), accentShape(255))
        addState(intArrayOf(android.R.attr.state_activated), accentShape(255))
        addState(intArrayOf(android.R.attr.state_pressed), accentShape(110))
        addState(intArrayOf(), android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
    }
    navigationView.post { navigationView.itemBackground = stateList }
}

