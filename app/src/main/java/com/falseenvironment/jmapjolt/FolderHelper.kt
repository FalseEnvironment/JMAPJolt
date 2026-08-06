package com.falseenvironment.jmapjolt

import android.content.res.ColorStateList
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Local-only display override (name/color) for a user-created JMAP mailbox (no server role). */
data class FolderMeta(
    val mailboxId: String,
    var displayName: String?,
    var colorHex: String
)

// ---------------------------------------------------------------------------
// Persistence
// ---------------------------------------------------------------------------

internal fun MainActivity.folderMetaForAccount(accountEmail: String): List<FolderMeta> {
    if (accountEmail.isBlank()) return emptyList()
    val cached = accountFolderMetaCache[accountEmail]
    if (cached != null) return cached

    val key = "folder_meta_json_$accountEmail"
    val raw = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        .getString(key, null) ?: return emptyList()
    val list = mutableListOf<FolderMeta>()
    try {
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("mailboxId")
            if (id.isBlank()) continue
            list.add(FolderMeta(
                id,
                o.optString("displayName", "").takeIf { it.isNotBlank() },
                o.optString("color", "#8A8A8A")
            ))
        }
    } catch (e: Exception) {
        Log.e(MainActivity.TAG, "folderMetaForAccount failed", e)
    }
    accountFolderMetaCache[accountEmail] = list
    return list
}

internal fun MainActivity.loadFolderMeta() {
    folderMeta.clear()
    val email = currentAccountEmail ?: return
    folderMeta.addAll(folderMetaForAccount(email))
}

internal fun MainActivity.saveFolderMeta() {
    val email = currentAccountEmail ?: return
    val arr = JSONArray()
    folderMeta.forEach { m ->
        arr.put(JSONObject().apply {
            put("mailboxId", m.mailboxId)
            put("displayName", m.displayName ?: "")
            put("color", m.colorHex)
        })
    }
    getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        .edit().putString("folder_meta_json_$email", arr.toString()).apply()
    accountFolderMetaCache[email] = folderMeta.toList()
}

internal fun MainActivity.folderMetaFor(mailboxId: String): FolderMeta? =
    folderMeta.find { it.mailboxId == mailboxId }

/** Display name for a subfolder: local override if set, else the server mailbox name. */
internal fun MainActivity.folderDisplayName(mbox: JMapClient.MailboxInfo): String =
    folderMetaFor(mbox.id)?.displayName?.takeIf { it.isNotBlank() } ?: mbox.name

/** Icon tint for a subfolder: local override color if set, else the default drawer icon tint. */
internal fun MainActivity.folderIconColor(mailboxId: String, defaultTint: Int): Int =
    folderMetaFor(mailboxId)?.colorHex?.let { runCatching { it.toColorInt() }.getOrNull() } ?: defaultTint

// ---------------------------------------------------------------------------
// Folder editor (settings > Labels section): rename display, recolor, reorder
// ---------------------------------------------------------------------------

internal fun MainActivity.showFolderEditorDialog() {
    val dp = resources.displayMetrics.density
    val bgColor = getDialogBackgroundColor()
    val textColor = if (currentTheme == "light") "#212121".toColorInt() else Color.WHITE
    val secondaryColor = if (currentTheme == "light") "#757575".toColorInt() else "#BDBDBD".toColorInt()
    val disabledColor = if (currentTheme == "light") "#CCCCCC".toColorInt() else "#555555".toColorInt()
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
    outer.addView(TextView(this).apply {
        text = "Edit folders"
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

    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }

    fun iconBtn(res: Int, tint: Int, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
        val sz = (34 * dp).toInt()
        layoutParams = LinearLayout.LayoutParams(sz, sz)
        val p = (7 * dp).toInt()
        setPadding(p, p, p, p)
        scaleType = ImageView.ScaleType.FIT_CENTER
        isClickable = true; isFocusable = true
        background = ContextCompat.getDrawable(
            this@showFolderEditorDialog,
            android.util.TypedValue().also {
                theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true)
            }.resourceId
        )
        setOnClickListener { onClick() }
    }

    fun rebuildRows() {
        list.removeAllViews()
        val folders = (mailboxCache?.filter { it.role == null } ?: emptyList())
            .sortedBy { subfolderDisplayOrder.indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        if (folders.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No folders yet"
                textSize = 14f
                setTextColor(secondaryColor)
                gravity = Gravity.CENTER
                setPadding(0, (24 * dp).toInt(), 0, (12 * dp).toInt())
            })
        }
        folders.forEachIndexed { index, mbox ->
            val meta = folderMetaFor(mbox.id) ?: FolderMeta(mbox.id, null, "#8A8A8A").also { folderMeta.add(it) }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (52 * dp).toInt()
                )
                setPadding((20 * dp).toInt(), 0, (12 * dp).toInt(), 0)
            }
            // Color dot: tap to recolor with the hue wheel.
            row.addView(View(this).apply {
                val sz = (24 * dp).toInt()
                layoutParams = LinearLayout.LayoutParams(sz, sz).also { it.marginEnd = (14 * dp).toInt() }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(meta.colorHex.toColorInt())
                }
                isClickable = true; isFocusable = true
                setOnClickListener {
                    showLabelColorWheelDialog(folderDisplayName(mbox), meta.colorHex) { hex ->
                        meta.colorHex = hex
                        saveFolderMeta()
                        rebuildDrawerMenuPublic()
                        rebuildRows()
                    }
                }
            })
            row.addView(TextView(this).apply {
                text = folderDisplayName(mbox)
                textSize = 15f
                setTextColor(textColor)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(iconBtn(
                R.drawable.ic_lucide_chevron_down,
                if (index > 0) textColor else disabledColor
            ) {
                if (index > 0) {
                    val prevId = folders[index - 1].id
                    subfolderDisplayOrder.remove(mbox.id)
                    subfolderDisplayOrder.add(subfolderDisplayOrder.indexOf(prevId), mbox.id)
                    saveSubfolderOrder(); rebuildDrawerMenuPublic(); rebuildRows()
                }
            }.apply { rotation = 180f })
            row.addView(iconBtn(
                R.drawable.ic_lucide_chevron_down,
                if (index < folders.size - 1) textColor else disabledColor
            ) {
                if (index < folders.size - 1) {
                    val nextId = folders[index + 1].id
                    subfolderDisplayOrder.remove(mbox.id)
                    subfolderDisplayOrder.add(subfolderDisplayOrder.indexOf(nextId) + 1, mbox.id)
                    saveSubfolderOrder(); rebuildDrawerMenuPublic(); rebuildRows()
                }
            })
            row.addView(iconBtn(R.drawable.ic_lucide_pencil, accentInt) {
                showEditFolderDialog(mbox, meta) { rebuildRows() }
            })
            row.addView(iconBtn(R.drawable.ic_lucide_trash, "#D32F2F".toColorInt()) {
                showThemedConfirmDialog(
                    title = "Delete folder",
                    message = "Delete \"${folderDisplayName(mbox)}\"? Emails inside will also be deleted.",
                    confirmLabel = "Delete",
                    isDangerous = true
                ) {
                    val account = connectedAccount
                    if (account == null) {
                        rebuildRows()
                        return@showThemedConfirmDialog
                    }
                    lifecycleScope.launch {
                        val ok = jmapClient.deleteMailbox(account, mbox.id)
                        if (ok) {
                            mailboxCache = mailboxCache?.filterNot { it.id == mbox.id }
                            folderMeta.remove(meta)
                            saveFolderMeta()
                            subfolderDisplayOrder.remove(mbox.id)
                            saveSubfolderOrder()
                            if (subfolderNavIds[selectedFolder] == mbox.id) {
                                selectedFolder = R.id.nav_inbox
                            }
                            rebuildDrawerMenuPublic()
                        } else {
                            showThemedSnackbar("Could not delete folder")
                        }
                        rebuildRows()
                    }
                }
            })
            list.addView(row)
        }

        // Accent "create" affordance, mirroring the label editor.
        val createRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (52 * dp).toInt()
            )
            setPadding((20 * dp).toInt(), 0, (20 * dp).toInt(), 0)
            isClickable = true; isFocusable = true
            background = ContextCompat.getDrawable(
                this@showFolderEditorDialog,
                android.util.TypedValue().also {
                    theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
                }.resourceId
            )
            setOnClickListener { showCreateFolderDialog { rebuildRows() } }
        }
        createRow.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_lucide_plus)
            imageTintList = ColorStateList.valueOf(accentInt)
            val sz = (20 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz).also { it.marginEnd = (16 * dp).toInt() }
            scaleType = ImageView.ScaleType.FIT_CENTER
        })
        createRow.addView(TextView(this).apply {
            text = "Create new folder"
            textSize = 15f
            setTextColor(accentInt)
            typeface = Typeface.DEFAULT_BOLD
        })
        list.addView(createRow)
    }
    rebuildRows()

    scroll.addView(list)
    outer.addView(scroll)

    val dialog = AlertDialog.Builder(this).setView(outer).create()
    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    dialog.show()
}

/** Name + color prompt that provisions a new user folder (mailbox) on the server. */
internal fun MainActivity.showCreateFolderDialog(onCreated: () -> Unit) {
    val dp = resources.displayMetrics.density
    val textColor = if (currentTheme == "light") "#212121".toColorInt() else Color.WHITE
    val hintColor = if (currentTheme == "light") "#9E9E9E".toColorInt() else "#616161".toColorInt()
    val pendingHsv = floatArrayOf(210f, 0.75f, 0.95f)

    val nameInput = EditText(this).apply {
        hint = "Folder name"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        filters = arrayOf(android.text.InputFilter.LengthFilter(30), noArabicFilter())
        setTextColor(textColor)
        setHintTextColor(hintColor)
        backgroundTintList = ColorStateList.valueOf(hintColor)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = (16 * dp).toInt() }
    }
    val wheel = buildHueWheel(pendingHsv, 200)
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val p = (20 * dp).toInt()
        setPadding(p, (14 * dp).toInt(), p, (4 * dp).toInt())
        addView(nameInput)
        addView(wheel.also { (it.layoutParams as LinearLayout.LayoutParams).bottomMargin = (16 * dp).toInt() })
        addView(buildPresetSwatchRow(pendingHsv, wheel))
    }

    // Returning false keeps the dialog open on an empty/duplicate name.
    showCardDialog("New folder", root, "Create") {
        val name = nameInput.text.toString().trim()
        if (name.isEmpty()) {
            nameInput.error = "Name required"
            return@showCardDialog false
        }
        if (mailboxCache?.any { it.name.equals(name, ignoreCase = true) } == true) {
            nameInput.error = "Folder already exists"
            return@showCardDialog false
        }
        val account = connectedAccount
        if (account == null) {
            showThemedSnackbar("Not connected")
            return@showCardDialog true
        }
        val colorHex = hsvHex(pendingHsv)
        lifecycleScope.launch {
            val id = jmapClient.createMailbox(account, name, null)
            if (id == null) {
                showThemedSnackbar("Could not create folder")
            } else {
                mailboxCache = (mailboxCache ?: emptyList()) +
                    JMapClient.MailboxInfo(id, name, null)
                folderMeta.add(FolderMeta(id, null, colorHex))
                saveFolderMeta()
                subfolderDisplayOrder.add(id)
                saveSubfolderOrder()
                rebuildDrawerMenuPublic()
            }
            onCreated()
        }
        true
    }
}

internal fun MainActivity.showEditFolderDialog(
    mbox: JMapClient.MailboxInfo,
    meta: FolderMeta,
    onSaved: () -> Unit
) {
    val dp = resources.displayMetrics.density
    val textColor = if (currentTheme == "light") "#212121".toColorInt() else Color.WHITE
    val hintColor = if (currentTheme == "light") "#9E9E9E".toColorInt() else "#616161".toColorInt()

    val pendingHsv = FloatArray(3).also { hsv ->
        Color.colorToHSV(
            runCatching { meta.colorHex.toColorInt() }.getOrDefault("#8A8A8A".toColorInt()),
            hsv
        )
    }

    val nameInput = EditText(this).apply {
        hint = mbox.name
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        filters = arrayOf(android.text.InputFilter.LengthFilter(30), noArabicFilter())
        setText(folderDisplayName(mbox))
        setTextColor(textColor)
        setHintTextColor(hintColor)
        backgroundTintList = ColorStateList.valueOf(hintColor)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = (16 * dp).toInt() }
    }
    val wheel = buildHueWheel(pendingHsv, 200)
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val p = (20 * dp).toInt()
        setPadding(p, (14 * dp).toInt(), p, (4 * dp).toInt())
        addView(nameInput)
        addView(wheel.also { (it.layoutParams as LinearLayout.LayoutParams).bottomMargin = (16 * dp).toInt() })
        addView(buildPresetSwatchRow(pendingHsv, wheel))
    }

    showCardDialog("Edit folder", root, "Save") {
        val name = nameInput.text.toString().trim()
        meta.displayName = if (name.isEmpty() || name == mbox.name) null else name
        meta.colorHex = hsvHex(pendingHsv)
        saveFolderMeta()
        rebuildDrawerMenuPublic()
        onSaved()
        true
    }
}
