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

internal fun MainActivity.handleWidgetIntent(intent: Intent?) {
    intent?.getStringExtra(InboxWidgetProvider.EXTRA_OPEN_INBOX)?.let { selection ->
        intent.removeExtra(InboxWidgetProvider.EXTRA_OPEN_INBOX)
        openWidgetInbox(selection)
        return
    }
    val id = intent?.getStringExtra(InboxWidgetProvider.EXTRA_OPEN_EMAIL_ID) ?: return
    if (id.isBlank()) return
    pendingWidgetEmailId = id
    pendingWidgetAccount = intent.getStringExtra(InboxWidgetProvider.EXTRA_OPEN_ACCOUNT)
    widgetSwitchAttempted = false
    // Consume so a config change or re-delivery doesn't reopen it.
    intent.removeExtra(InboxWidgetProvider.EXTRA_OPEN_EMAIL_ID)
    // On a warm start data is already loaded; on cold start the session-restore
    // load path will call tryOpenPendingWidgetEmail once emails arrive.
    if (baseEmails.isNotEmpty()) tryOpenPendingWidgetEmail()
}

internal fun MainActivity.openWidgetInbox(selection: String) {
    if (composeContainer.visibility == View.VISIBLE) hideCompose()
    if (selection == WidgetSupport.UNIFIED || savedAccounts.size <= 1) {
        selectedFolder = if (savedAccounts.size > 1) R.id.nav_unified_inbox else R.id.nav_inbox
        showMailboxScreen()
        applyFolderFilterAndRefresh()
        navigationView.post { rebuildDrawerMenu() }
        return
    }
    val account = savedAccounts.firstOrNull { it.email.equals(selection, ignoreCase = true) }
    if (account != null) switchToSavedAccount(account, forceInbox = true)
    else showMailboxScreen()
}

internal fun MainActivity.tryOpenPendingWidgetEmail() {
    val id = pendingWidgetEmailId ?: return
    val account = pendingWidgetAccount
    // Different account than the one shown: switch to it once, then wait for its load.
    if (!account.isNullOrBlank() && account != WidgetSupport.UNIFIED &&
        !account.equals(currentAccountEmail, ignoreCase = true)) {
        if (!widgetSwitchAttempted) {
            widgetSwitchAttempted = true
            savedAccounts.firstOrNull { it.email.equals(account, ignoreCase = true) }
                ?.let { switchToSavedAccount(it, forceInbox = true) }
        }
        return
    }
    val match = baseEmails.firstOrNull { it.id == id } ?: return
    pendingWidgetEmailId = null
    pendingWidgetAccount = null
    if (!isShowingEmailDetail) showEmailDetail(match)
}

internal fun MainActivity.prefetchEmailBody(email: DisplayEmail) {
    if (email.fullBody.isNotBlank()) return
    if (!prefetchingIds.add(email.id)) return
    val account = resolveAccountFor(email) ?: run { prefetchingIds.remove(email.id); return }
    lifecycleScope.launch {
        try {
            val fresh = jmapClient.fetchEmailsById(account, listOf(email.id)).firstOrNull()
            if (fresh != null && fresh.fullBody.isNotBlank()) {
                val updated = email.copy(fullBody = fresh.fullBody, attachments = fresh.attachments)
                val idx = emails.indexOfFirst { it.id == email.id }
                if (idx >= 0) {
                    emails[idx] = updated
                    val bi = baseEmails.indexOfFirst { it.id == email.id }
                    if (bi >= 0) baseEmails[bi] = updated
                }
            }
        } catch (_: Exception) {
            // Silent failure — will retry on open
        } finally {
            prefetchingIds.remove(email.id)
        }
    }
}

