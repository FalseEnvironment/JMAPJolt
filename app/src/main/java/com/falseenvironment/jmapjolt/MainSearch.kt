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

internal fun MainActivity.activateSearch() {
    // If an email is open, leave it first so the search results are actually visible.
    if (isShowingEmailDetail) closeEmailDetail()
    isSearchActive = true
    searchBarTitle.visibility = View.GONE
    searchInput.visibility = View.VISIBLE
    searchClearBtn.visibility = View.GONE
    // Scope chips: default to the current folder when it maps to a chip, else All.
    searchScope = searchScopes.firstOrNull { it.second == selectedFolder }?.second
    refreshSearchChips()
    searchChipsScroll.animate().cancel()
    searchChipsScroll.layoutParams = searchChipsScroll.layoutParams.also {
        it.height = ViewGroup.LayoutParams.WRAP_CONTENT
    }
    searchChipsScroll.visibility = View.VISIBLE
    searchChipsScroll.alpha = 0f
    searchChipsScroll.translationY = -40f * resources.displayMetrics.density
    searchChipsScroll.animate()
        .alpha(1f)
        .translationY(0f)
        .setDuration(250)
        .setInterpolator(android.view.animation.DecelerateInterpolator(2f))
        .start()
    searchInput.requestFocus()
    val imm = getSystemService(InputMethodManager::class.java)
    imm?.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
}

internal fun MainActivity.deactivateSearch() {
    isSearchActive = false
    searchInput.text.clear()
    searchInput.visibility = View.GONE
    searchBarTitle.visibility = View.VISIBLE
    searchClearBtn.visibility = View.GONE
    animateChipsBarOut()
    searchScope = null
    hideKeyboard()
    emails.clear()
    emails.addAll(baseEmails)
    emailAdapter.notifyDataSetChanged()
    emptyStateView.visibility = if (emails.isEmpty()) View.VISIBLE else View.GONE
    emailsRecyclerView.visibility = if (emails.isEmpty()) View.GONE else View.VISIBLE
}

internal fun MainActivity.animateChipsBarOut() {
    val bar = searchChipsScroll
    bar.animate().cancel()
    val startH = bar.height
    if (bar.visibility != View.VISIBLE || startH == 0) {
        bar.visibility = View.GONE
        return
    }
    val anim = android.animation.ValueAnimator.ofInt(startH, 0).setDuration(200)
    anim.interpolator = android.view.animation.AccelerateInterpolator()
    anim.addUpdateListener { va ->
        val h = va.animatedValue as Int
        bar.layoutParams = bar.layoutParams.also { it.height = h }
        bar.alpha = h.toFloat() / startH
    }
    anim.addListener(object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(a: android.animation.Animator) {
            bar.visibility = View.GONE
            bar.alpha = 1f
            bar.layoutParams = bar.layoutParams.also {
                it.height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
    })
    anim.start()
}

internal fun MainActivity.refreshSearchChips() {
    val dp = resources.displayMetrics.density
    searchChipsRow.removeAllViews()
    val accent = currentAccentColor.toColorInt()
    val isLight = currentTheme == "light"
    val tonalBg = if (isLight) "#E8E8EC".toColorInt() else "#2A2A2A".toColorInt()
    val tonalText = if (isLight) "#1A1A1A".toColorInt() else "#EBEBF0".toColorInt()
    searchScopes.forEach { (label, scope) ->
        val selected = scope == searchScope
        searchChipsRow.addView(TextView(this).apply {
            text = label
            textSize = 13f
            typeface = if (selected) android.graphics.Typeface.DEFAULT_BOLD
                       else android.graphics.Typeface.DEFAULT
            setTextColor(if (selected) getOnAccentColor() else tonalText)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 10 * dp
                setColor(if (selected) accent else tonalBg)
            }
            setPadding((14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = (8 * dp).toInt() }
            setOnClickListener {
                if (searchScope != scope) {
                    searchScope = scope
                    refreshSearchChips()
                    applySearchFilter(searchInput.text?.toString() ?: "")
                }
            }
        })
    }
}

internal fun MainActivity.searchSourceEmails(): List<DisplayEmail> {
    val scope = searchScope
    return when {
        scope == null -> {
            // All: union of every cached folder plus the current list, newest first.
            val seen = HashSet<String>()
            (folderCache.values.flatten() + baseEmails)
                .filter { seen.add(it.id) }
                .sortedByDescending { it.receivedAt }
        }
        scope == selectedFolder -> baseEmails.toList()
        else -> folderCache[scope] ?: emptyList()
    }
}

internal fun MainActivity.applySearchFilter(query: String) {
    val source = if (isSearchActive) searchSourceEmails() else baseEmails.toList()
    val filtered = if (query.isBlank()) source else source.filter {
        it.subject.contains(query, ignoreCase = true) ||
        it.from.contains(query, ignoreCase = true) ||
        it.preview.contains(query, ignoreCase = true) ||
        labelsOf(it).any { label -> label.name.contains(query, ignoreCase = true) }
    }
    emails.clear()
    emails.addAll(filtered.distinctBy { it.id })
    emailAdapter.notifyDataSetChanged()
    emptyStateView.visibility = if (emails.isEmpty()) View.VISIBLE else View.GONE
    emailsRecyclerView.visibility = if (emails.isEmpty()) View.GONE else View.VISIBLE
}

