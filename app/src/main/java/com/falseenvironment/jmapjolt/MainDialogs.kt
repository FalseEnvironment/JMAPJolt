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
 * Themed dialogs and menus built in code: the confirm/link/move-label dialogs and
 * the overflow popup. All of them paint through the theme tokens rather than the
 * platform dialog styles, which is why they live here instead of in XML.
 */

internal fun MainActivity.showThemedConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    isDangerous: Boolean = false,
    onConfirm: () -> Unit
) {
    val dp = resources.displayMetrics.density
    val dialogBg = getDialogBackgroundColor()
    val textColor = if (currentTheme == "light") "#212121".toColorInt() else Color.WHITE
    val secondaryColor = if (currentTheme == "light") "#757575".toColorInt() else "#9E9E9E".toColorInt()
    val confirmColor = if (isDangerous) "#EF5350".toColorInt() else currentAccentColor.toColorInt()

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

    if (title.isNotBlank()) {
        root.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(textColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (8 * dp).toInt() }
        })
    }

    root.addView(TextView(this).apply {
        text = message
        textSize = 14f
        setTextColor(secondaryColor)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = (20 * dp).toInt() }
    })

    val btnRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }
    root.addView(btnRow)

    val dialog = AlertDialog.Builder(this).setView(root).create()

    btnRow.addView(TextView(this).apply {
        text = "Cancel"
        textSize = 14f
        setTextColor(secondaryColor)
        setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
        isClickable = true; isFocusable = true
        setOnClickListener { dialog.dismiss() }
    })
    btnRow.addView(TextView(this).apply {
        text = confirmLabel
        textSize = 14f
        setTextColor(confirmColor)
        setTypeface(null, android.graphics.Typeface.BOLD)
        setPadding((16 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt())
        isClickable = true; isFocusable = true
        setOnClickListener { dialog.dismiss(); onConfirm() }
    })

    dialog.show()
    dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
    dialog.window?.attributes?.let { lp ->
        lp.width = (resources.displayMetrics.widthPixels * 0.88f).toInt()
        dialog.window?.attributes = lp
    }
}

internal fun MainActivity.showLinkConfirmationDialog(url: String) {
    val dp = resources.displayMetrics.density
    val dialogBg = getDialogBackgroundColor()
    val accentInt = currentAccentColor.toColorInt()
    val textColor = tokens.textPrimary
    val secondaryColor = tokens.textSecondary

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
        text = "Open Link?"
        textSize = 18f
        setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(textColor)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = (10 * dp).toInt() }
    })

    root.addView(TextView(this).apply {
        text = url
        textSize = 12f
        setTextColor(secondaryColor)
        maxLines = 4
        ellipsize = android.text.TextUtils.TruncateAt.END
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = (20 * dp).toInt() }
    })

    val btnRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }
    root.addView(btnRow)

    val dialog = AlertDialog.Builder(this).setView(root).create()

    btnRow.addView(TextView(this).apply {
        text = "Cancel"
        textSize = 14f
        setTextColor(secondaryColor)
        setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
        isClickable = true; isFocusable = true
        setOnClickListener { dialog.dismiss() }
    })
    btnRow.addView(TextView(this).apply {
        text = "Open"
        textSize = 14f
        setTextColor(accentInt)
        setTypeface(null, android.graphics.Typeface.BOLD)
        setPadding((16 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt())
        isClickable = true; isFocusable = true
        setOnClickListener {
            dialog.dismiss()
            try {
                val uri = Uri.parse(url)
                val scheme = uri.scheme?.lowercase()
                if (scheme == "https" || scheme == "http" || scheme == "mailto") {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                } else {
                    android.widget.Toast.makeText(this@showLinkConfirmationDialog, "Cannot open link", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                android.widget.Toast.makeText(this@showLinkConfirmationDialog, "Cannot open link", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    })

    dialog.show()
    dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
    dialog.window?.attributes?.let { lp ->
        lp.width = (resources.displayMetrics.widthPixels * 0.88f).toInt()
        dialog.window?.attributes = lp
    }
}

internal fun MainActivity.showMoveLabelPicker(
    mailboxes: List<JMapClient.MailboxInfo>,
    ids: List<String>,
    mode: androidx.appcompat.view.ActionMode?,
    disabledRoles: Set<String> = emptySet(),
    onPicked: (() -> Unit)? = null
) {
    val account = connectedAccount ?: return
    val dp = resources.displayMetrics.density
    val bgColor = getDialogBackgroundColor()
    val textColor = if (currentTheme == "light") "#212121".toColorInt() else Color.WHITE
    val secondaryColor = if (currentTheme == "light") "#757575".toColorInt() else "#BDBDBD".toColorInt()
    val accentInt = currentAccentColor.toColorInt()

    val outer = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16 * dp
            setColor(bgColor)
        }
        elevation = 8 * dp
    }

    // Title row
    outer.addView(TextView(this).apply {
        text = "Move to"
        textSize = 13f
        setTextColor(secondaryColor)
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.08f
        setPadding((20 * dp).toInt(), (16 * dp).toInt(), (20 * dp).toInt(), (8 * dp).toInt())
    })

    outer.addView(View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        setBackgroundColor(0x22FFFFFF)
    })

    val scroll = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            minOf(mailboxes.size, 6) * (52 * dp).toInt()
        )
    }
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    var dialog: AlertDialog? = null

    // System folders first (have a role), then user-created folders (role == null), sorted by name.
    val sortedMailboxes = mailboxes.sortedWith(compareBy({ it.role == null }, { it.name }))
    sortedMailboxes.forEach { mbox ->
        val iconRes = when (mbox.role?.lowercase()) {
            "inbox" -> R.drawable.ic_lucide_inbox
            "archive" -> R.drawable.ic_lucide_archive
            "sent" -> R.drawable.ic_lucide_send
            "junk", "spam" -> R.drawable.ic_lucide_ban
            "starred", "flagged" -> R.drawable.ic_lucide_star
            null -> R.drawable.ic_lucide_folder_input
            else -> R.drawable.ic_lucide_tag
        }
        val isDisabled = mbox.role?.lowercase() in disabledRoles
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (52 * dp).toInt()
            )
            setPadding((20 * dp).toInt(), 0, (20 * dp).toInt(), 0)
            isClickable = !isDisabled
            isFocusable = !isDisabled
            alpha = if (isDisabled) 0.4f else 1f
            if (!isDisabled) {
                background = ContextCompat.getDrawable(
                    this@showMoveLabelPicker,
                    android.util.TypedValue().also {
                        theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
                    }.resourceId
                )
                setOnClickListener {
                    dialog?.dismiss()
                    val mailboxId = mbox.id
                    val mboxRole = mbox.role?.lowercase()
                    // Capture account+role mapping BEFORE removeEmailsAnimated wipes
                    // the lists. The picker shows the active account's mailboxes, so the
                    // cached mailboxId only applies to same-account moves; cross-account
                    // moves must re-resolve the target mailbox by role on the email's own
                    // account (a foreign mailboxId would silently fail on the server).
                    val accByIdForMove = emails.filter { it.id in ids }
                        .associate { it.id to (resolveAccountFor(it) ?: account) }
                    mode?.finish()
                    clearSelection()
                    onPicked?.invoke()
                    removeEmailsAnimated(ids)
                    // Hold the move until the server confirms it. The destination is
                    // resolved to its nav id so the rows stay visible in the folder they
                    // were moved into, and hidden everywhere else.
                    PendingMutations.markMoved(ids, navIdForMailbox(mbox))
                    saveEmailCache()
                    showThemedSnackbar("Moved to ${folderDisplayName(mbox)}")
                    lifecycleScope.launch {
                        try {
                            ids.forEach { id ->
                                val acc = accByIdForMove[id] ?: account
                                val sameAccount = acc.email.equals(account.email, ignoreCase = true)
                                val targetId = when {
                                    mboxRole == "archive" -> resolveOrCreateArchive(acc)
                                    mboxRole != null -> resolveMailboxIdByRole(acc, mboxRole)
                                    sameAccount -> mailboxId
                                    else -> null // custom folder: no cross-account mapping
                                } ?: return@forEach
                                jmapClient.setMailbox(acc, id, targetId)
                                if (mboxRole == "inbox") {
                                    BackgroundEmailSyncReceiver.addToBaseline(this@showMoveLabelPicker, acc.email, listOf(id))
                                }
                            }
                        }
                        catch (e: Exception) {
                            PendingMutations.forget(ids)
                            Log.e(MainActivity.TAG, "Failed label move", e)
                        }
                    }
                }
            }
        }
        row.addView(ImageView(this).apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(accentInt)
            val sz = (20 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz).also { it.marginEnd = (16 * dp).toInt() }
            scaleType = ImageView.ScaleType.FIT_CENTER
        })
        row.addView(TextView(this).apply {
            text = folderDisplayName(mbox)
            textSize = 15f
            setTextColor(textColor)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        list.addView(row)
    }

    scroll.addView(list)
    outer.addView(scroll)

    dialog = AlertDialog.Builder(this)
        .setView(outer)
        .create()
    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    dialog.show()
}

internal fun MainActivity.showMoreOptionsPopup(mode: androidx.appcompat.view.ActionMode?) {
    val account = connectedAccount ?: return
    val dp = resources.displayMetrics.density
    val ids = selectedEmails.toList()
    val allFavorites = ids.isNotEmpty() && ids.all { id -> emails.find { it.id == id }?.isFavorite == true }
    val darker = darkenColor(currentAccentColor.toColorInt())

    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 8 * dp
            setColor(darker)
        }
        val vp = (4 * dp).toInt()
        setPadding(0, vp, 0, vp)
        elevation = 8 * dp
    }

    var popupRef: android.widget.PopupWindow? = null

    fun row(label: String, iconRes: Int, action: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                (200 * dp).toInt(), (48 * dp).toInt()
            )
            val hp = (16 * dp).toInt()
            setPadding(hp, 0, hp, 0)
            addView(ImageView(this@showMoreOptionsPopup).apply {
                setImageResource(iconRes)
                imageTintList = ColorStateList.valueOf(Color.WHITE)
                val sz = (18 * dp).toInt()
                layoutParams = LinearLayout.LayoutParams(sz, sz).also { it.marginEnd = (12 * dp).toInt() }
            })
            addView(TextView(this@showMoreOptionsPopup).apply {
                text = label; textSize = 14f; setTextColor(Color.WHITE)
            })
            setOnClickListener { popupRef?.dismiss(); action() }
        }

    // Sent appears as a move target only outside the Sent folder and only when the
    // selection involves mail this account actually sent. It is greyed out when the
    // selection mixes sent and received mail (received mail can't be filed as Sent).
    val isSentBy = { id: String ->
        val em = emails.find { it.id == id }
        val emAccount = if (em != null) resolveAccountFor(em) else null
        em?.fromEmail.equals((emAccount ?: account).email, ignoreCase = true)
    }
    val anySent = ids.any(isSentBy)
    val allSent = ids.all(isSentBy)
    val includeSent = selectedFolder != R.id.nav_sent && selectedFolder != R.id.nav_unified_inbox && anySent
    container.addView(row("Move to", R.drawable.ic_lucide_folder_input) {
        val excludedRoles = buildList {
            add("drafts")
            add("trash")
            if (!includeSent) add("sent")
            // Already in (unified) inbox: "Move to Inbox" is a no-op, hide it.
            if (selectedFolder == R.id.nav_inbox || selectedFolder == R.id.nav_unified_inbox) add("inbox")
        }
        val disabledRoles = if (allSent) emptySet() else setOf("sent")
        fun present(mailboxes: List<JMapClient.MailboxInfo>) {
            val filtered = mailboxes.filter { it.role?.lowercase() !in excludedRoles }
            if (filtered.isNotEmpty()) showMoveLabelPicker(filtered, ids, mode, disabledRoles)
        }
        val resolvedAccount = ids.firstOrNull()?.let { resolveAccountForId(it) } ?: account
        val cached = mailboxCache
        if (cached != null) {
            // Instant: show from cache, refresh in the background for next time.
            present(cached)
            lifecycleScope.launch {
                runCatching { jmapClient.fetchMailboxes(resolvedAccount) }.getOrNull()?.let { mailboxCache = it }
            }
        } else {
            lifecycleScope.launch {
                val mailboxes = jmapClient.fetchMailboxes(resolvedAccount)
                mailboxCache = mailboxes
                present(mailboxes)
            }
        }
    })

    container.addView(View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        setBackgroundColor(0x22FFFFFF)
    })

    container.addView(row("Label", R.drawable.ic_lucide_tag) {
        mode?.finish()
        clearSelection()
        showLabelPicker(ids)
    })

    container.addView(View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        setBackgroundColor(0x22FFFFFF)
    })

    val inSpam = selectedFolder == R.id.nav_spam
    container.addView(row(if (inSpam) "Not spam" else "Spam", R.drawable.ic_lucide_ban) {
        val toSpam = !inSpam
        val movedEmails = emails.filter { it.id in ids }
        val accountsById = movedEmails.associate { it.id to (resolveAccountFor(it) ?: account) }
        mode?.finish()
        clearSelection()
        removeEmailsAnimated(ids)
        movedEmails.forEach {
            if (toSpam) updateFolderCachesForMove(it, R.id.nav_spam) else updateFolderCachesForInbox(it)
        }
        saveEmailCache()
        showThemedSnackbar(if (toSpam) "Moved to Spam" else "Moved to Inbox")
        lifecycleScope.launch {
            try {
                ids.forEach { id ->
                    val acc = accountsById[id] ?: account
                    jmapClient.setJunkKeyword(acc, id, toSpam)
                    val mailboxId = jmapClient.resolveMailboxIdByRole(acc, if (toSpam) "junk" else "inbox")
                    if (mailboxId != null) {
                        jmapClient.setMailbox(acc, id, mailboxId)
                        if (!toSpam) BackgroundEmailSyncReceiver.addToBaseline(this@showMoreOptionsPopup, acc.email, listOf(id))
                    }
                }
            } catch (e: Exception) {
                PendingMutations.forget(ids)
                Log.e(MainActivity.TAG, "Bulk spam toggle failed", e)
            }
        }
    })

    container.addView(View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        setBackgroundColor(0x22FFFFFF)
    })

    container.addView(row(
        if (allFavorites) "Remove from Favorites" else "Add to Favorites",
        R.drawable.ic_lucide_star
    ) {
        val newState = !allFavorites
        // Mirror the star-button path so the Favourites view updates instantly:
        // flag the rows, record the optimistic override, and patch the cache.
        ids.forEach { id ->
            emails.find { it.id == id }?.isFavorite = newState
            baseEmails.find { it.id == id }?.isFavorite = newState
            optimisticFavorite[id] = newState
            val source = emails.find { it.id == id } ?: baseEmails.find { it.id == id }
            if (source != null) updateFolderCachesForFavorite(source.copy(), newState)
        }
        mode?.finish()
        clearSelection()
        // Removing a favourite while viewing Favourites drops it from the list now.
        if (!newState && selectedFolder == R.id.nav_favourite) {
            // Rows were removed, positions shifted — full refresh required.
            emails.removeAll { it.id in ids }
            baseEmails.removeAll { it.id in ids }
            folderCache[R.id.nav_favourite] = emails.toList()
            emailAdapter.notifyDataSetChanged()
        } else {
            emailAdapter.notifyItemsChangedByIds(ids)
        }
        updateEmptyState()
        saveEmailCache()
        lifecycleScope.launch {
            ids.forEach { id ->
                val acc = resolveAccountForId(id) ?: account
                try { jmapClient.setFavorite(acc, id, newState) }
                catch (e: Exception) { Log.e(MainActivity.TAG, "Failed favorite toggle", e) }
            }
        }
    })

    val pw = android.widget.PopupWindow(
        container,
        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
        true
    ).also {
        it.elevation = 10 * dp
        it.isOutsideTouchable = true
    }
    popupRef = pw
    pw.showAsDropDown(toolbar, toolbar.width - (220 * dp).toInt(), 0)
}

private fun MainActivity.forceShowMenuIcons(menu: Menu) {
    try {
        val m = menu.javaClass.getDeclaredMethod("setOptionalIconsVisible", Boolean::class.java)
        m.isAccessible = true
        m.invoke(menu, true)
    } catch (_: Exception) {}
}
