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

internal fun MainActivity.threadKeyOf(e: DisplayEmail): String {
    // Only reply/forward messages collapse into a subject-based conversation; plain
    // mails keep their own thread (server threadId) so unrelated same-subject mails
    // are never lumped together.
    if (hasReplyForwardPrefix(e.subject)) {
        val norm = normalizeSubject(e.subject)
        if (norm.isNotBlank()) return "__subj_$norm"
    }
    return if (e.threadId.isNotBlank()) e.threadId else "__single_${e.id}"
}

internal fun MainActivity.hasReplyForwardPrefix(subject: String): Boolean =
    replyForwardPrefix.containsMatchIn(subject.trim())

internal fun MainActivity.normalizeSubject(subject: String): String {
    var s = subject.trim()
    while (true) {
        val stripped = s.replaceFirst(replyForwardPrefix, "")
        if (stripped == s) break
        s = stripped
    }
    return s.trim().lowercase().replace(Regex("\\s+"), " ")
}

internal fun MainActivity.isThreadExpanded(key: String): Boolean = expandedThreads.contains(key)

internal fun MainActivity.buildThreadedView(full: List<DisplayEmail>): List<DisplayEmail> {
    threadMembers.clear()
    val groups = LinkedHashMap<String, MutableList<DisplayEmail>>()
    for (e in full) groups.getOrPut(threadKeyOf(e)) { mutableListOf() }.add(e)
    val out = ArrayList<DisplayEmail>(full.size)
    for ((key, members) in groups) {
        threadMembers[key] = members
        val multi = members.size > 1
        val head = members.first()
        head.threadKey = key
        head.threadCount = members.size
        head.isThreadHeadRow = multi
        head.isThreadChildRow = false
        head.isThreadMoreRow = false
        out.add(head)
        if (multi && expandedThreads.contains(key)) {
            val childCount = members.size - 1
            val limit = (threadChildLimit[key] ?: MainActivity.THREAD_PAGE).coerceAtMost(childCount)
            for (i in 1..limit) {
                val child = members[i]
                child.threadKey = key
                child.threadCount = members.size
                child.isThreadHeadRow = false
                child.isThreadChildRow = true
                child.isThreadMoreRow = false
                out.add(child)
            }
            if (limit < childCount) {
                // Synthetic "+N more" row: reveals the next MainActivity.THREAD_PAGE on tap.
                val more = head.copy(id = "__more_$key").apply {
                    threadKey = key
                    threadCount = members.size
                    isThreadHeadRow = false
                    isThreadChildRow = false
                    isThreadMoreRow = true
                    threadHiddenCount = childCount - limit
                }
                out.add(more)
            }
        }
    }
    return out
}

internal fun MainActivity.toggleThread(key: String) {
    if (!expandedThreads.add(key)) expandedThreads.remove(key)
    // Reopening a conversation always starts from the first page again.
    threadChildLimit[key] = MainActivity.THREAD_PAGE
    rebuildThreadedList()
}

internal fun MainActivity.showMoreThread(key: String) {
    threadChildLimit[key] = (threadChildLimit[key] ?: MainActivity.THREAD_PAGE) + MainActivity.THREAD_PAGE
    rebuildThreadedList()
}

internal fun MainActivity.rebuildThreadedList() {
    val display = buildThreadedView(baseEmails)
    emails.clear()
    emails.addAll(display)
    emailAdapter.notifyDataSetChanged()
}

