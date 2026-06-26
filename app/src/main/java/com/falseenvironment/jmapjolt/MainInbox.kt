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

internal fun MainActivity.setupAdapters() {
    emailAdapter = EmailAdapter(this)
    val layoutManager = LinearLayoutManager(this)
    emailsRecyclerView.layoutManager = layoutManager
    emailsRecyclerView.adapter = emailAdapter
    attachMailSwipe()
    setupInfiniteScroll(layoutManager)
    setupSelectionBarListeners()
}

internal fun MainActivity.setupInfiniteScroll(layoutManager: LinearLayoutManager) {
    emailsRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (dy <= 0 || isLoadingMore) return
            // Only paginate when the current page is full — a short page means
            // we already have every email the folder holds.
            if (emails.size < emailLimit) return

            val lastVisible = layoutManager.findLastVisibleItemPosition()
            if (lastVisible >= emails.size - MainActivity.LOAD_MORE_THRESHOLD) {
                isLoadingMore = true
                emailLimit += JMapClient.DEFAULT_EMAIL_LIMIT
                refreshInboxNow()
            }
        }
    })
}

internal fun MainActivity.attachLabelDrag() {
    if (labelDragHelper != null) return
    val rv = navigationView.getChildAt(0) as? RecyclerView ?: return

    fun itemIdOf(vh: RecyclerView.ViewHolder): Int? {
        val itemView = vh.itemView as? androidx.appcompat.view.menu.MenuView.ItemView ?: return null
        return (itemView.itemData as? MenuItem)?.itemId
    }

    val callback = object : ItemTouchHelper.Callback() {
        override fun isLongPressDragEnabled() = true
        override fun isItemViewSwipeEnabled() = false

        override fun getMovementFlags(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder
        ): Int {
            val id = itemIdOf(viewHolder) ?: return 0
            return if (labelNavIds.containsKey(id))
                makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
            else 0
        }

        override fun canDropOver(
            recyclerView: RecyclerView,
            current: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            val id = itemIdOf(target) ?: return false
            return labelNavIds.containsKey(id)
        }

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            val fromKw = itemIdOf(viewHolder)?.let { labelNavIds[it] } ?: return false
            val toKw = itemIdOf(target)?.let { labelNavIds[it] } ?: return false
            val from = labels.indexOfFirst { it.keyword == fromKw }
            val to = labels.indexOfFirst { it.keyword == toKw }
            if (from < 0 || to < 0 || from == to) return false
            labels.add(to, labels.removeAt(from))
            // Don't save on every step – only on drop (clearView).
            recyclerView.adapter?.notifyItemMoved(
                viewHolder.bindingAdapterPosition, target.bindingAdapterPosition
            )
            return true
        }

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                viewHolder?.itemView?.performHapticFeedback(
                    android.view.HapticFeedbackConstants.LONG_PRESS
                )
                viewHolder?.itemView?.alpha = 0.7f
            }
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            viewHolder.itemView.alpha = 1f
            // Persist new order and immediately re-sync the drawer menu (no post{}
            // so there is no visible delay between releasing the drag and the menu updating).
            saveLabels()
            rebuildDrawerMenu()
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
    }
    labelDragHelper = ItemTouchHelper(callback).also { it.attachToRecyclerView(rv) }
}

internal fun MainActivity.attachMailSwipe() {
    val activity = this
    val callback =
            object :
                    ItemTouchHelper.SimpleCallback(
                            0,
                            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
                    ) {
                private val paint = Paint()

                override fun onMove(
                        rv: RecyclerView,
                        vh: RecyclerView.ViewHolder,
                        target: RecyclerView.ViewHolder
                ) = false

                override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float {
                    return 0.35f
                }

                override fun getSwipeEscapeVelocity(defaultValue: Float): Float {
                    return Float.MAX_VALUE  // disabilita swipe da velocità — richiede rilascio dito
                }

                // While a row is being swiped horizontally, the pull-to-refresh
                // spinner must not appear at all.
                override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                    super.onSelectedChanged(viewHolder, actionState)
                    if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                        mailSwipeRefresh.isEnabled = false
                    }
                }

                override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                    super.clearView(rv, vh)
                    mailSwipeRefresh.isEnabled = true
                }

                override fun onChildDraw(
                        c: Canvas,
                        rv: RecyclerView,
                        vh: RecyclerView.ViewHolder,
                        dX: Float,
                        dY: Float,
                        state: Int,
                        active: Boolean
                ) {
                    val view = vh.itemView
                    val width = view.width.toFloat()
                    // Apply spring damping beyond 30% of item width
                    val maxSwipeDistance = width * 0.30f
                    val cappedDX = if (dX > 0) {
                        if (dX <= maxSwipeDistance) dX else maxSwipeDistance + (dX - maxSwipeDistance) * 0.2f
                    } else {
                        if (dX >= -maxSwipeDistance) dX else -maxSwipeDistance + (dX + maxSwipeDistance) * 0.2f
                    }

                    if (cappedDX != 0f) {
                        val action = if (cappedDX > 0) getRightSwipeAction() else getLeftSwipeAction()
                        val (colorRes, iconRes) = when (action) {
                            MainActivity.SwipeAction.DELETE -> Pair("#D32F2F".toColorInt(), R.drawable.ic_lucide_trash)
                            MainActivity.SwipeAction.ARCHIVE -> Pair("#388E3C".toColorInt(), R.drawable.ic_lucide_archive)
                            MainActivity.SwipeAction.MARK_READ -> Pair("#3D8BFD".toColorInt(), R.drawable.ic_lucide_eye)
                            MainActivity.SwipeAction.MARK_SPAM -> Pair("#F57C00".toColorInt(), R.drawable.ic_lucide_ban)
                        }
                        paint.color = colorRes

                        val itemHeight = view.bottom - view.top
                        val icon = ContextCompat.getDrawable(activity, iconRes)?.mutate()
                        icon?.setTint(Color.WHITE)
                        val intrinsicWidth = icon?.intrinsicWidth ?: 0
                        val intrinsicHeight = icon?.intrinsicHeight ?: 0

                        // Clip everything to the revealed strip: the icon stays
                        // "behind" the row and is uncovered progressively instead
                        // of popping in/out at a pixel threshold.
                        val iconTop = view.top + (itemHeight - intrinsicHeight) / 2
                        val iconBottom = iconTop + intrinsicHeight
                        c.save()
                        if (cappedDX > 0) {
                            c.clipRect(
                                    view.left.toFloat(),
                                    view.top.toFloat(),
                                    view.left + cappedDX,
                                    view.bottom.toFloat()
                            )
                            c.drawColor(colorRes)
                            val iconLeft = view.left + 48
                            icon?.setBounds(iconLeft, iconTop, iconLeft + intrinsicWidth, iconBottom)
                        } else {
                            c.clipRect(
                                    view.right + cappedDX,
                                    view.top.toFloat(),
                                    view.right.toFloat(),
                                    view.bottom.toFloat()
                            )
                            c.drawColor(colorRes)
                            val iconRight = view.right - 48
                            icon?.setBounds(iconRight - intrinsicWidth, iconTop, iconRight, iconBottom)
                        }
                        icon?.draw(c)
                        c.restore()
                    }
                    super.onChildDraw(c, rv, vh, cappedDX, dY, state, active)
                }

                override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                    val position = vh.adapterPosition
                    if (position !in emails.indices) return
                    val item = emails[position]
                    val action =
                            if (direction == ItemTouchHelper.RIGHT) getRightSwipeAction()
                            else getLeftSwipeAction()

                    val account = resolveAccountFor(item) ?: connectedAccount ?: return

                    // Drafts cannot be marked read or archived; cancel the swipe.
                    if (selectedFolder == R.id.nav_drafts &&
                        (action == MainActivity.SwipeAction.MARK_READ || action == MainActivity.SwipeAction.ARCHIVE)) {
                        emailAdapter.notifyItemChanged(position)
                        Snackbar.make(drawerLayout, "Not available for drafts", Snackbar.LENGTH_SHORT).show()
                        return
                    }

                    // Deleting from Trash is permanent: confirm first, restoring the row meanwhile.
                    if (selectedFolder == R.id.nav_trash && action == MainActivity.SwipeAction.DELETE) {
                        emailAdapter.notifyItemChanged(position)
                        confirmPermanentDelete(account, listOf(item.id))
                        return
                    }

                    // Archiving from the Favourites view keeps the email flagged, so it
                    // stays visible there (an email can be both favourited and archived).
                    // Snap the row back instead of removing it.
                    if (selectedFolder == R.id.nav_favourite && action == MainActivity.SwipeAction.ARCHIVE) {
                        emailAdapter.notifyItemChanged(position)
                        Snackbar.make(drawerLayout, "Archived", Snackbar.LENGTH_SHORT).show()
                        lifecycleScope.launch {
                            try {
                                val archiveId = jmapClient.resolveMailboxIdByRole(account, "archive")
                                if (archiveId != null) jmapClient.setMailbox(account, item.id, archiveId)
                            } catch (e: Exception) {
                                Log.e(MainActivity.TAG,"Archive from favourites failed", e)
                            }
                        }
                        return
                    }

                    // 1. Optimistic local UI update
                    when (action) {
                        MainActivity.SwipeAction.DELETE, MainActivity.SwipeAction.ARCHIVE, MainActivity.SwipeAction.MARK_SPAM -> {
                            emails.removeAt(position)
                            emailAdapter.notifyItemRemoved(position)
                            emptyStateView.visibility = if (emails.isEmpty()) View.VISIBLE else View.GONE
                            emailsRecyclerView.visibility = if (emails.isEmpty()) View.GONE else View.VISIBLE
                            val targetNavId = when (action) {
                                MainActivity.SwipeAction.DELETE -> R.id.nav_trash
                                MainActivity.SwipeAction.ARCHIVE -> R.id.nav_archive
                                else -> -1 // MARK_SPAM: removals only
                            }
                            updateFolderCachesForMove(item, targetNavId)
                            saveEmailCache()
                        }
                        MainActivity.SwipeAction.MARK_READ -> {
                            item.seen = !item.seen
                            emailAdapter.notifyItemChanged(position)
                            saveEmailCache()
                        }
                    }

                    // 2. Asynchronous JMAP server update
                    lifecycleScope.launch {
                        try {
                            when (action) {
                                MainActivity.SwipeAction.DELETE -> {
                                    val trashId = jmapClient.resolveMailboxIdByRole(account, "trash")
                                    if (trashId != null) {
                                        jmapClient.setMailbox(account, item.id, trashId)
                                    }
                                }
                                MainActivity.SwipeAction.ARCHIVE -> {
                                    val archiveId = jmapClient.resolveMailboxIdByRole(account, "archive")
                                    if (archiveId != null) {
                                        jmapClient.setMailbox(account, item.id, archiveId)
                                    }
                                }
                                MainActivity.SwipeAction.MARK_READ -> {
                                    jmapClient.setSeen(account, item.id, item.seen)
                                }
                                MainActivity.SwipeAction.MARK_SPAM -> {
                                    val spamId = jmapClient.resolveMailboxIdByRole(account, "spam")
                                    if (spamId != null) {
                                        jmapClient.setMailbox(account, item.id, spamId)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(MainActivity.TAG,"Failed to perform optimistic swipe action $action on server", e)
                        }
                    }
                }
            }
    ItemTouchHelper(callback).attachToRecyclerView(emailsRecyclerView)
}

internal fun MainActivity.moveCategory(from: Int, to: Int) {
    if (to !in categoryOrder.indices) return
    val item = categoryOrder.removeAt(from)
    categoryOrder.add(to, item)
}

internal fun MainActivity.loadCategoryPreferences() {
    val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
    val savedOrder = prefs.getString(MainActivity.KEY_CATEGORY_ORDER, null)
    if (!savedOrder.isNullOrBlank()) {
        val parsed = savedOrder.split(",").mapNotNull { it.toIntOrNull() }
        if (parsed.size == categoryOrder.size && parsed.containsAll(categoryOrder)) {
            categoryOrder.clear()
            categoryOrder.addAll(parsed)
        }
    }
    categoryOrder.forEach { id ->
        val key = "category_name_$id"
        val saved = prefs.getString(key, null)
        if (!saved.isNullOrBlank()) {
            categoryNames[id] = saved
        } else {
            categoryNames[id] = getDefaultCategoryTitle(id)
        }
    }
}

internal fun MainActivity.saveCategoryPreferences() {
    val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
    val editor = prefs.edit()
    editor.putString(MainActivity.KEY_CATEGORY_ORDER, categoryOrder.joinToString(","))
    categoryOrder.forEach { id -> editor.putString("category_name_$id", categoryNames[id]) }
    editor.putString(MainActivity.KEY_SWIPE_RIGHT_ACTION, getRightSwipeAction().name)
    editor.putString(MainActivity.KEY_SWIPE_LEFT_ACTION, getLeftSwipeAction().name)
    editor.apply()
}

internal fun MainActivity.updateEmailsList(rawList: List<DisplayEmail>) {
    // A fetch landed: allow the next scroll-triggered page load.
    isLoadingMore = false
    // Stable adapter ids derive from email ids: a duplicate id in the list
    // (e.g. multi-account label sync merging overlapping results) crashes
    // RecyclerView with "Called attach on a child which is not detached".
    val newList = rawList.distinctBy { it.id }
    val folderChanged = prevUpdateFolder != selectedFolder
    prevUpdateFolder = selectedFolder

    // Threaded view is what the adapter renders; baseEmails keeps the full flat
    // list for search. The diff compares old vs new *threaded* lists so it stays
    // consistent with the adapter's backing data.
    val display = buildThreadedView(newList)

    val diffResult = if (!folderChanged) {
        androidx.recyclerview.widget.DiffUtil.calculateDiff(
                object : androidx.recyclerview.widget.DiffUtil.Callback() {
                    override fun getOldListSize(): Int = emails.size
                    override fun getNewListSize(): Int = display.size
                    override fun areItemsTheSame(
                            oldItemPosition: Int,
                            newItemPosition: Int
                    ): Boolean {
                        return emails[oldItemPosition].id == display[newItemPosition].id
                    }
                    override fun areContentsTheSame(
                            oldItemPosition: Int,
                            newItemPosition: Int
                    ): Boolean {
                        val a = emails[oldItemPosition]
                        val b = display[newItemPosition]
                        return a.seen == b.seen &&
                                a.isFavorite == b.isFavorite &&
                                a.labels == b.labels &&
                                a.preview == b.preview &&
                                a.subject == b.subject &&
                                a.from == b.from &&
                                a.isThreadHeadRow == b.isThreadHeadRow &&
                                a.isThreadChildRow == b.isThreadChildRow &&
                                a.isThreadMoreRow == b.isThreadMoreRow &&
                                a.threadHiddenCount == b.threadHiddenCount &&
                                a.threadCount == b.threadCount
                    }
                }
        )
    } else null

    val firstChanged = emails.firstOrNull()?.id != display.firstOrNull()?.id
    baseEmails.clear()
    baseEmails.addAll(newList)
    emails.clear()
    emails.addAll(display)
    if (diffResult != null) diffResult.dispatchUpdatesTo(emailAdapter)
    else emailAdapter.notifyDataSetChanged()

    if (firstChanged && !isSearchActive) {
        emailsRecyclerView.post { emailsRecyclerView.scrollToPosition(0) }
    }

    saveEmailCache()

    emptyStateView.visibility = if (emails.isEmpty()) View.VISIBLE else View.GONE
    emailsRecyclerView.visibility = if (emails.isEmpty()) View.GONE else View.VISIBLE

    if (pendingMailboxShow) {
        pendingMailboxShow = false
        showMailboxScreen(skipRefresh = true)
        loadingOverlay.animate()
            .alpha(0f)
            .setDuration(350)
            .withEndAction {
                loadingOverlay.visibility = View.GONE
                loadingOverlay.alpha = 1f
            }
            .start()
    }

    tryOpenPendingWidgetEmail()
}

internal fun MainActivity.applyFolderFilterAndRefresh() {
    // New folder starts at the first page again.
    emailLimit = JMapClient.DEFAULT_EMAIL_LIMIT
    isLoadingMore = false
    val folderTitle = getCurrentMailboxTitle()
    supportActionBar?.title = folderTitle
    updateCustomTopBar(folderTitle, inMailbox = true)

    val cached = folderCache[selectedFolder]
    if (cached != null) {
        updateEmailsList(cached)
    } else {
        emails.clear()
        emailAdapter.notifyDataSetChanged()
        emptyStateView.visibility = View.GONE
        emailsRecyclerView.visibility = View.GONE
        // Show the persisted offline snapshot immediately (works with no network);
        // the periodic sync below refreshes it once the network responds.
        loadOfflineCache(selectedFolder)
    }

    startPeriodicSync()
}

internal fun MainActivity.cacheBucket(folderId: Int): String? {
    val scope = if (folderId == R.id.nav_unified_inbox) "unified"
        else connectedAccount?.email ?: return null
    return com.falseenvironment.jmapjolt.cache.EmailCacheStore.bucket(scope, folderId)
}

internal fun MainActivity.loadOfflineCache(folderId: Int) {
    val activity = this
    val bucket = cacheBucket(folderId) ?: return
    lifecycleScope.launch {
        val cached = runCatching {
            com.falseenvironment.jmapjolt.cache.EmailCacheStore.load(activity, bucket)
        }.getOrDefault(emptyList())
        // Skip if the user already switched folders or the network beat us to it.
        if (cached.isEmpty() || selectedFolder != folderId || emails.isNotEmpty()) return@launch
        folderCache[folderId] = cached
        updateEmailsList(cached)
    }
}

internal fun MainActivity.persistOfflineCache(folderId: Int, list: List<DisplayEmail>) {
    val activity = this
    val bucket = cacheBucket(folderId) ?: return
    lifecycleScope.launch {
        runCatching {
            com.falseenvironment.jmapjolt.cache.EmailCacheStore.save(activity, bucket, list)
        }
    }
}

internal fun MainActivity.getFolderRole(navId: Int): String? =
    when (navId) {
        R.id.nav_sent -> "sent"
        R.id.nav_drafts -> "drafts"
        R.id.nav_spam -> "junk"
        R.id.nav_trash -> "trash"
        R.id.nav_archive -> "archive"
        else -> null
    }

internal fun MainActivity.toggleSelection(id: String) {
    if (selectedEmails.contains(id)) {
        selectedEmails.remove(id)
    } else {
        selectedEmails.add(id)
    }
    updateSelectionBar()
    val pos = emails.indexOfFirst { it.id == id }
    if (pos >= 0) emailAdapter.notifyItemChanged(pos) else emailAdapter.notifyDataSetChanged()
}

internal fun MainActivity.updateSelectionBar() {
    if (selectedEmails.isEmpty()) {
        searchBarContainer.visibility = View.VISIBLE
        selectionBarContainer.visibility = View.GONE
    } else {
        searchBarContainer.visibility = View.GONE
        selectionBarContainer.visibility = View.VISIBLE
        selectionCountText.text = "${selectedEmails.size} selected"
        val allSeen = selectedEmails.all { id -> emails.find { it.id == id }?.seen == true }
        selectionReadBtn.contentDescription = if (allSeen) "Mark Unread" else "Mark Read"
        // In Archive the action button restores the email to the Inbox instead.
        if (selectedFolder == R.id.nav_archive) {
            selectionArchiveBtn.setImageResource(R.drawable.ic_lucide_archive_restore)
            selectionArchiveBtn.contentDescription = "Move to Inbox"
        } else {
            selectionArchiveBtn.setImageResource(R.drawable.ic_lucide_archive)
            selectionArchiveBtn.contentDescription = "Archive"
        }
    }
}

internal fun MainActivity.setupSelectionBarListeners() {
    selectionCloseBtn.setOnClickListener { clearSelection() }
    selectionArchiveBtn.setOnClickListener {
        performAction(if (selectedFolder == R.id.nav_archive) "unarchive" else "archive")
    }
    selectionDeleteBtn.setOnClickListener { performAction("delete") }
    selectionReadBtn.setOnClickListener { performAction("toggleRead") }
    selectionMoreBtn.setOnClickListener { performAction("more") }

    searchBarMenuIcon.setOnClickListener {
        if (drawerToggle.isDrawerIndicatorEnabled) drawerLayout.openDrawer(GravityCompat.START)
        else handleNavigationClick()
    }

    searchBarTitle.setOnClickListener { activateSearch() }

    searchInput.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            val query = s?.toString() ?: ""
            searchClearBtn.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
            applySearchFilter(query)
        }
        override fun afterTextChanged(s: android.text.Editable?) {}
    })

    searchClearBtn.setOnClickListener { deactivateSearch() }
}

internal fun MainActivity.clearSelection() {
    val positions = selectedEmails.mapNotNull { id ->
        emails.indexOfFirst { it.id == id }.takeIf { it >= 0 }
    }
    selectedEmails.clear()
    updateSelectionBar()
    positions.forEach { emailAdapter.notifyItemChanged(it) }
}

internal fun MainActivity.removeEmailsAnimated(ids: Collection<String>) {
    val idSet = ids.toSet()
    for (i in emails.indices.reversed()) {
        if (emails[i].id in idSet) {
            emails.removeAt(i)
            emailAdapter.notifyItemRemoved(i)
        }
    }
    baseEmails.removeAll { it.id in idSet }
    emptyStateView.visibility = if (emails.isEmpty()) View.VISIBLE else View.GONE
    emailsRecyclerView.visibility = if (emails.isEmpty()) View.GONE else View.VISIBLE
}

internal fun MainActivity.applyOptimisticFavorite(
    list: List<DisplayEmail>,
    isFavFolder: Boolean
): List<DisplayEmail> {
    if (optimisticFavorite.isEmpty()) return list
    // Drop overrides the server has already caught up with.
    list.forEach { e -> if (optimisticFavorite[e.id] == e.isFavorite) optimisticFavorite.remove(e.id) }
    if (isFavFolder) {
        val idsInList = list.map { it.id }.toSet()
        optimisticFavorite.entries.removeAll { (id, fav) -> !fav && id !in idsInList }
    }
    if (optimisticFavorite.isEmpty()) return list
    var result = list.map { e ->
        val ov = optimisticFavorite[e.id]
        if (ov != null && ov != e.isFavorite) e.copy(isFavorite = ov) else e
    }
    if (isFavFolder) result = result.filter { optimisticFavorite[it.id] != false }
    return result
}

