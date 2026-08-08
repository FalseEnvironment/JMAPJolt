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
    private lateinit var searchBar: View
    private lateinit var selectionBar: View
    private lateinit var selectionCountView: TextView
    private lateinit var shareButton: ImageView
    private lateinit var deleteButton: ImageView
    private val adapter = ContactsAdapter()

    private var contacts: List<Contact> = emptyList()
    private var filter: ContactSource? = null
    private var query: String = ""
    private var searchHintJob: Job? = null

    /**
     * Multi-select state. The mode is a flag of its own so emptying the selection keeps the
     * selection bar up (with select-all still reachable) instead of snapping back to the search
     * bar; only the back arrow or the system back button leaves it.
     */
    private val selectedIds = linkedSetOf<String>()
    private var isSelecting = false

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
        applyShowPreference()
        updateSelectionBar()
    }

    /**
     * Settings can pin the list to a single backend. When pinned, the scope chips would only
     * offer choices that contradict the setting, so the whole bar goes away.
     */
    fun applyShowPreference() {
        val forced = ContactsPrefs.forcedSource(activity)
        filterBar.visibility = if (forced == null) View.VISIBLE else View.GONE
        filter = forced
        styleChips(filterBar)
        renderList()
    }

    /** Android back closes multi-select before the tab itself reacts. */
    fun onBackPressed(): Boolean {
        if (!isSelecting) return false
        exitSelection()
        return true
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
        applyShowPreference()
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

    /**
     * Paints a created or edited contact straight away, before the backend has acknowledged it.
     * The next [refresh] replaces the list with the server's version, so a rejected write simply
     * disappears again instead of leaving the UI lying.
     */
    private fun applyLocal(contact: Contact) {
        publishLocal(contacts.filterNot { it.id == contact.id } + contact)
    }

    /** Mirror of [applyLocal] for deletions. */
    private fun removeLocal(removed: Collection<Contact>) {
        val ids = removed.map { it.id }.toSet()
        publishLocal(contacts.filterNot { it.id in ids })
    }

    private fun publishLocal(updated: List<Contact>) {
        contacts = updated.sortedBy { it.displayName.lowercase() }
        ContactsCache.contacts = contacts
        ContactAvatars.index(contacts)
        renderList()
    }

    /** Opens the editor on a blank contact; wired to the panel's floating action button. */
    fun startNewContact() {
        val default = when (ContactsPrefs.provider(activity)) {
            ContactsPrefs.Provider.DAVX5 -> ContactSource.DAVX5
            ContactsPrefs.Provider.JMAP -> ContactSource.JMAP
        }
        ContactEditor(
            activity,
            Contact(source = default),
            onSaved = { refresh() },
            onApplied = { applyLocal(it) },
            onRemoved = { removeLocal(listOf(it)) },
        ).show()
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
        searchBar = bar
        selectionBar = buildSelectionBar()
        strip.addView(bar)
        strip.addView(selectionBar)
        return strip
    }

    /**
     * Replaces the search row while contacts are selected: count on the left, then select-all,
     * share and delete, all drawn on the same darkened accent as the search field.
     */
    private fun buildSelectionBar(): View {
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12 * density
                setColor(activity.darkenColor(palette.accent, 0.78f))
            }
            setPadding(dp(4), 0, dp(4), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
            visibility = View.GONE
        }
        bar.addView(iconButton(R.drawable.ic_arrow_back_24dp, palette.onAccent) {
            exitSelection()
        }.apply { contentDescription = context.getString(R.string.contacts_exit_selection) })

        selectionCountView = TextView(activity).apply {
            setTextColor(palette.onAccent)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .also { it.marginStart = dp(4) }
        }
        bar.addView(selectionCountView)

        bar.addView(iconButton(R.drawable.ic_lucide_check, palette.onAccent) {
            selectAllVisible()
        }.apply { contentDescription = context.getString(R.string.contacts_select_all) })
        shareButton = iconButton(R.drawable.ic_lucide_share_2, palette.onAccent) {
            shareSelected()
        }.apply { contentDescription = context.getString(R.string.contacts_share_selected) }
        deleteButton = iconButton(R.drawable.ic_lucide_trash, palette.onAccent) {
            confirmDeleteSelected()
        }.apply { contentDescription = context.getString(R.string.contacts_delete_selected) }
        bar.addView(shareButton)
        bar.addView(deleteButton)
        return bar
    }

    // ---- multi-select ---------------------------------------------------------------------

    private fun toggleSelection(contact: Contact) {
        isSelecting = true
        if (!selectedIds.remove(contact.id)) selectedIds.add(contact.id)
        // Row and section picks close the mode once nothing is left; only the select-all button
        // deliberately keeps an empty selection alive.
        if (selectedIds.isEmpty()) isSelecting = false
        updateSelectionBar()
        adapter.notifyDataSetChanged()
    }

    /** Leaves multi-select entirely and restores the search bar. */
    private fun exitSelection() {
        if (!isSelecting) return
        isSelecting = false
        selectedIds.clear()
        updateSelectionBar()
        adapter.notifyDataSetChanged()
    }

    /** Select-all doubles as deselect-all once every visible row is already picked. */
    private fun selectAllVisible() {
        isSelecting = true
        val visible = visibleContacts()
        if (visible.isNotEmpty() && visible.all { it.id in selectedIds }) selectedIds.clear()
        else visible.forEach { selectedIds.add(it.id) }
        updateSelectionBar()
        adapter.notifyDataSetChanged()
    }

    /** Tapping an index letter picks (or drops) the whole A / B / C section. */
    private fun toggleSection(letter: String) {
        val section = visibleContacts().filter { sectionLetter(it) == letter }
        if (section.isEmpty()) return
        isSelecting = true
        if (section.all { it.id in selectedIds }) section.forEach { selectedIds.remove(it.id) }
        else section.forEach { selectedIds.add(it.id) }
        if (selectedIds.isEmpty()) isSelecting = false
        updateSelectionBar()
        adapter.notifyDataSetChanged()
    }

    private fun updateSelectionBar() {
        if (!::selectionBar.isInitialized) return
        selectionBar.visibility = if (isSelecting) View.VISIBLE else View.GONE
        searchBar.visibility = if (isSelecting) View.GONE else View.VISIBLE
        if (!isSelecting) return
        selectionCountView.text =
            context.getString(R.string.contacts_selected_count, selectedIds.size)
        // Share and delete need a target; select-all stays live so an emptied selection is usable.
        val hasTargets = selectedIds.isNotEmpty()
        listOf(shareButton, deleteButton).forEach {
            it.isEnabled = hasTargets
            it.alpha = if (hasTargets) 1f else 0.35f
        }
    }

    private fun selectedContacts(): List<Contact> = contacts.filter { it.id in selectedIds }

    private fun confirmDeleteSelected() {
        val targets = selectedContacts()
        if (targets.isEmpty()) return
        // Same rounded surface card, accent typography and text buttons as the contact editor's
        // dialogs, so the confirmation follows the active theme and accent instead of the system
        // AlertDialog look (which also localised its buttons independently of the app).
        val dialog = android.app.Dialog(activity)
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16 * density
                setColor(palette.surface)
            }
            setPadding(dp(20), dp(20), dp(20), dp(12))
        }
        card.addView(TextView(activity).apply {
            text = context.getString(R.string.contacts_delete_confirm_title)
            textSize = 17f
            setTextColor(palette.text)
            setTypeface(typeface, Typeface.BOLD)
        })
        card.addView(TextView(activity).apply {
            text = context.getString(R.string.contacts_delete_confirm_body, targets.size)
            textSize = 14f
            setTextColor(palette.secondaryText)
            setPadding(0, dp(10), 0, dp(6))
        })
        card.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(dialogButton(context.getString(R.string.contacts_cancel),
                palette.secondaryText) { dialog.dismiss() })
            addView(dialogButton(context.getString(R.string.contacts_delete_selected),
                androidx.core.content.ContextCompat.getColor(
                    activity, R.color.contacts_delete_red)) {
                dialog.dismiss()
                deleteSelected(targets)
            })
        })
        dialog.setContentView(card)
        dialog.show()
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.window?.attributes?.let { lp ->
            lp.width = (resources.displayMetrics.widthPixels * 0.86f).toInt()
            dialog.window?.attributes = lp
        }
    }

    private fun dialogButton(label: String, color: Int, onClick: () -> Unit): TextView =
        TextView(activity).apply {
            text = label
            textSize = 14f
            setTextColor(color)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener { onClick() }
        }

    private fun deleteSelected(targets: List<Contact>) {
        // Rows leave the list at once; the backend deletes run afterwards and only the reload can
        // bring a contact back, which is exactly what should happen when the server refuses.
        removeLocal(targets)
        exitSelection()
        scope.launch {
            val failures = targets.count { !runCatching { repository.delete(it) }.getOrDefault(false) }
            if (failures > 0) {
                android.widget.Toast.makeText(
                    activity, R.string.contacts_delete_failed, android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            refresh()
        }
    }

    /** Exports the picked cards to a single cached .vcf and hands it to the system share sheet. */
    private fun shareSelected() {
        val targets = selectedContacts()
        if (targets.isEmpty()) return
        val sent = runCatching {
            val dir = java.io.File(activity.cacheDir, "contacts").apply { mkdirs() }
            // Shared cards should not linger on disk: drop leftovers from earlier shares.
            dir.listFiles()?.forEach { it.delete() }
            val file = java.io.File(dir, "contacts-${System.currentTimeMillis()}.vcf")
            file.writeText(ContactsVcf.toVcf(targets))
            val uri = androidx.core.content.FileProvider.getUriForFile(
                activity, "${activity.packageName}.fileprovider", file)
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/x-vcard"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(android.content.Intent.createChooser(
                intent, context.getString(R.string.contacts_share_title)))
        }.isSuccess
        if (!sent) {
            android.widget.Toast.makeText(
                activity, R.string.contacts_share_failed, android.widget.Toast.LENGTH_SHORT).show()
        }
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
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
    }

    /** Index letter of a row, "#" for names that do not start with a letter (like AOSP Contacts). */
    private fun sectionLetter(contact: Contact): String {
        val first = contact.displayName.trim().firstOrNull() ?: return "#"
        return if (first.isLetter()) first.uppercaseChar().toString() else "#"
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
            val contact = items[position]
            val letter = sectionLetter(contact)
            val isFirstOfLetter =
                position == 0 || sectionLetter(items[position - 1]) != letter
            holder.bind(contact, if (isFirstOfLetter) letter else "")
        }
    }

    /** Empty row skeleton reused by the recycler; [ContactViewHolder] only swaps the text. */
    private fun buildRowView(): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(16), dp(10))
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        // Left gutter carrying the A / B / C index letter on the first row of each section.
        row.addView(TextView(activity).apply {
            setTextColor(palette.accent)
            textSize = 14f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            minHeight = dp(40)
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(40))
                .also { it.marginEnd = dp(4) }
        })
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
            // Check mark shown in place of the initials/photo while the row is selected.
            addView(ImageView(activity).apply {
                setImageResource(R.drawable.ic_lucide_check)
                imageTintList = ColorStateList.valueOf(activity.getOnAccentColor())
                val pad = dp(7)
                setPadding(pad, pad, pad, pad)
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

        private val letter = row.getChildAt(0) as TextView
        private val avatarBubble = row.getChildAt(1) as FrameLayout
        private val avatar = avatarBubble.getChildAt(0) as TextView
        private val avatarPhoto = avatarBubble.getChildAt(1) as ImageView
        private val avatarCheck = avatarBubble.getChildAt(2) as ImageView
        private val name = (row.getChildAt(2) as LinearLayout).getChildAt(0) as TextView
        private val subtitle = (row.getChildAt(2) as LinearLayout).getChildAt(1) as TextView
        private val badge = row.getChildAt(3) as TextView

        fun bind(contact: Contact, sectionLetter: String) {
            letter.text = sectionLetter
            letter.isClickable = sectionLetter.isNotEmpty()
            letter.setOnClickListener(
                if (sectionLetter.isEmpty()) null
                else View.OnClickListener { toggleSection(sectionLetter) })
            val selected = contact.id in selectedIds
            // Selected rows mirror the inbox: the accent circle stays and only carries a check,
            // over a slightly darkened row background (see EmailAdapter's isSelected branch).
            avatar.text = if (selected) "" else contact.initials
            val photo = ContactAvatars.decode(contact.photoBase64)
            avatarPhoto.setImageBitmap(photo)
            avatarPhoto.visibility =
                if (photo == null || selected) View.GONE else View.VISIBLE
            avatarCheck.visibility = if (selected) View.VISIBLE else View.GONE
            row.setBackgroundColor(
                if (selected) activity.darkenColor(activity.getDialogBackgroundColor(), 0.85f)
                else android.graphics.Color.TRANSPARENT)
            avatarBubble.setOnClickListener { toggleSelection(contact) }
            row.setOnLongClickListener { toggleSelection(contact); true }
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
            row.setOnClickListener {
                if (isSelecting) toggleSelection(contact)
                else ContactEditor(
                    activity,
                    contact,
                    onSaved = { refresh() },
                    onApplied = { applyLocal(it) },
                    onRemoved = { removeLocal(listOf(it)) },
                ).show()
            }
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
