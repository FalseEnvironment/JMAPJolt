package com.falseenvironment.jmapjolt

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
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
import kotlinx.coroutines.launch

/**
 * Address book picker for the compose screen: a searchable list of every contact that actually has
 * an email address (a contact with several addresses appears once per address), reading from the
 * same [ContactsRepository] the contacts tab uses. Rows toggle instead of dismissing, so several
 * addresses can be gathered in one pass; Done hands them all back to the caller, which turns them
 * into recipient chips in the active To/Cc/Bcc category.
 */
class ContactPicker(
    private val activity: MainActivity,
    private val onPick: (List<String>) -> Unit
) {

    /** One selectable line: a contact paired with the single address this row stands for. */
    private data class Entry(val contact: Contact, val address: String)

    private val palette = CalendarTheme.palette(activity)
    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val adapter = EntriesAdapter()
    private var entries: List<Entry> = emptyList()
    private var query: String = ""

    /** Addresses ticked so far; insertion order is the order the chips are created in. */
    private val selected = linkedSetOf<String>()

    private lateinit var emptyView: TextView
    private lateinit var doneButton: TextView
    private lateinit var dialog: Dialog

    fun show() {
        dialog = Dialog(activity)
        dialog.setContentView(buildContent())
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.attributes?.let { lp ->
            lp.width = (activity.resources.displayMetrics.widthPixels * 0.92f).toInt()
            lp.height = (activity.resources.displayMetrics.heightPixels * 0.75f).toInt()
            dialog.window?.attributes = lp
        }
        // Paint the cached book immediately, then reconcile with the backends.
        ContactsCache.contacts?.let { render(it) }
        activity.lifecycleScope.launch {
            val loaded = runCatching { ContactsRepository(activity).loadAll() }.getOrNull()
                ?: return@launch
            ContactsCache.contacts = loaded
            render(loaded)
        }
    }

    private fun buildContent(): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16 * density
                setColor(palette.surface)
            }
            setPadding(dp(16), dp(16), dp(16), dp(12))
        }

        root.addView(TextView(activity).apply {
            text = activity.getString(R.string.contacts_pick_title)
            setTextColor(palette.text)
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), 0, 0, dp(10))
        })

        root.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 999 * density
                setColor(palette.background)
            }
            setPadding(dp(14), 0, dp(10), 0)
            addView(EditText(activity).apply {
                hint = activity.getString(R.string.contacts_pick_search)
                setHintTextColor(palette.secondaryText)
                setTextColor(palette.text)
                textSize = 15f
                background = null
                inputType = InputType.TYPE_CLASS_TEXT
                setSingleLine()
                setPadding(0, dp(10), 0, dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addTextChangedListener(object : TextWatcher {
                    override fun afterTextChanged(s: Editable?) {
                        query = s?.toString().orEmpty()
                        renderVisible()
                    }
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                })
            })
        })

        val content = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        content.addView(RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = this@ContactPicker.adapter
            clipToPadding = false
            setPadding(0, dp(8), 0, dp(4))
            applyThemedScrollbar(palette.accent)
        })
        emptyView = TextView(activity).apply {
            text = activity.getString(R.string.contacts_pick_empty)
            setTextColor(palette.secondaryText)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(40), dp(24), dp(24))
            visibility = View.GONE
        }
        content.addView(emptyView)
        root.addView(content)

        fun action(label: String, color: Int, onClick: () -> Unit) = TextView(activity).apply {
            text = label
            textSize = 14f
            setTextColor(color)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(16), dp(10), dp(16), dp(6))
            setOnClickListener { onClick() }
        }

        doneButton = action(activity.getString(R.string.contacts_pick_done), palette.accent) {
            onPick(selected.toList())
            dialog.dismiss()
        }
        root.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(action(activity.getString(R.string.contacts_cancel), palette.secondaryText) {
                dialog.dismiss()
            })
            addView(doneButton)
        })
        renderDoneButton()
        return root
    }

    /** Done carries the running count and greys out until at least one address is ticked. */
    private fun renderDoneButton() {
        val count = selected.size
        doneButton.text = activity.getString(R.string.contacts_pick_done) +
            if (count > 0) " ($count)" else ""
        doneButton.isEnabled = count > 0
        doneButton.setTextColor(if (count > 0) palette.accent else palette.secondaryText)
    }

    private fun render(contacts: List<Contact>) {
        entries = contacts.flatMap { contact ->
            contact.emails
                .map { it.address.trim() }
                .filter { it.isNotEmpty() }
                .map { Entry(contact, it) }
        }
        renderVisible()
    }

    private fun renderVisible() {
        val needle = query.trim().lowercase()
        val visible = entries.filter {
            needle.isEmpty() ||
                it.address.lowercase().contains(needle) ||
                it.contact.displayName.lowercase().contains(needle) ||
                it.contact.organization?.companyName?.lowercase()?.contains(needle) == true
        }
        emptyView.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        adapter.submit(visible)
    }

    private inner class EntriesAdapter : RecyclerView.Adapter<EntryViewHolder>() {
        private var items: List<Entry> = emptyList()

        fun submit(list: List<Entry>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            EntryViewHolder(buildRow())

        override fun onBindViewHolder(holder: EntryViewHolder, position: Int) =
            holder.bind(items[position])
    }

    private fun buildRow(): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(9), dp(8), dp(9))
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        row.addView(FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).also { it.marginEnd = dp(12) }
            addView(TextView(activity).apply {
                setTextColor(palette.onAccent)
                textSize = 14f
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(palette.accent)
                }
                layoutParams = FrameLayout.LayoutParams(dp(36), dp(36))
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
                layoutParams = FrameLayout.LayoutParams(dp(36), dp(36))
            })
        })
        row.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(activity).apply {
                setTextColor(palette.text)
                textSize = 15f
            })
            addView(TextView(activity).apply {
                setTextColor(palette.secondaryText)
                textSize = 13f
            })
        })
        row.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_lucide_plus)
            imageTintList = ColorStateList.valueOf(palette.accent)
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
        })
        return row
    }

    private inner class EntryViewHolder(private val row: LinearLayout) :
        RecyclerView.ViewHolder(row) {

        private val bubble = row.getChildAt(0) as FrameLayout
        private val initials = bubble.getChildAt(0) as TextView
        private val photo = bubble.getChildAt(1) as ImageView
        private val name = (row.getChildAt(1) as LinearLayout).getChildAt(0) as TextView
        private val address = (row.getChildAt(1) as LinearLayout).getChildAt(1) as TextView
        private val tick = row.getChildAt(2) as ImageView

        fun bind(entry: Entry) {
            initials.text = entry.contact.initials
            val bitmap = ContactAvatars.decode(entry.contact.photoBase64)
            photo.setImageBitmap(bitmap)
            photo.visibility = if (bitmap == null) View.GONE else View.VISIBLE
            name.text = entry.contact.displayName
            address.text = entry.address
            renderTick(entry.address)
            // Rows toggle and the dialog stays open, so several recipients can be picked at once.
            row.setOnClickListener {
                if (!selected.remove(entry.address)) selected += entry.address
                renderTick(entry.address)
                renderDoneButton()
            }
        }

        private fun renderTick(address: String) {
            val isSelected = address in selected
            tick.setImageResource(
                if (isSelected) R.drawable.ic_lucide_check else R.drawable.ic_lucide_plus)
            tick.imageTintList = ColorStateList.valueOf(
                if (isSelected) palette.accent else palette.secondaryText)
        }
    }
}
