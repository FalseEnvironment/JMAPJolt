package com.falseenvironment.jmapjolt

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.format.DateUtils
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class EmailAdapter(private val activity: MainActivity) : RecyclerView.Adapter<EmailAdapter.EmailHolder>() {

    private fun darkenColor(color: Int, factor: Float): Int {
        val r = (Color.red(color) * factor).toInt()
        val g = (Color.green(color) * factor).toInt()
        val b = (Color.blue(color) * factor).toInt()
        return Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
    }

    init {
        // Stable ids let RecyclerView match rows across full rebinds
        // (notifyDataSetChanged), avoiding flicker and needless rebinds.
        setHasStableIds(true)
    }

    // 64-bit FNV-1a over the JMAP id: String.hashCode() is only 32-bit and a
    // collision between two different ids in the same list (e.g. the cross-folder
    // union built for search) crashes RecyclerView when stable IDs are enabled
    // ("Called attach on a child which is not detached").
    override fun getItemId(position: Int): Long {
        var h = -3750763034362895579L
        for (c in activity.emails[position].id) {
            h = (h xor c.code.toLong()) * 1099511628211L
        }
        return h
    }

    // Cached preference: reading SharedPreferences on every onBindViewHolder
    // costs a map lookup + potential lock per row while scrolling.
    var loadFaviconsEnabled: Boolean = activity
        .getSharedPreferences(MainActivity.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        .getBoolean("load_favicons", false)

    @android.annotation.SuppressLint("ResourceType")
    inner class EmailHolder(root: ViewGroup) : RecyclerView.ViewHolder(root) {
        val colorStrip: android.view.View = root.findViewById(20)
        val starButton: ImageView = root.findViewById(7)
        val avatarContainer: FrameLayout = root.findViewById(9)
        val avatar: TextView = root.findViewById(5)
        val avatarImage: ImageView = root.findViewById(8)
        val labelRow: LinearLayout = root.findViewById(21)
        val senderText: TextView = root.findViewById(11)
        val title: TextView = root.findViewById(1)   // subject
        val subtitle: TextView = root.findViewById(2) // preview
        val dateText: TextView = root.findViewById(6)
    }

    @android.annotation.SuppressLint("ResourceType")
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EmailHolder {
        val dp = parent.context.resources.displayMetrics.density
        val root = FrameLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            // Ripple feedback on tap (MD3 state layer)
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x1AFFFFFF),
                null,
                android.graphics.drawable.ColorDrawable(android.graphics.Color.WHITE)
            )
        }

        val contentLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding((12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
        }

        // Account color strip (left edge, 3dp wide, slightly rounded) - positioned overlay start
        val colorStrip = android.view.View(activity).apply {
            id = 20
            layoutParams = FrameLayout.LayoutParams((3 * dp).toInt(), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.START
                topMargin = (12 * dp).toInt()
                bottomMargin = (12 * dp).toInt()
                marginStart = (4 * dp).toInt()
            }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 999 * dp
            }
            visibility = android.view.View.GONE
        }
        root.addView(contentLayout)
        root.addView(colorStrip)

        // Avatar circle — 44dp (meets 44pt touch target)
        val avatarSize = (44 * dp).toInt()
        val avatarContainer = FrameLayout(activity).apply {
            id = 9
            layoutParams = LinearLayout.LayoutParams(avatarSize, avatarSize)
                .apply { topMargin = (1 * dp).toInt() }
        }
        val avatar = TextView(activity).apply {
            id = 5
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val avatarImage = ImageView(activity).apply {
            id = 8
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        avatarContainer.addView(avatar)
        avatarContainer.addView(avatarImage)

        // Text column
        val textWrap = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins((12 * dp).toInt(), 0, (4 * dp).toInt(), 0) }
        }
        val senderTv = TextView(activity).apply {
            id = 11
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            letterSpacing = 0.01f
        }
        val subjectTv = TextView(activity).apply {
            id = 1
            textSize = 15f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = (2 * dp).toInt() }
            letterSpacing = 0.005f
        }
        val previewTv = TextView(activity).apply {
            id = 2
            textSize = 13f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = (2 * dp).toInt() }
        }
        textWrap.addView(senderTv)
        textWrap.addView(subjectTv)
        textWrap.addView(previewTv)

        // Right column: date (top) + star (bottom, larger hitbox)
        val rightWrap = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val dText = TextView(activity).apply {
            id = 6
            textSize = 12f
            maxLines = 1
            setSingleLine()
            gravity = Gravity.END
            letterSpacing = 0.01f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        // Up to 3 label tag icons (plus a "+" overflow marker) left of the time.
        val labelRowView = LinearLayout(activity).apply {
            id = 21
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        repeat(3) {
            labelRowView.addView(ImageView(activity).apply {
                setImageResource(R.drawable.ic_lucide_tag)
                val sz = (14 * dp).toInt()
                layoutParams = LinearLayout.LayoutParams(sz, sz)
                    .apply { marginEnd = (4 * dp).toInt() }
                scaleType = ImageView.ScaleType.FIT_CENTER
                visibility = android.view.View.GONE
            })
        }
        labelRowView.addView(TextView(activity).apply {
            text = "+"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = (4 * dp).toInt() }
            visibility = android.view.View.GONE
        })
        // Child index 4: account-color dot. Shown only in the unified inbox so the
        // user can tell which account a row's labels belong to.
        labelRowView.addView(ImageView(activity).apply {
            val sz = (8 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz)
                .apply { marginEnd = (4 * dp).toInt() }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(android.graphics.Color.GRAY)
            }
            visibility = android.view.View.GONE
        })
        val dateRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = (4 * dp).toInt() }
            addView(labelRowView)
            addView(dText)
        }
        val starSpacer = android.widget.Space(activity).apply {
            layoutParams = LinearLayout.LayoutParams(1, 0, 1f)
        }
        val starPad = (5 * dp).toInt()
        val starBtn = ImageView(activity).apply {
            id = 7
            setImageResource(R.drawable.ic_lucide_star)
            val starSize = (30 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(starSize, starSize)
            setPadding(starPad, starPad, starPad, starPad)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        rightWrap.addView(dateRow)
        rightWrap.addView(starSpacer)
        rightWrap.addView(starBtn)

        contentLayout.addView(avatarContainer)
        contentLayout.addView(textWrap)
        contentLayout.addView(rightWrap)
        return EmailHolder(root)
    }

    override fun getItemCount(): Int = activity.emails.size

    override fun onBindViewHolder(holder: EmailHolder, position: Int) {
        val item = activity.emails[position]
        val isLight = activity.currentTheme == "light"
        val primaryColor = if (isLight) Color.BLACK else Color.WHITE
        val secondaryColor = if (isLight) "#5A5A5A".toColorInt() else "#9E9E9E".toColorInt()
        val mutedColor = if (isLight) "#8A8A8A".toColorInt() else "#616161".toColorInt()

        // Staggered entrance: only animate for first 12 items on fresh loads
        if (position < 12 && holder.itemView.alpha == 0f) {
            val dp = holder.itemView.resources.displayMetrics.density
            holder.itemView.alpha = 0f
            holder.itemView.translationY = 16 * dp
            holder.itemView.animate()
                .alpha(1f).translationY(0f)
                .setStartDelay((position * 35L).coerceAtMost(280L))
                .setDuration(260)
                .setInterpolator(android.view.animation.DecelerateInterpolator(2f))
                .start()
        } else {
            holder.itemView.alpha = 1f
            holder.itemView.translationY = 0f
        }

        val senderName = if (activity.selectedFolder == R.id.nav_drafts) {
            "To: " + item.toEmail.ifBlank { "(no recipient)" }
        } else {
            item.from.ifBlank { item.fromEmail }
        }

        // Account color strip: only meaningful with more than one account, shown in the
        // unified inbox and kept visible during search (selectedFolder stays unified, but
        // be explicit so the strip survives any future search-scope changes).
        val multiAccount = activity.savedAccounts.size > 1
        val showAccountStrip = multiAccount && item.accountEmail.isNotBlank() &&
            (activity.selectedFolder == R.id.nav_unified_inbox || activity.isSearchActive)
        if (showAccountStrip) {
            holder.colorStrip.visibility = android.view.View.VISIBLE
            holder.colorStrip.setBackgroundColor(activity.getAccountColor(item.accountEmail))
        } else {
            holder.colorStrip.visibility = android.view.View.GONE
        }
        holder.senderText.text = senderName
        holder.senderText.setTextColor(secondaryColor)
        holder.title.text = item.subject
        holder.subtitle.text = item.preview

        // Sent and Drafts are always shown as read — their seen state is irrelevant.
        val alwaysRead = activity.selectedFolder == R.id.nav_sent ||
                activity.selectedFolder == R.id.nav_drafts
        if (!item.seen && !alwaysRead) {
            holder.senderText.setTypeface(null, Typeface.BOLD)
            holder.title.setTypeface(null, Typeface.BOLD)
            holder.title.setTextColor(primaryColor)
            holder.subtitle.setTextColor(if (isLight) "#424242".toColorInt() else "#E0E0E0".toColorInt())
            holder.dateText.setTextColor(activity.currentAccentColor.toColorInt())
        } else {
            holder.senderText.setTypeface(null, Typeface.NORMAL)
            holder.title.setTypeface(null, Typeface.NORMAL)
            holder.title.setTextColor(secondaryColor)
            holder.subtitle.setTextColor(mutedColor)
            holder.dateText.setTextColor(mutedColor)
        }

        holder.dateText.text = if (item.receivedAt > 0) {
            formatRelativeDate(item.receivedAt)
        } else {
            ""
        }

        val rowLabels = activity.labelsOf(item)
        for (i in 0..2) {
            val iv = holder.labelRow.getChildAt(i) as ImageView
            val l = rowLabels.getOrNull(i)
            if (l != null) {
                iv.visibility = android.view.View.VISIBLE
                iv.imageTintList = ColorStateList.valueOf(l.colorHex.toColorInt())
            } else {
                iv.visibility = android.view.View.GONE
            }
        }
        (holder.labelRow.getChildAt(3) as TextView).apply {
            visibility = if (rowLabels.size > 3) android.view.View.VISIBLE else android.view.View.GONE
            setTextColor(secondaryColor)
        }
        // Account-ownership dot: same gating as the account strip, only when the row has labels.
        (holder.labelRow.getChildAt(4) as ImageView).apply {
            if (showAccountStrip && rowLabels.isNotEmpty()) {
                (background as android.graphics.drawable.GradientDrawable)
                    .setColor(activity.getAccountColor(item.accountEmail))
                visibility = android.view.View.VISIBLE
            } else {
                visibility = android.view.View.GONE
            }
        }

        holder.starButton.imageTintList = ColorStateList.valueOf(
            if (item.isFavorite) activity.currentAccentColor.toColorInt()
            else if (isLight) "#CCCCCC".toColorInt() else "#444444".toColorInt()
        )
        holder.starButton.setOnClickListener {
            holder.starButton.animateTap()
            val newFav = !item.isFavorite
            item.isFavorite = newFav
            activity.baseEmails.find { it.id == item.id }?.isFavorite = newFav
            activity.optimisticFavorite[item.id] = newFav
            activity.updateFolderCachesForFavorite(item.copy(), newFav)
            // Update tint immediately — no animation, no rebind
            holder.starButton.imageTintList = ColorStateList.valueOf(
                if (newFav) activity.currentAccentColor.toColorInt()
                else if (isLight) "#CCCCCC".toColorInt() else "#444444".toColorInt()
            )
            val pos = holder.adapterPosition
            if (!newFav && activity.selectedFolder == R.id.nav_favourite &&
                pos != RecyclerView.NO_POSITION) {
                activity.emails.removeAt(pos)
                activity.baseEmails.removeAll { it.id == item.id }
                activity.folderCache[R.id.nav_favourite] = activity.emails.toList()
                notifyItemRemoved(pos)
                activity.emptyStateView.visibility = if (activity.emails.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
                activity.emailsRecyclerView.visibility = if (activity.emails.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
            }
            activity.saveEmailCache()
            val acc = activity.resolveAccountFor(item) ?: activity.connectedAccount ?: return@setOnClickListener
            activity.lifecycleScope.launch {
                try { activity.jmapClient.setFavorite(acc, item.id, newFav) }
                catch (e: Exception) { Log.e(MainActivity.TAG, "toggle star failed", e) }
            }
        }

        val isSelected = activity.selectedEmails.contains(item.id)
        val bg = android.graphics.drawable.GradientDrawable()
        bg.shape = android.graphics.drawable.GradientDrawable.OVAL

        if (isSelected) {
            bg.setColor(activity.currentAccentColor.toColorInt())
            holder.avatar.text = ""
            holder.avatarImage.tag = null
            val selPad = (7 * holder.itemView.resources.displayMetrics.density).toInt()
            holder.avatarImage.setPadding(selPad, selPad, selPad, selPad)
            // load() via Coil cancels any in-flight favicon request on this view
            holder.avatarImage.load(R.drawable.ic_lucide_check) { crossfade(false) }
            holder.avatarImage.imageTintList = ColorStateList.valueOf(activity.getOnAccentColor())
            holder.avatarImage.visibility = android.view.View.VISIBLE
            val themeBg = activity.getDialogBackgroundColor()
            holder.itemView.setBackgroundColor(darkenColor(themeBg, 0.85f))
        } else {
            holder.avatarImage.imageTintList = null
            holder.avatarImage.setPadding(0, 0, 0, 0)
            val letter =
                    if (item.from.isNotBlank()) item.from.first().uppercaseChar().toString()
                    else "?"

            val domain =
                    item.fromEmail.substringAfter("@", "").ifEmpty {
                        EMAIL_IN_TEXT_REGEX
                                .find(item.from)
                                ?.value
                                ?.substringAfter("@")
                                ?: ""
                    }
            val normalizedDomain = if (domain.isNotEmpty()) FaviconRepository.getRootDomain(domain.lowercase()) else ""

            val hashKey = if (normalizedDomain.isNotEmpty()) normalizedDomain else item.from
            val hash = hashKey.hashCode()
            val hue = kotlin.math.abs(hash % 360).toFloat()
            val hashColor = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.75f))
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)

            val loadFavicons = loadFaviconsEnabled

            if (loadFavicons && normalizedDomain.isNotEmpty()) {
                // Offset from the theme background so dark favicons stay visible on dark themes
                // (and light favicons on the light theme).
                val neutralBg = when (activity.currentTheme) {
                    "light" -> "#E8E8E8".toColorInt()
                    "oled"  -> "#212121".toColorInt()
                    else    -> "#383838".toColorInt()
                }
                bg.setColor(neutralBg)
                val favPad = (6 * holder.itemView.resources.displayMetrics.density).toInt()
                holder.avatarImage.setPadding(favPad, favPad, favPad, favPad)
                holder.avatar.text = letter
                holder.avatarImage.visibility = android.view.View.VISIBLE
                holder.avatarImage.setImageDrawable(null)
                holder.avatarImage.tag = normalizedDomain
                // Cancel any in-flight job so stale crossfades don't flicker on rebind.
                (holder.avatarImage.getTag(R.id.tag_favicon_job) as? kotlinx.coroutines.Job)?.cancel()
                val faviconJob = activity.lifecycleScope.launch {
                    val bitmap = FaviconRepository.fetchFavicon(normalizedDomain)
                    if (holder.avatarImage.tag != normalizedDomain) return@launch
                    if (activity.selectedEmails.contains(item.id)) return@launch
                    if (bitmap != null) {
                        holder.avatarImage.load(bitmap) {
                            crossfade(false)
                            transformations(CircleCropTransformation())
                        }
                        holder.avatar.text = ""
                    } else {
                        (holder.avatar.background as? android.graphics.drawable.GradientDrawable)
                            ?.setColor(hashColor)
                        holder.avatar.text = letter
                        holder.avatarImage.visibility = android.view.View.GONE
                    }
                }
                holder.avatarImage.setTag(R.id.tag_favicon_job, faviconJob)
            } else {
                bg.setColor(hashColor)
                holder.avatar.text = letter
                holder.avatarImage.visibility = android.view.View.GONE
            }
        }
        holder.avatar.background = bg

        holder.avatarContainer.setOnClickListener { activity.toggleSelection(item.id) }
        holder.itemView.setOnLongClickListener {
            if (activity.selectedEmails.isEmpty()) {
                activity.toggleSelection(item.id)
                true
            } else false
        }
        holder.itemView.setOnClickListener {
            if (activity.selectedEmails.isNotEmpty()) {
                activity.toggleSelection(item.id)
            } else if (activity.selectedFolder == R.id.nav_drafts) {
                activity.openDraftForEdit(item)
            } else {
                activity.showEmailDetail(item)
            }
        }
    }
}

/**
 * Relative time for email rows:
 *  - under 1 minute -> "now"
 *  - 1-59 minutes   -> "Nm ago"
 *  - 1-23 hours     -> "Nh ago"
 *  - 1-5 days       -> "Nd ago"
 *  - older          -> absolute date ("MMM dd")
 */
// Hoisted from onBindViewHolder: compiling a regex / date formatter per bound row
// is measurable jank while scrolling.
private val EMAIL_IN_TEXT_REGEX = "[a-zA-Z0-9._-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}".toRegex()
private val MONTH_DAY_FORMAT = SimpleDateFormat("MMM dd", Locale.getDefault())

internal fun formatRelativeDate(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    if (diff < 0) return MONTH_DAY_FORMAT.format(Date(timestamp))
    val minutes = diff / 60_000
    val hours = diff / 3_600_000
    val days = diff / 86_400_000
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days <= 5 -> "${days}d ago"
        else -> MONTH_DAY_FORMAT.format(Date(timestamp))
    }
}
