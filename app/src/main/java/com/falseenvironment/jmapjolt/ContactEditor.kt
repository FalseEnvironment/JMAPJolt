package com.falseenvironment.jmapjolt

import android.app.Dialog
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.Outline
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Create/edit form for an address book entry, covering the JSContact fields the app models in
 * [Contact]. The backend selector decides whether the card is pushed over JMAP or written into the
 * system provider for DAVx5 to sync; an existing contact keeps the backend it came from.
 *
 * Fields are rounded pill inputs with a caption above, matching [CalendarEventEditor]; name parts
 * share rows, and the bulkier Organization and Address sections start collapsed.
 */
class ContactEditor(
    private val activity: MainActivity,
    private val original: Contact,
    private val onSaved: () -> Unit
) {

    private val palette: CalendarTheme.Palette = CalendarTheme.palette(activity)
    private val repository = ContactsRepository(activity)
    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val isNew = original.jmapId == null && !ContactsProvider.isProviderContactId(original.id)

    private lateinit var prefixField: EditText
    private lateinit var firstNameField: EditText
    private lateinit var middleNameField: EditText
    private lateinit var lastNameField: EditText
    private lateinit var suffixField: EditText
    private lateinit var nicknameField: EditText
    private lateinit var companyField: EditText
    private lateinit var departmentField: EditText
    private lateinit var jobTitleField: EditText
    private lateinit var roleField: EditText
    private lateinit var streetField: EditText
    private lateinit var localityField: EditText
    private lateinit var regionField: EditText
    private lateinit var postcodeField: EditText
    private lateinit var countryField: EditText
    private lateinit var notesField: EditText

    private lateinit var emailRows: LinearLayout
    private lateinit var phoneRows: LinearLayout
    private lateinit var categoryRow: LinearLayout

    private lateinit var avatarImage: ImageView
    private lateinit var avatarInitials: TextView
    private var photoBase64: String? = original.photoBase64

    private var source: ContactSource = original.source
    private val selectedCategories = original.categories.toMutableSet()
    private val customCategories = mutableListOf<String>()

    fun show() {
        val dialog = Dialog(activity)
        dialog.setContentView(buildForm(dialog))
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.attributes?.let { lp ->
            lp.width = (activity.resources.displayMetrics.widthPixels * 0.92f).toInt()
            lp.height = (activity.resources.displayMetrics.heightPixels * 0.88f).toInt()
            dialog.window?.attributes = lp
        }
    }

    // ---- form -----------------------------------------------------------------------------

    private fun buildForm(dialog: Dialog): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16 * density
                setColor(palette.surface)
            }
            setPadding(dp(20), dp(20), dp(20), dp(16))
        }

        root.addView(TextView(activity).apply {
            text = activity.getString(if (isNew) R.string.contacts_new else R.string.contacts_edit)
            setTextColor(palette.text)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        })

        val scroll = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            applyThemedScrollbar(palette.accent)
        }
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }

        // The backend is fixed once a contact exists: moving a card between backends would mean
        // deleting it on one side and recreating it on the other.
        if (isNew) {
            form.addView(caption(activity.getString(R.string.contacts_save_to)))
            form.addView(buildSourceSelector())
        }

        // Hints double as examples where the label alone is ambiguous (prefix, suffix, titles).
        prefixField = pillField(R.string.contacts_hint_prefix, original.prefix)
        firstNameField = pillField(R.string.contacts_field_first_name, original.firstName)
        lastNameField = pillField(R.string.contacts_field_last_name, original.lastName)
        suffixField = pillField(R.string.contacts_hint_suffix, original.suffix)
        middleNameField = pillField(R.string.contacts_field_middle_name, original.middleName)
        nicknameField = pillField(R.string.contacts_field_nickname, original.nickname)

        // Avatar on the left, the two names stacked on the right: the header of the card.
        form.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(buildAvatarPicker())
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(fieldRow(
                    R.string.contacts_field_first_name to firstNameField,
                    weights = floatArrayOf(1f)))
                addView(fieldRow(
                    R.string.contacts_field_last_name to lastNameField,
                    weights = floatArrayOf(1f)))
            })
        })
        // Keep the bubble in sync while the name is typed, so a new contact stops showing an
        // empty circle as soon as there is something to derive initials from.
        listOf(firstNameField, lastNameField, nicknameField).forEach { field ->
            field.addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) = refreshInitials()
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            })
        }

        form.addView(caption(activity.getString(R.string.contacts_section_email)))
        emailRows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        form.addView(emailRows)
        original.emails.ifEmpty { listOf(ContactEmail("")) }.forEach { addEmailRow(it) }
        form.addView(addButton(R.string.contacts_add_email) { addEmailRow(ContactEmail("")) })

        form.addView(caption(activity.getString(R.string.contacts_section_phone)))
        phoneRows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        form.addView(phoneRows)
        original.phones.ifEmpty { listOf(ContactPhone("")) }.forEach { addPhoneRow(it) }
        form.addView(addButton(R.string.contacts_add_phone) { addPhoneRow(ContactPhone("")) })

        // Rarely-filled name parts live behind their own fold so the top of the form stays to the
        // two fields most contacts actually need.
        val otherBody = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(fieldRow(
                R.string.contacts_field_prefix to prefixField,
                R.string.contacts_field_suffix to suffixField,
                weights = floatArrayOf(1f, 1f)))
            addView(fieldRow(
                R.string.contacts_field_middle_name to middleNameField,
                R.string.contacts_field_nickname to nicknameField,
                weights = floatArrayOf(1f, 1f)))
        }
        form.addView(collapsible(R.string.contacts_section_other_details, otherBody,
            expanded = listOf(original.prefix, original.suffix, original.middleName,
                original.nickname).any { it.isNotBlank() }))

        val org = original.organization ?: ContactOrganization()
        companyField = pillField(R.string.contacts_hint_company, org.companyName)
        departmentField = pillField(R.string.contacts_field_department, org.department)
        jobTitleField = pillField(R.string.contacts_hint_job_title, org.jobTitle)
        roleField = pillField(R.string.contacts_hint_role, org.role)
        val orgBody = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(fieldRow(
                R.string.contacts_field_company to companyField,
                R.string.contacts_field_department to departmentField,
                weights = floatArrayOf(1f, 1f)))
            addView(fieldRow(
                R.string.contacts_field_job_title to jobTitleField,
                R.string.contacts_field_role to roleField,
                weights = floatArrayOf(1f, 1f)))
        }
        form.addView(collapsible(R.string.contacts_section_organization, orgBody,
            expanded = !org.isEmpty()))

        val address = original.addresses.firstOrNull() ?: ContactAddress()
        streetField = pillField(R.string.contacts_field_street, address.street)
        localityField = pillField(R.string.contacts_field_locality, address.locality)
        regionField = pillField(R.string.contacts_field_region, address.region)
        postcodeField = pillField(R.string.contacts_field_postcode, address.postcode)
        countryField = pillField(R.string.contacts_field_country, address.country)
        val addressBody = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(fieldRow(R.string.contacts_field_street to streetField, weights = floatArrayOf(1f)))
            addView(fieldRow(
                R.string.contacts_field_locality to localityField,
                R.string.contacts_field_postcode to postcodeField,
                weights = floatArrayOf(1.4f, 0.8f)))
            addView(fieldRow(
                R.string.contacts_field_region to regionField,
                R.string.contacts_field_country to countryField,
                weights = floatArrayOf(1f, 1f)))
        }
        form.addView(collapsible(R.string.contacts_section_address, addressBody,
            expanded = !address.isEmpty()))

        form.addView(caption(activity.getString(R.string.contacts_section_categories)))
        categoryRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        form.addView(android.widget.HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(categoryRow)
        })
        renderCategories()
        form.addView(addButton(R.string.contacts_add_category) { promptCustomCategory() })

        notesField = notesBox(original.notes)
        val notesBody = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, 0)
            addView(notesField)
        }
        form.addView(collapsible(R.string.contacts_section_notes, notesBody,
            expanded = original.notes.isNotBlank()))

        scroll.addView(form)
        root.addView(scroll)
        root.addView(buildActions(dialog))
        return root
    }

    /**
     * Tappable avatar bubble: shows the contact photo when there is one, the initials otherwise,
     * and opens the system photo picker (no Google Photos dependency) to replace it. Long-press
     * clears the photo again.
     */
    private fun buildAvatarPicker(): View {
        val size = dp(72)
        val initials = TextView(activity).apply {
            text = original.initials
            setTextColor(palette.onAccent)
            textSize = 24f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(palette.accent)
            }
            layoutParams = FrameLayout.LayoutParams(size, size)
        }
        val image = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(size, size)
        }
        avatarImage = image
        avatarInitials = initials
        renderAvatar()

        return FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).also {
                it.marginEnd = dp(14)
                it.topMargin = dp(10)
            }
            addView(initials)
            addView(image)
            contentDescription = activity.getString(R.string.contacts_avatar_change)
            setOnClickListener { pickPhoto() }
            setOnLongClickListener {
                photoBase64 = null
                renderAvatar()
                true
            }
        }
    }

    private fun pickPhoto() {
        activity.pendingContactPhoto = { uri ->
            activity.lifecycleScope.launch {
                val encoded = withContext(Dispatchers.IO) {
                    ContactAvatars.fromPickedImage(activity, uri)
                }
                if (encoded == null) {
                    notify(R.string.contacts_avatar_failed)
                } else {
                    photoBase64 = encoded
                    renderAvatar()
                }
            }
        }
        activity.pickContactPhotoLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    /** Recomputes the bubble letters from the name fields as they are typed. */
    private fun refreshInitials() {
        avatarInitials.text = original.copy(
            firstName = firstNameField.text.toString().trim(),
            lastName = lastNameField.text.toString().trim(),
            nickname = nicknameField.text.toString().trim()
        ).initials
    }

    private fun renderAvatar() {
        val bitmap = ContactAvatars.decode(photoBase64)
        if (bitmap == null) {
            avatarImage.visibility = View.GONE
            avatarImage.setImageDrawable(null)
            avatarInitials.visibility = View.VISIBLE
        } else {
            avatarImage.setImageBitmap(bitmap)
            avatarImage.visibility = View.VISIBLE
            avatarInitials.visibility = View.GONE
        }
    }

    /** One row of captioned pill fields, sharing the width by [weights]. */
    private fun fieldRow(
        vararg fields: Pair<Int, EditText>,
        weights: FloatArray
    ): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
            setPadding(0, dp(8), 0, 0)
        }
        fields.forEachIndexed { index, (captionRes, field) ->
            val column = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, weights.getOrElse(index) { 1f }
                ).also { if (index < fields.lastIndex) it.marginEnd = dp(8) }
            }
            column.addView(caption(activity.getString(captionRes)))
            column.addView(field)
            row.addView(column)
        }
        return row
    }

    /** Section header that folds its body away; keeps the form short on first open. */
    private fun collapsible(titleRes: Int, body: View, expanded: Boolean): View {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        val chevron = ImageView(activity).apply {
            setImageResource(
                if (expanded) R.drawable.ic_lucide_chevron_down else R.drawable.ic_lucide_chevron_right)
            imageTintList = android.content.res.ColorStateList.valueOf(palette.accent)
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).also { it.marginEnd = dp(8) }
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            addView(chevron)
            addView(TextView(activity).apply {
                text = activity.getString(titleRes).uppercase()
                setTextColor(palette.secondaryText)
                textSize = 11f
                setTypeface(typeface, Typeface.BOLD)
            })
            setOnClickListener {
                val nowVisible = body.visibility != View.VISIBLE
                body.visibility = if (nowVisible) View.VISIBLE else View.GONE
                chevron.setImageResource(
                    if (nowVisible) R.drawable.ic_lucide_chevron_down
                    else R.drawable.ic_lucide_chevron_right)
            }
        }
        body.visibility = if (expanded) View.VISIBLE else View.GONE
        container.addView(header)
        container.addView(body)
        return container
    }

    private fun buildSourceSelector(): View {
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
        }
        listOf(
            ContactSource.JMAP to R.string.contacts_source_jmap,
            ContactSource.DAVX5 to R.string.contacts_source_davx5
        ).forEach { (value, labelRes) ->
            bar.addView(TextView(activity).apply {
                text = activity.getString(labelRes)
                textSize = 13f
                setPadding(dp(14), dp(7), dp(14), dp(7))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.marginEnd = dp(8) }
                tag = value
                setOnClickListener {
                    source = value
                    if (value == ContactSource.DAVX5 &&
                        !ContactsProvider.hasWritePermission(activity)) {
                        activity.requestContactsPermissions { }
                    }
                    styleSourceChips(bar)
                }
            })
        }
        styleSourceChips(bar)
        return bar
    }

    private fun styleSourceChips(bar: LinearLayout) {
        for (i in 0 until bar.childCount) {
            val chip = bar.getChildAt(i) as? TextView ?: continue
            val selected = chip.tag == source
            chip.setTextColor(if (selected) palette.onAccent else palette.secondaryText)
            chip.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 999 * density
                setColor(if (selected) palette.accent else palette.background)
            }
        }
    }

    private fun buildActions(dialog: Dialog): View {
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(8), 0, 0)
        }
        if (!isNew) {
            val danger = ContextCompat.getColor(activity, R.color.contacts_delete_red)
            bar.addView(textButton(activity.getString(R.string.contacts_delete), danger) {
                activity.lifecycleScope.launch {
                    val deleted = repository.delete(original)
                    notify(if (deleted) R.string.contacts_deleted else R.string.contacts_delete_failed)
                    if (deleted) { dialog.dismiss(); onSaved() }
                }
            })
        }
        bar.addView(textButton(activity.getString(R.string.contacts_cancel), palette.secondaryText) {
            dialog.dismiss()
        })
        bar.addView(textButton(activity.getString(R.string.contacts_save), palette.accent) {
            save(dialog)
        })
        return bar
    }

    private fun save(dialog: Dialog) {
        val contact = collect()
        if (contact.displayName == "(no name)") {
            notify(R.string.contacts_need_name)
            return
        }
        if (contact.source == ContactSource.DAVX5 && !ContactsProvider.hasWritePermission(activity)) {
            activity.requestContactsPermissions { }
            notify(R.string.contacts_need_permission)
            return
        }
        activity.lifecycleScope.launch {
            val saved = repository.save(contact)
            if (saved == null) {
                notify(R.string.contacts_save_failed)
                return@launch
            }
            notify(R.string.contacts_saved)
            dialog.dismiss()
            onSaved()
        }
    }

    private fun collect(): Contact {
        val organization = ContactOrganization(
            companyName = companyField.text.toString().trim(),
            department = departmentField.text.toString().trim(),
            jobTitle = jobTitleField.text.toString().trim(),
            role = roleField.text.toString().trim()
        )
        val address = ContactAddress(
            street = streetField.text.toString().trim(),
            locality = localityField.text.toString().trim(),
            region = regionField.text.toString().trim(),
            postcode = postcodeField.text.toString().trim(),
            country = countryField.text.toString().trim(),
            context = original.addresses.firstOrNull()?.context ?: ContactContext.PRIVATE
        )
        return original.copy(
            prefix = prefixField.text.toString().trim(),
            firstName = firstNameField.text.toString().trim(),
            middleName = middleNameField.text.toString().trim(),
            lastName = lastNameField.text.toString().trim(),
            suffix = suffixField.text.toString().trim(),
            nickname = nicknameField.text.toString().trim(),
            emails = collectRows(emailRows, CONTEXT_OPTIONS, ContactContext.PRIVATE) { v, c ->
                ContactEmail(v, c)
            },
            phones = collectRows(phoneRows, PHONE_OPTIONS, ContactPhoneFeature.MOBILE) { v, f ->
                ContactPhone(v, f)
            },
            organization = organization.takeIf { !it.isEmpty() },
            addresses = if (address.isEmpty()) emptyList() else listOf(address),
            categories = selectedCategories.toList(),
            notes = notesField.text.toString().trim(),
            photoBase64 = photoBase64,
            source = source
        )
    }

    /** Reads the value + dropdown-option pair each dynamic row keeps in its children. */
    private fun <T, O> collectRows(
        container: LinearLayout,
        options: List<O>,
        fallback: O,
        build: (String, O) -> T
    ): List<T> {
        val out = mutableListOf<T>()
        for (i in 0 until container.childCount) {
            val row = container.getChildAt(i) as? LinearLayout ?: continue
            val input = row.findViewWithTag<EditText>(TAG_VALUE) ?: continue
            val value = input.text.toString().trim()
            if (value.isBlank()) continue
            val spinner = row.findViewWithTag<Spinner>(TAG_CONTEXT)
            out += build(value, options.getOrElse(spinner?.selectedItemPosition ?: 0) { fallback })
        }
        return out
    }

    // ---- dynamic rows ----------------------------------------------------------------------

    private fun addEmailRow(email: ContactEmail) {
        emailRows.addView(valueRow(
            emailRows, email.address, R.string.contacts_field_email,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            optionSpinner(CONTEXT_OPTIONS.map { it.label },
                CONTEXT_OPTIONS.indexOf(email.context))))
        refreshRemoveButtons(emailRows)
    }

    private fun addPhoneRow(phone: ContactPhone) {
        phoneRows.addView(valueRow(
            phoneRows, phone.number, R.string.contacts_field_phone,
            InputType.TYPE_CLASS_PHONE,
            optionSpinner(PHONE_OPTIONS.map { it.label },
                PHONE_OPTIONS.indexOf(phone.feature))))
        refreshRemoveButtons(phoneRows)
    }

    /**
     * The first email/phone is the primary one and is never removable, so its X is dropped from the
     * layout entirely (GONE, not INVISIBLE) and the row sits flush with the rest of the form.
     * Re-run after every add/remove so the X follows the current row order.
     */
    private fun refreshRemoveButtons(container: LinearLayout) {
        for (i in 0 until container.childCount) {
            val row = container.getChildAt(i) as? LinearLayout ?: continue
            row.findViewWithTag<ImageView>(TAG_REMOVE)?.visibility =
                if (i == 0) View.GONE else View.VISIBLE
        }
    }

    /** Remove button, the input itself, and the row's kind dropdown (context or phone feature). */
    private fun valueRow(
        parent: LinearLayout,
        value: String,
        hintRes: Int,
        inputType: Int,
        kindSpinner: Spinner
    ): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        row.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_lucide_x)
            tag = TAG_REMOVE
            imageTintList = android.content.res.ColorStateList.valueOf(palette.secondaryText)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).also { it.marginEnd = dp(8) }
            contentDescription = activity.getString(R.string.contacts_remove)
            setOnClickListener {
                parent.removeView(row)
                refreshRemoveButtons(parent)
            }
        })
        row.addView(pillField(hintRes, value, inputType).apply {
            tag = TAG_VALUE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(kindSpinner.apply {
            tag = TAG_CONTEXT
            layoutParams = LinearLayout.LayoutParams(dp(120), ViewGroup.LayoutParams.WRAP_CONTENT)
                .also { it.marginStart = dp(4) }
        })
        return row
    }

    /**
     * Dropdown styled like the calendar's overflow popup: rounded card on a darkened accent, text
     * in [CalendarTheme.Palette.onAccent], and a raised elevation instead of the stock flat list.
     */
    private fun optionSpinner(labels: List<String>, selectedIndex: Int): Spinner {
        val popupBackground = activity.darkenColor(palette.accent)
        val spinner = Spinner(activity)
        val adapter = object : ArrayAdapter<String>(
            activity, android.R.layout.simple_spinner_item, labels
        ) {
            override fun getView(p: Int, cv: View?, parent: ViewGroup): View =
                (super.getView(p, cv, parent) as TextView).apply {
                    setTextColor(palette.text); textSize = 14f
                }
            override fun getDropDownView(p: Int, cv: View?, parent: ViewGroup): View =
                (super.getDropDownView(p, cv, parent) as TextView).apply {
                    setTextColor(palette.onAccent)
                    textSize = 14f
                    setBackgroundColor(Color.TRANSPARENT)
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(selectedIndex.coerceAtLeast(0))
        spinner.setPopupBackgroundDrawable(GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8).toFloat()
            setColor(popupBackground)
        })
        spinner.dropDownVerticalOffset = dp(4)
        spinner.elevation = 8 * density
        spinner.background?.setColorFilter(palette.accent, PorterDuff.Mode.SRC_IN)
        return spinner
    }

    // ---- categories ------------------------------------------------------------------------

    private fun renderCategories() {
        categoryRow.removeAllViews()
        val all = (ContactCategories.DEFAULTS + customCategories + selectedCategories).distinct()
        all.forEach { category ->
            val selected = category in selectedCategories
            categoryRow.addView(TextView(activity).apply {
                text = category
                textSize = 13f
                setPadding(dp(14), dp(7), dp(14), dp(7))
                setTextColor(if (selected) palette.onAccent else palette.secondaryText)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 999 * density
                    setColor(if (selected) palette.accent else palette.background)
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.marginEnd = dp(8) }
                setOnClickListener {
                    if (selected) selectedCategories -= category else selectedCategories += category
                    renderCategories()
                }
            })
        }
    }

    private fun promptCustomCategory() {
        val input = pillField(R.string.contacts_field_category, "")
        val dialog = Dialog(activity)
        val wrapper = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16 * density
                setColor(palette.surface)
            }
            setPadding(dp(20), dp(20), dp(20), dp(12))
            addView(input)
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                addView(textButton(activity.getString(R.string.contacts_cancel), palette.secondaryText) {
                    dialog.dismiss()
                })
                addView(textButton(activity.getString(R.string.contacts_add), palette.accent) {
                    val value = input.text.toString().trim()
                    if (value.isNotBlank()) {
                        customCategories += value
                        selectedCategories += value
                        renderCategories()
                    }
                    dialog.dismiss()
                })
            })
        }
        dialog.setContentView(wrapper)
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.attributes?.let { lp ->
            lp.width = (activity.resources.displayMetrics.widthPixels * 0.85f).toInt()
            dialog.window?.attributes = lp
        }
    }

    // ---- small builders --------------------------------------------------------------------

    /** Rounded input box matching the calendar event editor (theme fill + subtle border). */
    private fun pillField(
        hintRes: Int,
        value: String,
        inputTypeFlags: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
    ): EditText = EditText(activity).apply {
        setText(value)
        hint = activity.getString(hintRes)
        setTextColor(palette.text)
        setHintTextColor(palette.secondaryText)
        textSize = 14f
        inputType = inputTypeFlags
        setSingleLine()
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(palette.background)
            setStroke(dp(1), CalendarTimelineView.adjustAlpha(palette.secondaryText, 0.4f))
        }
        setPadding(dp(14), dp(11), dp(14), dp(11))
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /** Multi-line notes input: fixed 4-line box that wraps and scrolls inside itself. */
    private fun notesBox(value: String): EditText = EditText(activity).apply {
        setText(value)
        hint = activity.getString(R.string.contacts_field_notes)
        setTextColor(palette.text)
        setHintTextColor(palette.secondaryText)
        textSize = 14f
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setSingleLine(false)
        setHorizontallyScrolling(false)
        setLines(4)
        maxLines = 4
        gravity = Gravity.TOP or Gravity.START
        isVerticalScrollBarEnabled = true
        movementMethod = android.text.method.ScrollingMovementMethod.getInstance()
        applyThemedScrollbar(palette.accent)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(palette.background)
            setStroke(dp(1), CalendarTimelineView.adjustAlpha(palette.secondaryText, 0.4f))
        }
        setPadding(dp(14), dp(11), dp(14), dp(11))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun caption(text: String): TextView = TextView(activity).apply {
        this.text = text.uppercase()
        setTextColor(palette.secondaryText)
        textSize = 10f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(4), dp(10), 0, dp(4))
    }

    private fun addButton(labelRes: Int, onClick: () -> Unit): TextView =
        TextView(activity).apply {
            text = activity.getString(labelRes)
            textSize = 15f
            setTextColor(palette.accent)
            setPadding(dp(4), dp(10), 0, dp(2))
            setOnClickListener { onClick() }
        }

    private fun textButton(label: String, color: Int, onClick: () -> Unit): TextView =
        TextView(activity).apply {
            text = label
            textSize = 14f
            setTextColor(color)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener { onClick() }
        }

    /** In-app pill notification, same one the mail list uses for "Moved to Archive". */
    private fun notify(messageRes: Int) {
        activity.showThemedSnackbar(activity.getString(messageRes))
    }

    private companion object {
        const val TAG_VALUE = "contact_value"
        const val TAG_CONTEXT = "contact_context"
        const val TAG_REMOVE = "contact_remove"
        val CONTEXT_OPTIONS = listOf(
            ContactContext.PRIVATE, ContactContext.WORK, ContactContext.OTHER)
        val PHONE_OPTIONS = listOf(
            ContactPhoneFeature.MOBILE, ContactPhoneFeature.TELEPHONE, ContactPhoneFeature.FAX)
    }
}
