package com.falseenvironment.jmapjolt

import android.content.res.ColorStateList
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Slim, accent-tinted overlay scrollbar, mirroring the drawer's `applyDrawerScrollbarStyle` so the
 * contacts list and the notes box scroll with the same 3dp accent thumb as the rest of the app.
 */
internal fun View.applyThemedScrollbar(accent: Int) {
    val d = resources.displayMetrics.density
    isVerticalScrollBarEnabled = true
    scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
    scrollBarSize = (3 * d).toInt()
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        verticalScrollbarThumbDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999 * d
            setColor(android.graphics.Color.argb(
                160,
                android.graphics.Color.red(accent),
                android.graphics.Color.green(accent),
                android.graphics.Color.blue(accent)
            ))
        }
    }
}

/**
 * Address book UI hosted inside [MainActivity] so the app's real navigation drawer stays available,
 * exactly like [CalendarPanel]. The list is a [RecyclerView] and the first paint comes from
 * [ContactsCache], so opening the tab with a large address book does not block on the network or
 * inflate every row up front.
 */
class ContactsPanel(private val activity: MainActivity) : FrameLayout(activity) {

    private var palette: CalendarTheme.Palette = CalendarTheme.palette(activity)
    private val repository = ContactsRepository(activity)
    private val scope get() = activity.lifecycleScope

    private lateinit var searchInput: EditText
    private lateinit var listView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var filterBar: LinearLayout
    private val adapter = ContactsAdapter()

    private var contacts: List<Contact> = emptyList()
    private var filter: ContactSource? = null
    private var query: String = ""
    private var searchHintJob: Job? = null

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    init {
        buildUi()
    }

    private fun buildUi() {
        removeAllViews()
        setBackgroundColor(palette.background)
        addView(buildRoot())
        addView(buildFab())
        adapter.submit(visibleContacts())
    }

    /** Called when the panel becomes visible. */
    fun onShown() {
        // The panel outlives a theme change (it is created once and re-shown), so re-read the
        // palette here and rebuild if the user switched theme or accent in the meantime.
        val current = CalendarTheme.palette(activity)
        if (current != palette) {
            palette = current
            buildUi()
        }
        if (ContactsPrefs.provider(activity) == ContactsPrefs.Provider.DAVX5 &&
            !ContactsProvider.hasReadPermission(activity)) {
            activity.requestContactsPermissions { refresh() }
            return
        }
        // Paint whatever the last load produced, then reconcile with the backends in background.
        ContactsCache.contacts?.let {
            contacts = it
            renderList()
        }
        refresh()
    }

    fun refresh() {
        scope.launch {
            val loaded = runCatching { repository.loadAll() }.getOrNull() ?: return@launch
            ContactsCache.contacts = loaded
            contacts = loaded
            renderList()
        }
    }

    /** Opens the editor on a blank contact; wired to the panel's floating action button. */
    fun startNewContact() {
        val default = when (ContactsPrefs.provider(activity)) {
            ContactsPrefs.Provider.DAVX5 -> ContactSource.DAVX5
            ContactsPrefs.Provider.JMAP -> ContactSource.JMAP
        }
        ContactEditor(activity, Contact(source = default)) { refresh() }.show()
    }

    // ---- layout ---------------------------------------------------------------------------

    private fun buildRoot(): View {
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        col.addView(buildSearchBar())
        filterBar = buildFilterBar()
        col.addView(filterBar)

        val content = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        listView = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = this@ContactsPanel.adapter
            setHasFixedSize(true)
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(88))
            applyThemedScrollbar(palette.accent)
        }
        emptyView = TextView(activity).apply {
            text = context.getString(R.string.contacts_empty)
            setTextColor(palette.secondaryText)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(48), dp(32), dp(32))
            visibility = View.GONE
        }
        content.addView(listView)
        content.addView(emptyView)
        col.addView(content)
        return col
    }

    /**
     * Same top bar as the inbox: an accent strip holding a squared (12dp) container on a darkened
     * accent, with the menu icon, the field and the overflow all drawn in [palette.onAccent].
     * Mirrors `searchBarContainer` in activity_main.xml and the styling applied in ThemeHelper.
     */
    private fun buildSearchBar(): View {
        val strip = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.accent)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12 * density
                setColor(activity.darkenColor(palette.accent, 0.78f))
            }
            setPadding(dp(4), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
        }
        bar.addView(iconButton(R.drawable.ic_menu_24dp, palette.onAccent) {
            activity.openMainDrawer()
        })

        val fieldWrapper = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val hintColor = CalendarTimelineView.adjustAlpha(
            palette.onAccent, if (palette.isDark) 0.65f else 0.55f)
        searchInput = EditText(activity).apply {
            hint = context.getString(R.string.contacts_search_placeholder)
            setHintTextColor(hintColor)
            setTextColor(palette.onAccent)
            textSize = 16f
            background = null
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            setPadding(dp(4), dp(10), 0, dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString().orEmpty()
                    renderList()
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            })
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) startSearchingHint() else stopSearchingHint()
            }
        }
        fieldWrapper.addView(searchInput)
        bar.addView(fieldWrapper)

        bar.addView(TextView(activity).apply {
            text = "⋮"
            textSize = 22f
            setTextColor(palette.onAccent)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(2), dp(10), dp(2))
            setOnClickListener { showOverflowMenu(it) }
        })
        strip.addView(bar)
        return strip
    }

    /**
     * "Searching." → ".." → "..." placeholder loop while the field is focused and still empty,
     * matching the inbox search bar (see `startSearchingHintAnimation` in MainSearch).
     */
    private fun startSearchingHint() {
        searchHintJob?.cancel()
        searchHintJob = scope.launch {
            var dots = 0
            while (searchInput.hasFocus()) {
                if (searchInput.text.isNullOrEmpty()) {
                    dots = dots % 3 + 1
                    searchInput.hint = "Searching" + ".".repeat(dots)
                }
                delay(400)
            }
        }
    }

    private fun stopSearchingHint() {
        searchHintJob?.cancel()
        searchHintJob = null
        searchInput.hint = context.getString(R.string.contacts_search_placeholder)
    }

    /** Scope chips: which backend the list and the search look at. */
    private fun buildFilterBar(): LinearLayout {
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(6), dp(12), dp(8))
        }
        val chips = listOf<Pair<String, ContactSource?>>(
            context.getString(R.string.contacts_filter_all) to null,
            context.getString(R.string.contacts_source_jmap) to ContactSource.JMAP,
            context.getString(R.string.contacts_source_davx5) to ContactSource.DAVX5
        )
        // Equal-weight chips so the row spans the full bar width like the search bar above it.
        chips.forEachIndexed { index, (label, source) ->
            bar.addView(TextView(activity).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(8), dp(8), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ).also { if (index < chips.lastIndex) it.marginEnd = dp(8) }
                tag = source
                setOnClickListener {
                    filter = source
                    styleChips(bar)
                    renderList()
                }
            })
        }
        styleChips(bar)
        return bar
    }

    private fun styleChips(bar: LinearLayout) {
        for (i in 0 until bar.childCount) {
            val chip = bar.getChildAt(i) as? TextView ?: continue
            val selected = chip.tag == filter
            chip.setTextColor(if (selected) palette.onAccent else palette.secondaryText)
            chip.background = pill(if (selected) palette.accent else palette.surface)
        }
    }

    private fun showOverflowMenu(anchorView: View) {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8 * density
                setColor(palette.surface)
            }
            val vp = (4 * density).toInt()
            setPadding(0, vp, 0, vp)
            elevation = 8 * density
        }
        var popupRef: android.widget.PopupWindow? = null

        fun row(label: String, iconRes: Int, action: () -> Unit): LinearLayout =
            LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(dp(220), dp(48))
                val hp = dp(16)
                setPadding(hp, 0, hp, 0)
                addView(ImageView(activity).apply {
                    setImageResource(iconRes)
                    imageTintList = ColorStateList.valueOf(palette.accent)
                    val sz = dp(18)
                    layoutParams = LinearLayout.LayoutParams(sz, sz).also { it.marginEnd = dp(12) }
                })
                addView(TextView(activity).apply {
                    text = label; textSize = 14f; setTextColor(palette.text)
                })
                setOnClickListener { popupRef?.dismiss(); action() }
            }

        container.addView(row(context.getString(R.string.contacts_refresh),
            R.drawable.ic_rotate_cw) { refresh() })
        container.addView(View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(CalendarTimelineView.adjustAlpha(palette.secondaryText, 0.25f))
        })
        // DAVx5's LoginActivity sets up CardDAV and CalDAV collections alike.
        container.addView(row(context.getString(R.string.contacts_add_carddav),
            R.drawable.ic_lucide_user) { CalendarDavx5.launch(activity) })

        val pw = android.widget.PopupWindow(
            container,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).also {
            it.elevation = 10 * density
            it.isOutsideTouchable = true
        }
        popupRef = pw
        pw.showAsDropDown(anchorView, -dp(200), 0)
    }

    private fun buildFab(): View =
        com.google.android.material.floatingactionbutton.FloatingActionButton(activity).apply {
            setImageResource(R.drawable.ic_lucide_plus)
            imageTintList = ColorStateList.valueOf(palette.onAccent)
            backgroundTintList = ColorStateList.valueOf(palette.accent)
            contentDescription = context.getString(R.string.contacts_new)
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also {
                it.gravity = Gravity.BOTTOM or Gravity.END
                it.setMargins(dp(16), dp(16), dp(16), dp(16))
            }
            setOnClickListener { startNewContact() }
        }

    // ---- list -----------------------------------------------------------------------------

    /** Backend filter + free-text match over the fields a user would search by. */
    private fun visibleContacts(): List<Contact> {
        val needle = query.trim().lowercase()
        return contacts.filter { contact ->
            (filter == null || contact.source == filter) &&
                (needle.isEmpty() || contact.matches(needle))
        }
    }

    private fun Contact.matches(needle: String): Boolean =
        displayName.lowercase().contains(needle) ||
            nickname.lowercase().contains(needle) ||
            emails.any { it.address.lowercase().contains(needle) } ||
            phones.any { it.number.lowercase().contains(needle) } ||
            organization?.companyName?.lowercase()?.contains(needle) == true ||
            categories.any { it.lowercase().contains(needle) }

    private fun renderList() {
        val visible = visibleContacts()
        emptyView.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        emptyView.text = context.getString(
            if (contacts.isEmpty()) R.string.contacts_empty else R.string.contacts_no_match)
        adapter.submit(visible)
    }

    private fun pill(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 999 * density
        setColor(color)
    }

    /** Same 44dp box with 10dp padding as `searchBarMenuIcon` in activity_main.xml. */
    private fun iconButton(res: Int, tint: Int, onClick: () -> Unit): ImageView =
        ImageView(activity).apply {
            setImageResource(res)
            imageTintList = ColorStateList.valueOf(tint)
            scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = dp(10)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            setOnClickListener { onClick() }
        }

    /** Row recycling keeps scrolling smooth no matter how large the address book gets. */
    private inner class ContactsAdapter : RecyclerView.Adapter<ContactViewHolder>() {

        private var items: List<Contact> = emptyList()

        fun submit(list: List<Contact>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder =
            ContactViewHolder(buildRowView())

        override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
            holder.bind(items[position])
        }
    }

    /** Empty row skeleton reused by the recycler; [ContactViewHolder] only swaps the text. */
    private fun buildRowView(): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        // Initials bubble with the photo (when the contact has one) layered on top of it.
        row.addView(FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).also { it.marginEnd = dp(14) }
            addView(TextView(activity).apply {
                setTextColor(palette.onAccent)
                textSize = 15f
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(palette.accent)
                }
                layoutParams = FrameLayout.LayoutParams(dp(40), dp(40))
            })
            addView(ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setOval(0, 0, view.width, view.height)
                    }
                }
                visibility = View.GONE
                layoutParams = FrameLayout.LayoutParams(dp(40), dp(40))
            })
        })
        val textColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textColumn.addView(TextView(activity).apply {
            setTextColor(palette.text)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        })
        textColumn.addView(TextView(activity).apply {
            setTextColor(palette.secondaryText)
            textSize = 13f
        })
        row.addView(textColumn)
        row.addView(TextView(activity).apply {
            textSize = 11f
            setTextColor(palette.secondaryText)
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = pill(palette.surface)
        })
        return row
    }

    private inner class ContactViewHolder(private val row: LinearLayout) :
        RecyclerView.ViewHolder(row) {

        private val avatarBubble = row.getChildAt(0) as FrameLayout
        private val avatar = avatarBubble.getChildAt(0) as TextView
        private val avatarPhoto = avatarBubble.getChildAt(1) as ImageView
        private val name = (row.getChildAt(1) as LinearLayout).getChildAt(0) as TextView
        private val subtitle = (row.getChildAt(1) as LinearLayout).getChildAt(1) as TextView
        private val badge = row.getChildAt(2) as TextView

        fun bind(contact: Contact) {
            avatar.text = contact.initials
            val photo = ContactAvatars.decode(contact.photoBase64)
            avatarPhoto.setImageBitmap(photo)
            avatarPhoto.visibility = if (photo == null) View.GONE else View.VISIBLE
            name.text = contact.displayName
            val sub = contact.emails.firstOrNull()?.address
                ?: contact.phones.firstOrNull()?.number
                ?: contact.organization?.companyName
            subtitle.text = sub.orEmpty()
            subtitle.visibility = if (sub.isNullOrBlank()) View.GONE else View.VISIBLE
            badge.text = context.getString(
                if (contact.source == ContactSource.JMAP) R.string.contacts_source_jmap
                else R.string.contacts_source_davx5
            )
            row.setOnClickListener { ContactEditor(activity, contact) { refresh() }.show() }
        }
    }
}

/**
 * Last loaded address book, kept for the lifetime of the process so re-entering the tab paints
 * immediately instead of waiting on a JMAP round-trip and a provider query.
 */
object ContactsCache {
    @Volatile
    var contacts: List<Contact>? = null
}
