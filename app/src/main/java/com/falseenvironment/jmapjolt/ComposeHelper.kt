package com.falseenvironment.jmapjolt

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.text.Editable
import android.text.Html
import android.text.Layout
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.AlignmentSpan
import android.text.style.BulletSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.chip.Chip
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal fun MainActivity.setupComposeView() {
    fabCompose.setOnClickListener { showComposeView() }

    composeAttachButton.setOnClickListener { showAttachMenu() }

    composeSendButton.setOnClickListener { performSend() }
    topBarSendButton.setOnClickListener { performSend() }

    composeBodyInput.addTextChangedListener(FormatTextWatcher(this))
    buildFormatToolbar()
}

internal fun MainActivity.performSend() {
    val fromEmail = selectedFromEmail.ifBlank { null }
    val currentToText = composeToInput.text.toString().trim()
    if (currentToText.isNotBlank()) addRecipientChip(currentToText)
    val to = recipientEmails.joinToString(", ")
    val subject = composeSubjectInput.text.toString()
    @Suppress("DEPRECATION")
    val userHtml = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
        Html.toHtml(composeBodyInput.text, Html.TO_HTML_PARAGRAPH_LINES_INDIVIDUAL)
    else
        Html.toHtml(composeBodyInput.text)
    // Append the faithful original-message HTML (reply/forward quote) verbatim.
    val body = userHtml + (pendingQuoteHtml ?: "")

    if (fromEmail == null || recipientEmails.isEmpty()) {
        android.widget.Toast.makeText(this, "Please provide 'From' and 'To'", android.widget.Toast.LENGTH_SHORT).show()
        return
    }

    val accountToUse = savedAccounts.find { it.email == fromEmail }?.let {
        JMapClient.ConnectedAccount(
            email = it.email,
            password = it.password,
            sessionUrl = it.sessionUrl,
            apiUrl = it.apiUrl,
            accountId = it.accountId
        )
    } ?: connectedAccount

    if (accountToUse == null) {
        android.widget.Toast.makeText(this, "No active account", android.widget.Toast.LENGTH_SHORT).show()
        return
    }

    val jmapAttachments = pendingAttachments.mapNotNull { att ->
        try {
            val bytes = contentResolver.openInputStream(att.uri)?.use { it.readBytes() } ?: return@mapNotNull null
            JMapClient.Attachment(att.name, att.mimeType, att.size, bytes)
        } catch (e: Exception) { null }
    }

    val oldDraftId = editingDraftId

    lifecycleScope.launch(Dispatchers.Main) {
        topBarSendButton.isEnabled = false
        val success = jmapClient.sendEmail(accountToUse, to, subject, body, "text/html", jmapAttachments)
        topBarSendButton.isEnabled = true
        if (success) {
            if (oldDraftId != null) jmapClient.destroyEmail(accountToUse, oldDraftId)
            hideCompose()
            composeToInput.text.clear()
            composeSubjectInput.text.clear()
            composeBodyInput.text.clear()
            Snackbar.make(drawerLayout, "Email sent", Snackbar.LENGTH_SHORT).show()
        } else {
            Snackbar.make(drawerLayout, "Failed to send email", Snackbar.LENGTH_LONG).show()
        }
    }
}

/** Opens the compose editor pre-filled with a draft's recipient, subject and body. */
internal fun MainActivity.openDraftForEdit(email: DisplayEmail) {
    showComposeView()
    editingDraftId = email.id
    email.toEmail.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { addRecipientChip(it) }
    composeSubjectInput.setText(
        if (email.subject == "(No Subject)") "" else email.subject
    )
    @Suppress("DEPRECATION")
    val bodySpanned = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
        Html.fromHtml(email.fullBody, Html.FROM_HTML_MODE_LEGACY)
    else
        Html.fromHtml(email.fullBody)
    composeBodyInput.setText(bodySpanned)
    composeBodyInput.requestFocus()
}

/** Picks the From account for a reply/forward, defaulting to the account that owns the email. */
private fun MainActivity.selectComposeAccount(accountEmail: String) {
    if (accountEmail.isBlank()) return
    val match = savedAccounts.firstOrNull { it.email.equals(accountEmail, ignoreCase = true) } ?: return
    selectedFromEmail = match.email
    composeFromText.text = match.email
}

private fun composeQuoteDate(receivedAt: Long): String =
    java.text.SimpleDateFormat("MMM d, yyyy 'at' h:mm a", java.util.Locale.getDefault())
        .format(java.util.Date(receivedAt))

/** Inline style for the quoted-html island, so it renders even in clients that strip CSS classes. */
private const val QUOTE_ISLAND_STYLE = "border-left:2px solid #c5c5c5;padding-left:12px;margin-top:8px"

internal fun MainActivity.setPendingQuote(html: String, label: String) {
    pendingQuoteHtml = html
    quoteIndicatorLabel.text = label
    quoteIndicatorRow.visibility = View.VISIBLE
    quoteIndicatorDivider.visibility = View.VISIBLE
}

internal fun MainActivity.clearPendingQuote() {
    pendingQuoteHtml = null
    quoteIndicatorRow.visibility = View.GONE
    quoteIndicatorDivider.visibility = View.GONE
}

/** Opens compose pre-filled to reply to [email] (works for any account in the unified inbox). */
internal fun MainActivity.startReply(email: DisplayEmail) {
    showComposeView()
    selectComposeAccount(email.accountEmail)
    if (email.fromEmail.isNotBlank()) addRecipientChip(email.fromEmail)
    val base = if (email.subject == "(No Subject)") "" else email.subject
    composeSubjectInput.setText(if (base.startsWith("Re:", ignoreCase = true)) base else "Re: $base")
    val sender = android.text.TextUtils.htmlEncode(email.from.ifBlank { email.fromEmail })
    val header = "On ${composeQuoteDate(email.receivedAt)}, $sender wrote:"
    val island = "<br><br><div>$header</div>" +
        "<div data-quoted-html=\"\" class=\"quoted-html-island\" style=\"$QUOTE_ISLAND_STYLE\">" +
        sanitizeEmailHtml(email.fullBody) + "</div>"
    setPendingQuote(island, "Quoted: ${email.from.ifBlank { email.fromEmail }}")
    composeBodyInput.setText("")
    composeBodyInput.requestFocus()
}

/** Opens compose pre-filled to forward [email] (works for any account in the unified inbox). */
internal fun MainActivity.startForward(email: DisplayEmail) {
    showComposeView()
    selectComposeAccount(email.accountEmail)
    val base = if (email.subject == "(No Subject)") "" else email.subject
    val alreadyFwd = base.startsWith("Fwd:", ignoreCase = true) || base.startsWith("Fw:", ignoreCase = true)
    composeSubjectInput.setText(if (alreadyFwd) base else "Fwd: $base")
    fun enc(s: String) = android.text.TextUtils.htmlEncode(s)
    val island = "<br><br><div data-forwarded-html=\"\" class=\"quoted-html-island\" style=\"$QUOTE_ISLAND_STYLE\">" +
        "<div>---------- Forwarded message ----------</div>" +
        "<div>From: ${enc(email.from.ifBlank { email.fromEmail })}</div>" +
        "<div>Date: ${composeQuoteDate(email.receivedAt)}</div>" +
        "<div>Subject: ${enc(email.subject)}</div>" +
        "<div>To: ${enc(email.toEmail)}</div><br>" +
        sanitizeEmailHtml(email.fullBody) + "</div>"
    setPendingQuote(island, "Forwarding: ${email.subject}")
    composeBodyInput.setText("")
    composeBodyInput.requestFocus()
}

internal fun MainActivity.showComposeView() {
    editingDraftId = null
    clearPendingQuote()
    activeFormats.clear()
    composeListMode = 0
    composeListNextNumber = 1
    composeSelfEdit = false
    val emails = savedAccounts.map { it.email }

    val isLight = currentTheme == "light"
    val bgColor = when (currentTheme) {
        "light" -> "#FFFFFF".toColorInt()
        "oled"  -> "#000000".toColorInt()
        else    -> "#1A1A1A".toColorInt()
    }
    val textColor  = if (isLight) "#212121".toColorInt() else Color.WHITE
    val hintColor  = if (isLight) "#9E9E9E".toColorInt() else "#4A4A4A".toColorInt()
    composeContainer.setBackgroundColor(bgColor)
    listOf(composeToInput, composeSubjectInput, composeBodyInput).forEach {
        it.setTextColor(textColor)
        it.setHintTextColor(hintColor)
    }
    formatToolbarRow.setBackgroundColor(when (currentTheme) {
        "light" -> "#E8E8E8".toColorInt()
        "oled"  -> Color.BLACK
        else    -> "#212121".toColorInt()
    })
    buildFormatToolbar()
    updateFormatButtonStates()

    // Setup From dropdown
    selectedFromEmail = currentAccountEmail.takeIf { !it.isNullOrBlank() && it in emails }
        ?: emails.firstOrNull() ?: ""
    composeFromText.text = selectedFromEmail
    composeFromText.setTextColor(textColor)
    val dp = resources.displayMetrics.density
    composeFromLabel.background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 10 * dp
        setColor(when (currentTheme) {
            "light" -> 0xFFE0E0E0.toInt()
            "oled"  -> 0xFF1A1A1A.toInt()
            else    -> 0xFF2A2A2A.toInt()
        })
    }
    composeFromLabel.setOnClickListener {
        val idx = emails.indexOf(selectedFromEmail).let { if (it >= 0) it else 0 }
        showSettingsDropdown(composeFromLabel, emails, idx) { i ->
            selectedFromEmail = emails[i]
            composeFromText.text = selectedFromEmail
        }
    }

    // Reset chip group and wire Enter key for multi-recipient input
    composeToChipsGroup.removeAllViews()
    composeToChipsGroup.visibility = View.GONE
    recipientEmails.clear()
    composeToInput.setOnEditorActionListener { _, actionId, _ ->
        if (actionId == EditorInfo.IME_ACTION_DONE) {
            val addr = composeToInput.text.toString().trim()
            if (addr.isNotBlank()) addRecipientChip(addr)
            true
        } else false
    }
    composeToInput.setOnKeyListener { _, keyCode, event ->
        if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
            val addr = composeToInput.text.toString().trim()
            if (addr.isNotBlank()) addRecipientChip(addr)
            true
        } else false
    }

    setDrawerIndicator(false)
    drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
    drawerToggle.syncState()
    applyNavIconTint(getOnAccentColor())

    folderLabel.visibility = View.GONE
    topBarSendButton.visibility = View.VISIBLE
    composeContainer.isClickable = true
    composeContainer.isFocusable = true
    composeContainer.bringToFront()
    composeContainer.visibility = View.VISIBLE
    composeToInput.requestFocus()
}

internal fun MainActivity.hideCompose() {
    editingDraftId = null
    clearPendingQuote()
    composeContainer.visibility = View.GONE
    activeFormats.clear()
    updateFormatButtonStates()
    pendingAttachments.clear()
    refreshAttachmentChips()
    topBarSendButton.visibility = View.GONE
    // Restore the detail top-bar state if compose was opened on top of an open email
    if (isShowingEmailDetail) {
        currentDetailEmail?.let { updateCustomTopBar(it.fromEmail.ifBlank { it.from }, inMailbox = false) }
        setDrawerIndicator(false)
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        drawerToggle.syncState()
        return
    }
    updateCustomTopBar(getCurrentMailboxTitle())
    setDrawerIndicator(true)
    drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
    drawerToggle.syncState()
}

internal fun MainActivity.composeIsEmpty(): Boolean =
    recipientEmails.isEmpty() &&
        composeToInput.text.isNullOrBlank() &&
        composeSubjectInput.text.isNullOrBlank() &&
        composeBodyInput.text.isNullOrBlank() &&
        pendingAttachments.isEmpty()

internal fun MainActivity.clearComposeFields() {
    composeToChipsGroup.removeAllViews()
    composeToChipsGroup.visibility = View.GONE
    recipientEmails.clear()
    composeToInput.text.clear()
    composeSubjectInput.text.clear()
    composeBodyInput.text.clear()
}

internal fun MainActivity.addRecipientChip(email: String) {
    val trimmed = email.trim()
    if (trimmed.isBlank() || trimmed in recipientEmails) return
    recipientEmails.add(trimmed)
    val dp = resources.displayMetrics.density
    val chip = Chip(this).apply {
        text = trimmed
        isCloseIconVisible = true
        isClickable = false
        isFocusable = false
        chipBackgroundColor = ColorStateList.valueOf(when (currentTheme) {
            "light" -> 0xFFE0E0E0.toInt()
            "oled"  -> 0xFF1E1E1E.toInt()
            else    -> 0xFF2A2A2A.toInt()
        })
        setTextColor(if (currentTheme == "light") 0xFF212121.toInt() else Color.WHITE)
        closeIconTint = ColorStateList.valueOf(
            if (currentTheme == "light") 0xFF757575.toInt() else 0xFFAAAAAA.toInt()
        )
        chipStrokeWidth = 1f * dp
        chipStrokeColor = ColorStateList.valueOf(currentAccentColor.toColorInt())
        setOnCloseIconClickListener {
            recipientEmails.remove(trimmed)
            composeToChipsGroup.removeView(this)
            if (composeToChipsGroup.childCount == 0) composeToChipsGroup.visibility = View.GONE
        }
    }
    composeToChipsGroup.addView(chip)
    composeToChipsGroup.visibility = View.VISIBLE
    composeToInput.text.clear()
}

/**
 * Called when the user tries to leave the compose screen (Android back or top-left arrow).
 * If there is nothing to lose, leaves directly; otherwise asks Cancel / Drafts / Continue.
 */
internal fun MainActivity.attemptLeaveCompose() {
    if (composeIsEmpty()) {
        hideCompose()
        return
    }

    val dp = resources.displayMetrics.density
    val dialogBg = getDialogBackgroundColor()
    val textColor = if (currentTheme == "light") "#212121".toColorInt() else Color.WHITE
    val secondaryColor = if (currentTheme == "light") "#757575".toColorInt() else "#9E9E9E".toColorInt()
    val dangerColor = "#EF5350".toColorInt()
    val accentColor = currentAccentColor.toColorInt()

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
        text = "Save draft?"
        textSize = 18f
        setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(textColor)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = (8 * dp).toInt() }
    })

    root.addView(TextView(this).apply {
        text = "You have an unsent email. Save it to Drafts?"
        textSize = 14f
        setTextColor(secondaryColor)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = (20 * dp).toInt() }
    })

    val btnRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }
    root.addView(btnRow)

    val dialog = AlertDialog.Builder(this).setView(root).create()

    fun makeButton(label: String, color: Int, bold: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(color)
            if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding((14 * dp).toInt(), (10 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
            isClickable = true; isFocusable = true
            setOnClickListener { dialog.dismiss(); onClick() }
        }

    btnRow.addView(makeButton("Continue", secondaryColor, false) { })
    btnRow.addView(makeButton("Cancel", dangerColor, true) {
        clearComposeFields()
        hideCompose()
    })
    btnRow.addView(makeButton("Drafts", accentColor, true) {
        saveDraftFromCompose()
    })

    dialog.show()
    dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
    dialog.window?.attributes?.let { lp ->
        lp.width = (resources.displayMetrics.widthPixels * 0.88f).toInt()
        dialog.window?.attributes = lp
    }
}

internal fun MainActivity.saveDraftFromCompose() {
    val fromEmail = selectedFromEmail.ifBlank { null }
    val currentToText = composeToInput.text.toString().trim()
    if (currentToText.isNotBlank()) addRecipientChip(currentToText)
    val to = recipientEmails.joinToString(", ")
    val subject = composeSubjectInput.text.toString()
    @Suppress("DEPRECATION")
    val userHtml = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
        Html.toHtml(composeBodyInput.text, Html.TO_HTML_PARAGRAPH_LINES_INDIVIDUAL)
    else
        Html.toHtml(composeBodyInput.text)
    // Append the faithful original-message HTML (reply/forward quote) verbatim.
    val body = userHtml + (pendingQuoteHtml ?: "")

    val accountToUse = savedAccounts.find { it.email == fromEmail }?.let {
        JMapClient.ConnectedAccount(
            email = it.email,
            password = it.password,
            sessionUrl = it.sessionUrl,
            apiUrl = it.apiUrl,
            accountId = it.accountId
        )
    } ?: connectedAccount

    if (accountToUse == null) {
        Snackbar.make(drawerLayout, "No active account", Snackbar.LENGTH_SHORT).show()
        return
    }

    val jmapAttachments = pendingAttachments.mapNotNull { att ->
        try {
            val bytes = contentResolver.openInputStream(att.uri)?.use { it.readBytes() } ?: return@mapNotNull null
            JMapClient.Attachment(att.name, att.mimeType, att.size, bytes)
        } catch (e: Exception) { null }
    }

    val oldDraftId = editingDraftId

    // Show the draft in the Drafts list immediately; the server save happens in the background.
    insertOptimisticDraft(to, subject, body, accountToUse.email, oldDraftId)

    // Leave the screen immediately; the save happens in the background.
    clearComposeFields()
    hideCompose()

    lifecycleScope.launch(Dispatchers.Main) {
        val ok = jmapClient.saveDraft(accountToUse, to, subject, body, "text/html", jmapAttachments)
        if (ok && oldDraftId != null) {
            jmapClient.destroyEmail(accountToUse, oldDraftId)
        }
        Snackbar.make(
            drawerLayout,
            if (ok) "Draft saved" else "Failed to save draft",
            Snackbar.LENGTH_SHORT
        ).show()
    }
}

internal fun MainActivity.requestStoragePermIfNeeded(): Boolean {
    val needed = mutableListOf<String>()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_IMAGES) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            needed.add(android.Manifest.permission.READ_MEDIA_IMAGES)
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_VIDEO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            needed.add(android.Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            needed.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    return if (needed.isNotEmpty()) {
        requestStoragePermLauncher.launch(needed.toTypedArray())
        false
    } else true
}

internal fun MainActivity.pickPhoto() {
    if (requestStoragePermIfNeeded())
        pickMediaLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
}

internal fun MainActivity.pickVideo() {
    if (requestStoragePermIfNeeded())
        pickMediaLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
}

internal fun MainActivity.pickFile() {
    pickFileLauncher.launch(arrayOf("*/*"))
}

internal fun MainActivity.addAttachment(uri: Uri) {
    val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        ?.use { cursor ->
            cursor.moveToFirst()
            cursor.getString(0) ?: uri.lastPathSegment ?: "file"
        } ?: (uri.lastPathSegment ?: "file")
    val size = contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
        ?.use { it.moveToFirst(); if (it.columnCount > 0) it.getLong(0) else 0L } ?: 0L
    val mime = contentResolver.getType(uri) ?: "application/octet-stream"
    pendingAttachments.add(MainActivity.AttachmentData(uri, name, mime, size))
    refreshAttachmentChips()
}

internal fun MainActivity.removeAttachment(index: Int) {
    if (index in pendingAttachments.indices) {
        pendingAttachments.removeAt(index)
        refreshAttachmentChips()
    }
}


internal fun MainActivity.buildFormatToolbar() {
    formatToolbar.removeAllViews()
    formatButtons.clear()
    val dp = resources.displayMetrics.density
    val iconTint = when (currentTheme) {
        "light" -> "#555555".toColorInt()
        "oled"  -> Color.WHITE
        else    -> "#808080".toColorInt()
    }
    val dividerColor = when (currentTheme) {
        "light" -> "#C8C8C8".toColorInt()
        "oled"  -> "#333333".toColorInt()
        else    -> "#3A3A3A".toColorInt()
    }

    fun imgBtn(key: String, drawableRes: Int) {
        val v = ImageView(this).apply {
            setImageResource(drawableRes)
            imageTintList = ColorStateList.valueOf(iconTint)
            val size = (40 * dp).toInt()
            val pad  = (8 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(size, LinearLayout.LayoutParams.MATCH_PARENT)
            setPadding(pad, pad, pad, pad)
            scaleType = ImageView.ScaleType.FIT_CENTER
            isClickable = true; isFocusable = true
            setOnClickListener { toggleFormat(key) }
        }
        formatButtons[key] = v; formatToolbar.addView(v)
    }

    fun txtBtn(key: String, label: String) {
        val v = TextView(this).apply {
            text = label; textSize = 11f
            setTextColor(iconTint)
            setTypeface(null, Typeface.BOLD)
            val hPad = (10 * dp).toInt()
            setPadding(hPad, 0, hPad, 0)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT)
            isClickable = true; isFocusable = true
            setOnClickListener { toggleFormat(key) }
        }
        formatButtons[key] = v; formatToolbar.addView(v)
    }

    fun divider() {
        val v = View(this).apply {
            val vPad = (8 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams((1 * dp).toInt(), LinearLayout.LayoutParams.MATCH_PARENT)
                .also { it.setMargins(4, vPad, 4, vPad) }
            setBackgroundColor(dividerColor)
        }
        formatToolbar.addView(v)
    }

    imgBtn("bold",    R.drawable.ic_format_bold_24dp)
    imgBtn("italic",  R.drawable.ic_format_italic_24dp)
    imgBtn("under",   R.drawable.ic_format_underlined_24dp)
    imgBtn("strike",  R.drawable.ic_strikethrough_s_24dp)
    divider()
    txtBtn("h1", "H1")
    txtBtn("h2", "H2")
    divider()
    imgBtn("bullet",  R.drawable.ic_format_list_bulleted_24dp)
    imgBtn("number",  R.drawable.ic_format_list_numbered_24dp)
    divider()
    imgBtn("link",    R.drawable.ic_link_24dp)
    divider()
    imgBtn("align_l", R.drawable.ic_format_align_left_24dp)
    imgBtn("align_c", R.drawable.ic_format_align_center_24dp)
    imgBtn("align_r", R.drawable.ic_format_align_right_24dp)
}

internal fun MainActivity.updateFormatButtonStates() {
    val activeTint = ColorStateList.valueOf(currentAccentColor.toColorInt())
    val inactiveColor = when (currentTheme) {
        "light" -> "#555555".toColorInt()
        "oled"  -> Color.WHITE
        else    -> "#808080".toColorInt()
    }
    val inactiveTint = ColorStateList.valueOf(inactiveColor)
    for ((key, view) in formatButtons) {
        val on = when (key) {
            "bullet" -> composeListMode == 1
            "number" -> composeListMode == 2
            else -> key in activeFormats
        }
        when (view) {
            is ImageView -> view.imageTintList = if (on) activeTint else inactiveTint
            is TextView  -> view.setTextColor(if (on) currentAccentColor.toColorInt() else inactiveColor)
        }
    }
}

internal fun MainActivity.toggleFormat(key: String) {
    when (key) {
        "bold", "italic", "under", "strike" -> {
            val sel = getBodySelection()
            if (sel != null) applyCharFormat(key, sel.first, sel.second)
            else {
                if (key in activeFormats) activeFormats.remove(key) else activeFormats.add(key)
                updateFormatButtonStates()
            }
        }
        "h1"      -> toggleHeading(1)
        "h2"      -> toggleHeading(2)
        "bullet"  -> toggleListMode(1)
        "number"  -> toggleListMode(2)
        "link"    -> showLinkDialog()
        "align_l" -> setComposeAlignment(Layout.Alignment.ALIGN_NORMAL)
        "align_c" -> setComposeAlignment(Layout.Alignment.ALIGN_CENTER)
        "align_r" -> setComposeAlignment(Layout.Alignment.ALIGN_OPPOSITE)
    }
}

internal fun MainActivity.applyCharFormat(key: String, s: Int, e: Int) {
    val text = composeBodyInput.text
    when (key) {
        "bold" -> {
            val ex = text.getSpans(s, e, StyleSpan::class.java).filter { it.style == Typeface.BOLD }
            if (ex.isNotEmpty()) ex.forEach { text.removeSpan(it) } else text.setSpan(StyleSpan(Typeface.BOLD), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        "italic" -> {
            val ex = text.getSpans(s, e, StyleSpan::class.java).filter { it.style == Typeface.ITALIC }
            if (ex.isNotEmpty()) ex.forEach { text.removeSpan(it) } else text.setSpan(StyleSpan(Typeface.ITALIC), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        "under" -> {
            val ex = text.getSpans(s, e, UnderlineSpan::class.java)
            if (ex.isNotEmpty()) ex.forEach { text.removeSpan(it) } else text.setSpan(UnderlineSpan(), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        "strike" -> {
            val ex = text.getSpans(s, e, StrikethroughSpan::class.java)
            if (ex.isNotEmpty()) ex.forEach { text.removeSpan(it) } else text.setSpan(StrikethroughSpan(), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}

internal fun MainActivity.getBodySelection(): Pair<Int, Int>? {
    val s = composeBodyInput.selectionStart; val e = composeBodyInput.selectionEnd
    return if (s < e) Pair(s, e) else null
}

internal fun MainActivity.getLineRange(pos: Int): Pair<Int, Int> {
    val str = composeBodyInput.text.toString()
    val start = str.lastIndexOf('\n', pos - 1).let { if (it < 0) 0 else it + 1 }
    val end   = str.indexOf('\n', pos).let { if (it < 0) str.length else it }
    return Pair(start, end)
}

/** On/off heading toggle (type-ahead, like bold) — applies to a selection if one exists. */
internal fun MainActivity.toggleHeading(level: Int) {
    val key = "h$level"
    val other = if (level == 1) "h2" else "h1"
    val sizeMult = if (level == 1) 1.8f else 1.4f
    val otherMult = if (level == 1) 1.4f else 1.8f
    val text = composeBodyInput.text
    val (s, e) = getBodySelection() ?: Pair(-1, -1)
    if (key in activeFormats) {
        activeFormats.remove(key)
        if (e > s && s >= 0) {
            text.getSpans(s, e, RelativeSizeSpan::class.java).filter { it.sizeChange == sizeMult }.forEach { text.removeSpan(it) }
            text.getSpans(s, e, StyleSpan::class.java).filter { it.style == Typeface.BOLD }.forEach { text.removeSpan(it) }
        }
    } else {
        activeFormats.add(key)
        activeFormats.remove(other)
        if (e > s && s >= 0) {
            text.getSpans(s, e, RelativeSizeSpan::class.java).filter { it.sizeChange == otherMult }.forEach { text.removeSpan(it) }
            text.setSpan(RelativeSizeSpan(sizeMult), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(StyleSpan(Typeface.BOLD), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
    updateFormatButtonStates()
}

internal const val BULLET_PREFIX = "•  "
internal val NUMBER_MARKER_REGEX = Regex("^(\\d+)\\.\\s+")

/** Toggles a text-prefix list (1 = bullet, 2 = numbered) that continues on Enter. */
internal fun MainActivity.toggleListMode(mode: Int) {
    if (composeListMode == mode) {
        composeListMode = 0
        removeListMarkerFromCurrentLine()
        updateFormatButtonStates()
        return
    }
    composeListMode = mode
    composeListNextNumber = 1
    val text = composeBodyInput.text
    val pos = composeBodyInput.selectionStart.coerceAtLeast(0)
    val lineStart = text.toString().lastIndexOf('\n', pos - 1) + 1
    val lineEndNl = text.toString().indexOf('\n', lineStart)
    val lineEnd = if (lineEndNl < 0) text.length else lineEndNl
    val line = text.substring(lineStart, lineEnd)
    composeSelfEdit = true
    try {
        // Strip any existing marker on the line before applying the new one.
        when {
            line.startsWith(BULLET_PREFIX) -> text.delete(lineStart, lineStart + BULLET_PREFIX.length)
            NUMBER_MARKER_REGEX.find(line) != null ->
                text.delete(lineStart, lineStart + NUMBER_MARKER_REGEX.find(line)!!.value.length)
        }
        val marker = if (mode == 1) BULLET_PREFIX else "1.  "
        text.insert(lineStart, marker)
        if (mode == 2) composeListNextNumber = 2
        composeBodyInput.setSelection((lineStart + marker.length).coerceAtMost(text.length))
    } finally {
        composeSelfEdit = false
    }
    updateFormatButtonStates()
}

/** Current paragraph bounds, with a paragraph-safe end (text end or just past a '\n'). */
private fun MainActivity.currentParagraphBounds(): Pair<Int, Int> {
    val text = composeBodyInput.text
    val len = text.length
    val selS = composeBodyInput.selectionStart.coerceIn(0, len)
    val selE = composeBodyInput.selectionEnd.coerceIn(selS, len)
    val s = text.toString().lastIndexOf('\n', selS - 1) + 1
    val eNl = text.toString().indexOf('\n', selE)
    val e = if (eNl < 0) len else eNl
    val pe = if (e < len && text[e] == '\n') e + 1 else e
    return Pair(s, pe)
}

/** Ensures the current line is a real paragraph so a block span renders immediately (even if empty). */
private fun MainActivity.ensureRenderableParagraph() {
    val text = composeBodyInput.text
    val pos = composeBodyInput.selectionStart.coerceAtLeast(0)
    val lineStart = text.toString().lastIndexOf('\n', pos - 1) + 1
    if (lineStart >= text.length) {
        composeSelfEdit = true
        try {
            text.insert(lineStart, "\n")
            composeBodyInput.setSelection(lineStart)
        } finally { composeSelfEdit = false }
    }
}

private fun MainActivity.removeListMarkerFromCurrentLine() {
    val text = composeBodyInput.text
    val pos = composeBodyInput.selectionStart.coerceAtLeast(0)
    val lineStart = text.toString().lastIndexOf('\n', pos - 1) + 1
    val lineEndNl = text.toString().indexOf('\n', lineStart)
    val lineEnd = if (lineEndNl < 0) text.length else lineEndNl
    val line = text.substring(lineStart, lineEnd)
    composeSelfEdit = true
    try {
        when {
            line.startsWith(BULLET_PREFIX) -> text.delete(lineStart, lineStart + BULLET_PREFIX.length)
            NUMBER_MARKER_REGEX.find(line) != null ->
                text.delete(lineStart, lineStart + NUMBER_MARKER_REGEX.find(line)!!.value.length)
        }
    } finally { composeSelfEdit = false }
}

/** Re-applies the active paragraph alignment to the current paragraph. */
internal fun MainActivity.applyActiveParagraphFormats() {
    val text = composeBodyInput.text
    val (s, pe) = currentParagraphBounds()
    if (s < 0 || pe <= s) return

    val align = when {
        "align_c" in activeFormats -> Layout.Alignment.ALIGN_CENTER
        "align_r" in activeFormats -> Layout.Alignment.ALIGN_OPPOSITE
        "align_l" in activeFormats -> Layout.Alignment.ALIGN_NORMAL
        else -> null
    }
    text.getSpans(s, pe, AlignmentSpan::class.java).forEach { text.removeSpan(it) }
    if (align != null) {
        text.setSpan(AlignmentSpan.Standard(align), s, pe, Spannable.SPAN_PARAGRAPH)
    }
}

/** Centered rounded dialog (app style) asking for display text + URL, then inserts an anchor. */
internal fun MainActivity.showLinkDialog() {
    val dp = resources.displayMetrics.density
    val dialogBg = getDialogBackgroundColor()
    val textColor = if (currentTheme == "light") "#212121".toColorInt() else Color.WHITE
    val secondaryColor = if (currentTheme == "light") "#757575".toColorInt() else "#9E9E9E".toColorInt()
    val accentColor = currentAccentColor.toColorInt()

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
        text = "Insert link"
        textSize = 18f
        setTypeface(null, Typeface.BOLD)
        setTextColor(textColor)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = (16 * dp).toInt() }
    })

    fun field(hint: String): EditText = EditText(this).apply {
        this.hint = hint
        setText("")
        setTextColor(textColor)
        setHintTextColor(secondaryColor)
        backgroundTintList = ColorStateList.valueOf(secondaryColor)
        textSize = 15f
        maxLines = 1
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = (12 * dp).toInt() }
    }
    val nameInput = field("Text to display")
    val urlInput = field("https://example.com").apply { inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI }
    // Pre-fill the display text with the current selection.
    val sel = getBodySelection()
    if (sel != null) nameInput.setText(composeBodyInput.text.substring(sel.first, sel.second))
    root.addView(nameInput)
    root.addView(urlInput)

    val btnRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }
    root.addView(btnRow)

    val dialog = AlertDialog.Builder(this).setView(root).create()

    fun makeButton(label: String, color: Int, bold: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
            setPadding((14 * dp).toInt(), (10 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
        }

    btnRow.addView(makeButton("Cancel", secondaryColor, false) { dialog.dismiss() })
    btnRow.addView(makeButton("Insert", accentColor, true) {
        val rawUrl = urlInput.text.toString().trim()
        if (rawUrl.isBlank()) { dialog.dismiss(); return@makeButton }
        val finalUrl = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(rawUrl)) rawUrl else "https://$rawUrl"
        val display = nameInput.text.toString().trim().ifBlank { rawUrl }
        val span = android.text.SpannableString(display).apply {
            setSpan(android.text.style.URLSpan(finalUrl), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val body = composeBodyInput.text
        val s = sel?.first ?: composeBodyInput.selectionStart.coerceAtLeast(0)
        val e = sel?.second ?: s
        if (e > s) body.replace(s, e, span) else body.insert(s, span)
        composeBodyInput.setSelection(s + span.length)
        dialog.dismiss()
    })

    dialog.show()
    dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
    dialog.window?.attributes?.let { lp ->
        lp.width = (resources.displayMetrics.widthPixels * 0.88f).toInt()
        dialog.window?.attributes = lp
    }
}

/** On/off alignment toggle (radio: left / center / right). */
internal fun MainActivity.setComposeAlignment(alignment: Layout.Alignment) {
    val key = when (alignment) {
        Layout.Alignment.ALIGN_CENTER -> "align_c"
        Layout.Alignment.ALIGN_OPPOSITE -> "align_r"
        else -> "align_l"
    }
    if (key in activeFormats) activeFormats.remove(key)
    else {
        activeFormats.removeAll(listOf("align_l", "align_c", "align_r"))
        activeFormats.add(key)
    }
    applyActiveParagraphFormats()
    updateFormatButtonStates()
}

internal class FormatTextWatcher(private val activity: MainActivity) : TextWatcher {
    private var insertStart = -1
    private var insertCount = 0
    private var newlineInserted = false
    override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
        insertStart = if (before == 0 && count > 0) start else -1
        insertCount = count
        newlineInserted = before == 0 && count == 1 && start < s.length && s[start] == '\n'
    }
    override fun afterTextChanged(s: Editable) {
        if (activity.composeSelfEdit) return
        // List continuation: pressing Enter inside an active list emits the next marker.
        if (newlineInserted && activity.composeListMode != 0) {
            newlineInserted = false
            handleListNewline(s, insertStart)
            return
        }
        if (insertStart < 0 || activity.activeFormats.isEmpty()) return
        val iS = insertStart; val iE = insertStart + insertCount; insertStart = -1
        val af = activity.activeFormats
        if ("bold"   in af) s.setSpan(StyleSpan(Typeface.BOLD),   iS, iE, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if ("italic" in af) s.setSpan(StyleSpan(Typeface.ITALIC), iS, iE, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if ("under"  in af) s.setSpan(UnderlineSpan(),            iS, iE, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if ("strike" in af) s.setSpan(StrikethroughSpan(),        iS, iE, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if ("h1" in af) {
            s.setSpan(RelativeSizeSpan(1.8f), iS, iE, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            s.setSpan(StyleSpan(Typeface.BOLD), iS, iE, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else if ("h2" in af) {
            s.setSpan(RelativeSizeSpan(1.4f), iS, iE, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            s.setSpan(StyleSpan(Typeface.BOLD), iS, iE, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        // Re-apply paragraph alignment now that the line has content.
        if ("align_l" in af || "align_c" in af || "align_r" in af) {
            activity.composeSelfEdit = true
            try { activity.applyActiveParagraphFormats() } finally { activity.composeSelfEdit = false }
        }
    }

    private fun handleListNewline(s: Editable, newlinePos: Int) {
        val prevLineStart = s.toString().lastIndexOf('\n', newlinePos - 1) + 1
        val prevLine = s.substring(prevLineStart, newlinePos)
        val bullet = activity.composeListMode == 1
        val markerMatch = if (bullet) prevLine.startsWith(BULLET_PREFIX)
                          else NUMBER_MARKER_REGEX.containsMatchIn(prevLine)
        val content = when {
            bullet && markerMatch -> prevLine.removePrefix(BULLET_PREFIX)
            !bullet && markerMatch -> prevLine.substring(NUMBER_MARKER_REGEX.find(prevLine)!!.value.length)
            else -> prevLine
        }
        activity.composeSelfEdit = true
        try {
            if (markerMatch && content.isBlank()) {
                // Enter on an empty item ends the list and removes the dangling marker.
                s.delete(prevLineStart, newlinePos)
                activity.composeListMode = 0
                activity.composeBodyInput.setSelection((prevLineStart + 1).coerceAtMost(s.length))
                activity.updateFormatButtonStates()
            } else {
                val marker = if (bullet) BULLET_PREFIX else "${activity.composeListNextNumber}.  "
                s.insert(newlinePos + 1, marker)
                if (!bullet) activity.composeListNextNumber++
                activity.composeBodyInput.setSelection((newlinePos + 1 + marker.length).coerceAtMost(s.length))
            }
        } finally {
            activity.composeSelfEdit = false
        }
    }
}
