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
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.viewpager2.widget.ViewPager2
import coil.load
import com.google.android.material.navigation.NavigationView
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.unifiedpush.android.connector.INSTANCE_DEFAULT
import org.unifiedpush.android.connector.UnifiedPush

/**
 * Calendar intent handling. The mailto and widget intents are handled next to the
 * features that own them ([handleMailtoIntent] in ComposeHelper, [handleWidgetIntent]
 * in MainWidgets); this holds the calendar side and the deferred replay.
 */

internal fun MainActivity.handleCalendarIntent(intent: Intent?) {
    if (intent?.getBooleanExtra(MainActivity.EXTRA_OPEN_CALENDAR, false) == true) {
        intent.removeExtra(MainActivity.EXTRA_OPEN_CALENDAR)
        pendingOpenCalendar = true
        pendingCalendarNewEvent = intent.getBooleanExtra(MainActivity.EXTRA_NEW_EVENT, false)
        intent.removeExtra(MainActivity.EXTRA_NEW_EVENT)
        pendingCalendarEventStart = intent.getLongExtra(MainActivity.EXTRA_OPEN_EVENT_START, 0L)
        intent.removeExtra(MainActivity.EXTRA_OPEN_EVENT_START)
    }
    if (intent?.getBooleanExtra(MainActivity.EXTRA_OPEN_DRAWER, false) == true) {
        intent.removeExtra(MainActivity.EXTRA_OPEN_DRAWER)
        drawerLayout.post { drawerLayout.openDrawer(GravityCompat.START) }
    }
}

/**
 * Apply a pending calendar-widget request. Called after the mailbox/session UI is ready
 * (end of onCreate, or onNewIntent) so the calendar screen is not overwritten by the
 * session restore that runs after the intent is parsed.
 */
internal fun MainActivity.applyPendingCalendarIntent() {
    if (!pendingOpenCalendar) return
    pendingOpenCalendar = false
    val newEvent = pendingCalendarNewEvent
    val eventStart = pendingCalendarEventStart
    pendingCalendarNewEvent = false
    pendingCalendarEventStart = 0L
    showCalendarScreen()
    calendarPanelView?.post {
        val panel = calendarPanelView ?: return@post
        when {
            newEvent -> { panel.goToWeekOf(System.currentTimeMillis()); panel.startNewEvent() }
            eventStart > 0L -> panel.goToWeekOf(eventStart)
            else -> panel.goToWeekOf(System.currentTimeMillis())
        }
    }
}
