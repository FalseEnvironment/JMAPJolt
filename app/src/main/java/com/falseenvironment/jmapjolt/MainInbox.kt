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

    // Section drag bounds are computed once when a drag starts, not per-frame —
    // recomputing from live child positions during the swap animation feeds back
    // into the clamp and causes visible jitter as neighbors are mid-transition.
    var dragMinDY = -Float.MAX_VALUE
    var dragMaxDY = Float.MAX_VALUE

    // Neighbor rows shifted out of the way during a drag, with their applied offset.
    // Animated manually via translationY: the adapter is Menu-backed, so
    // notifyItemMoved-driven animations desync RecyclerView from the Menu (the old
    // flicker/revert bug) — pure view translation stays outside the adapter entirely.
    val shiftedRows = mutableMapOf<android.view.View, Float>()
    // Net slots the dragged row has moved (as a translationY), so on drop it can be
    // parked directly in its new slot instead of sliding back to the original one.
    var draggedNetShift = 0f
    // Last clamped drag translation, captured per-frame in onChildDraw — ItemTouchHelper's
    // zero-length recovery wipes the view's translation before clearView runs, so this is
    // the only record of where the finger actually released the row.
    var lastDragDY = 0f

    val callback = object : ItemTouchHelper.Callback() {
        override fun isLongPressDragEnabled() = true
        override fun isItemViewSwipeEnabled() = false

        override fun getMovementFlags(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder
        ): Int {
            val id = itemIdOf(viewHolder) ?: return 0
            return if (labelNavIds.containsKey(id) || subfolderNavIds.containsKey(id))
                makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
            else 0
        }

        override fun canDropOver(
            recyclerView: RecyclerView,
            current: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            val fromId = itemIdOf(current) ?: return false
            val toId = itemIdOf(target) ?: return false
            return (labelNavIds.containsKey(fromId) && labelNavIds.containsKey(toId)) ||
                   (subfolderNavIds.containsKey(fromId) && subfolderNavIds.containsKey(toId))
        }

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            val fromId = itemIdOf(viewHolder) ?: return false
            val toId = itemIdOf(target) ?: return false
            // With data-only swaps the layout never changes under the drag, so the same
            // crossing keeps re-firing onMove every frame. Gate on visual position: only
            // swap when the data order doesn't already match where the finger has put
            // the row relative to the target — makes repeated calls no-ops instead of
            // an A/B oscillation.
            val selVisCenter = viewHolder.itemView.let { it.top + it.translationY + it.height / 2f }
            val tgtVisCenter = target.itemView.let { it.top + it.translationY + it.height / 2f }
            val shouldBeAbove = selVisCenter < tgtVisCenter
            fun orderAlreadyCorrect(from: Int, to: Int): Boolean =
                if (shouldBeAbove) from < to else from > to
            // Slide the target row into the slot the dragged row is vacating.
            fun animateTargetShift() {
                val v = target.itemView
                val shift = if (shouldBeAbove) viewHolder.itemView.height.toFloat()
                            else -viewHolder.itemView.height.toFloat()
                val newTy = (shiftedRows[v] ?: 0f) + shift
                shiftedRows[v] = newTy
                draggedNetShift -= shift
                v.animate().translationY(newTy).setDuration(150).start()
            }
            // Labels
            val fromKw = labelNavIds[fromId]
            val toKw = labelNavIds[toId]
            if (fromKw != null && toKw != null) {
                val from = labels.indexOfFirst { it.keyword == fromKw }
                val to = labels.indexOfFirst { it.keyword == toKw }
                if (from < 0 || to < 0 || from == to || orderAlreadyCorrect(from, to)) return false
                // Data-only swap: the adapter is Menu-backed, so notifyItemMoved would
                // animate rows the underlying Menu never reorders — RecyclerView and
                // ItemTouchHelper desync and long drags cancel out in pairs. The static
                // layout keeps every crossing test deterministic; clearView's menu
                // rebuild applies the new order visually on drop.
                labels.add(to, labels.removeAt(from))
                animateTargetShift()
                return true
            }
            // Subfolders
            val fromMbId = subfolderNavIds[fromId]
            val toMbId = subfolderNavIds[toId]
            if (fromMbId != null && toMbId != null) {
                val from = subfolderDisplayOrder.indexOf(fromMbId)
                val to = subfolderDisplayOrder.indexOf(toMbId)
                if (from < 0 || to < 0 || from == to || orderAlreadyCorrect(from, to)) return false
                // Data-only swap — same rationale as the labels branch above.
                subfolderDisplayOrder.add(to, subfolderDisplayOrder.removeAt(from))
                animateTargetShift()
                return true
            }
            return false
        }

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                viewHolder.itemView.performHapticFeedback(
                    android.view.HapticFeedbackConstants.LONG_PRESS
                )
                viewHolder.itemView.alpha = 0.7f
                draggedNetShift = 0f

                // Snapshot the section's row bounds once, before any swap animation begins.
                dragMinDY = -Float.MAX_VALUE
                dragMaxDY = Float.MAX_VALUE
                val id = itemIdOf(viewHolder)
                val sectionIds: Set<Int>? = when {
                    id != null && labelNavIds.containsKey(id) -> labelNavIds.keys
                    id != null && subfolderNavIds.containsKey(id) -> subfolderNavIds.keys
                    else -> null
                }
                if (sectionIds != null) {
                    var minTop = Float.MAX_VALUE
                    var maxBottom = -Float.MAX_VALUE
                    for (i in 0 until rv.childCount) {
                        val child = rv.getChildAt(i)
                        val childId = itemIdOf(rv.getChildViewHolder(child))
                        if (childId != null && childId in sectionIds) {
                            minTop = minOf(minTop, child.top.toFloat())
                            maxBottom = maxOf(maxBottom, child.bottom.toFloat())
                        }
                    }
                    if (minTop != Float.MAX_VALUE) {
                        val itemHeight = viewHolder.itemView.height
                        // Half-item slack past the section edges: ItemTouchHelper's
                        // chooseDropTarget uses strict comparisons for upward swaps, so
                        // clamping exactly at the boundary makes the top-most swap
                        // trigger only intermittently (down was fine, up flickered).
                        val slack = itemHeight / 2f
                        dragMinDY = minTop - viewHolder.itemView.top - slack
                        dragMaxDY = maxBottom - itemHeight - viewHolder.itemView.top + slack
                    }
                }
            }
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            viewHolder.itemView.alpha = 1f
            dragMinDY = -Float.MAX_VALUE
            dragMaxDY = Float.MAX_VALUE
            // Recovery animation is zero-length (getAnimationDuration), so ItemTouchHelper
            // has just snapped the dragged row back to its layout slot. Restore it to
            // where the finger released it, then swoosh it into the slot its data now
            // occupies; neighbors hold their shifted positions until the rebuild.
            viewHolder.itemView.translationY = lastDragDY
            saveLabels()
            saveSubfolderOrder()
            // NavigationView's adapter is Menu-backed: the row order reverts on any
            // relayout unless the underlying Menu itself is rebuilt in the new order —
            // so this rebuild is required, not optional. It runs from the animation's
            // end callback (a safe frame, outside any RecyclerView layout pass), in the
            // same frame all manual translations are zeroed, so the rebuilt layout takes
            // over exactly where the animation ended.
            viewHolder.itemView.animate()
                .translationY(draggedNetShift)
                .setDuration(180)
                .withEndAction {
                    shiftedRows.keys.forEach { it.animate().cancel(); it.translationY = 0f }
                    shiftedRows.clear()
                    viewHolder.itemView.translationY = 0f
                    rebuildDrawerMenu()
                }
                .start()
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

        // Kill the drop "recovery" slide — by default ItemTouchHelper animates the
        // released row back to its layout slot (the pre-drag position), which reads
        // as a flick to the old spot right before the rebuild snaps it to the new one.
        override fun getAnimationDuration(
            recyclerView: RecyclerView,
            animationType: Int,
            animateDx: Float,
            animateDy: Float
        ): Long = 0L

        // Default chooseDropTarget is asymmetric: upward swaps use strict comparisons
        // against neighbors that are mid-swap-animation, so dragging up would flicker
        // or intermittently revert while dragging down worked. Symmetric rule instead:
        // swap with whichever candidate's center the dragged row's center has crossed.
        override fun chooseDropTarget(
            selected: RecyclerView.ViewHolder,
            dropTargets: MutableList<RecyclerView.ViewHolder>,
            curX: Int,
            curY: Int
        ): RecyclerView.ViewHolder? {
            val selectedCenter = curY + selected.itemView.height / 2
            var best: RecyclerView.ViewHolder? = null
            var bestDist = Int.MAX_VALUE
            for (target in dropTargets) {
                // A neighbor mid-swap-animation has its layout `top` already updated
                // while its visual position lags in translationY — compare against the
                // on-screen center, or the crossing test fires against a position the
                // row isn't actually at yet and the swap oscillates.
                val visualTop = target.itemView.top + target.itemView.translationY
                val targetCenter = (visualTop + target.itemView.height / 2f).toInt()
                val movingUp = targetCenter < selected.itemView.top + selected.itemView.height / 2
                val crossed = if (movingUp) selectedCenter <= targetCenter
                              else selectedCenter >= targetCenter
                if (!crossed) continue
                val dist = kotlin.math.abs(selectedCenter - targetCenter)
                if (dist < bestDist) { bestDist = dist; best = target }
            }
            return best
        }

        // Clamp the visual drag translation so a dragged row can't be pulled past
        // the boundaries of its own section (labels only travel within labels,
        // folders only within folders) even though canDropOver already blocks the
        // actual reorder — without this the row still visually escapes into the
        // adjacent section while following the finger.
        override fun onChildDraw(
            c: android.graphics.Canvas,
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            dX: Float,
            dY: Float,
            actionState: Int,
            isCurrentlyActive: Boolean
        ) {
            val clampedDY = if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                dY.coerceIn(dragMinDY, dragMaxDY).also { if (isCurrentlyActive) lastDragDY = it }
            } else dY
            super.onChildDraw(c, recyclerView, viewHolder, dX, clampedDY, actionState, isCurrentlyActive)
        }
    }
    labelDragHelper = ItemTouchHelper(callback).also { it.attachToRecyclerView(rv) }
}

internal fun MainActivity.saveSubfolderOrder() {
    val email = currentAccountEmail ?: return
    val arr = JSONArray().apply { subfolderDisplayOrder.forEach { put(it) } }
    getSharedPreferences(MainActivity.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        .edit().putString("subfolder_display_order_$email", arr.toString()).apply()
}

internal fun MainActivity.loadSubfolderOrder() {
    val email = currentAccountEmail ?: return
    val raw = getSharedPreferences(MainActivity.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        .getString("subfolder_display_order_$email", null) ?: return
    runCatching {
        subfolderDisplayOrder = (0 until JSONArray(raw).length())
            .map { JSONArray(raw).getString(it) }.toMutableList()
    }
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
                        showThemedSnackbar("Not available for drafts")
                        return
                    }

                    // Deleting from Trash is permanent: confirm first, restoring the row meanwhile.
                    if (isTrashedEmail(item) && action == MainActivity.SwipeAction.DELETE) {
                        emailAdapter.notifyItemChanged(position)
                        confirmPermanentDelete(account, listOf(item.id))
                        return
                    }

                    // Archiving from the Favourites view keeps the email flagged, so it
                    // stays visible there (an email can be both favourited and archived).
                    // Snap the row back instead of removing it.
                    if (selectedFolder == R.id.nav_favourite && action == MainActivity.SwipeAction.ARCHIVE) {
                        emailAdapter.notifyItemChanged(position)
                        showThemedSnackbar("Moved to Archive")
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

/**
 * Wholesale replacement of the visible rows. The adapter uses stable ids, so two rows
 * sharing an email id — or a swap landing while the previous item animations are still
 * running — makes RecyclerView try to re-attach a live child and crash with
 * "Called attach on a child which is not detached". Ending the animations and
 * deduplicating here keeps every caller safe.
 */
internal fun MainActivity.setVisibleEmails(rows: List<DisplayEmail>) {
    emailsRecyclerView.itemAnimator?.endAnimations()
    emails.clear()
    emails.addAll(rows.distinctBy { it.id })
    emailAdapter.notifyDataSetChanged()
}

internal fun MainActivity.updateEmailsList(rawList: List<DisplayEmail>) {
    // Paging fetches finish inside scroll callbacks, so this can land mid layout/scroll
    // pass; mutating the adapter's list there corrupts RecyclerView child state
    // ("Called attach on a child which is not detached"). Defer past the pass.
    if (emailsRecyclerView.isComputingLayout ||
        emailsRecyclerView.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
        emailsRecyclerView.post { updateEmailsList(rawList) }
        return
    }
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

    // A background periodic sync landing while the user is searching must not blow
    // away their search results/scroll position. baseEmails/folderCache (source data
    // for search) are still refreshed above/by the caller — just re-run the active
    // filter instead of replacing `emails` with the plain folder view.
    if (isSearchActive) {
        applySearchFilter(searchInput.text?.toString() ?: "")
        saveEmailCache()
        return
    }

    if (diffResult != null) {
        emails.clear()
        emails.addAll(display)
        diffResult.dispatchUpdatesTo(emailAdapter)
    } else {
        setVisibleEmails(display)
    }

    if (firstChanged) {
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
    // Subfolders use a stable server mailboxId so the cache survives app restarts
    // (the navId is dynamically generated and changes each session).
    val stableKey = subfolderNavIds[folderId]?.let { "subfolder_$it" }
    return if (stableKey != null) "$scope#$stableKey"
    else com.falseenvironment.jmapjolt.cache.EmailCacheStore.bucket(scope, folderId)
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
            // The search bar is shared with Settings/Calendar/Contacts, where filtering
            // the (hidden) mail list looked like nothing happened. As soon as the user
            // actually types, move to the inbox so the results are on screen.
            if (query.isNotEmpty()) enterMailboxForSearch()
            applySearchFilter(query)
        }
        override fun afterTextChanged(s: android.text.Editable?) {}
    })
    searchInput.setOnEditorActionListener { _, actionId, event ->
        val isEnterKey = event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER
        if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
            actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE || isEnterKey) {
            hideKeyboard()
            true
        } else false
    }

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

