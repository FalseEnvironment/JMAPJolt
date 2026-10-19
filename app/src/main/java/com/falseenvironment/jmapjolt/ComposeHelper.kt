package com.falseenvironment.jmapjolt

import android.content.res.ColorStateList
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import android.util.Log
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
import androidx.core.graphics.toColorInt
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun MainActivity.setupComposeView() {
    fabCompose.setOnClickListener { showComposeView() }

    composeAttachButton.setOnClickListener { showAttachMenu() }

    composeSendButton.setOnClickListener { performSend() }
    topBarSendButton.setOnClickListener { performSend() }

    composeBodyInput.addTextChangedListener(FormatTextWatcher(this))
    buildFormatToolbar()

    // One inline input per recipient row: Enter (or any separator) commits the
    // address into a chip in the same row, and the input stays there for the next.
    for (category in 0..2) setupRecipientInput(category)
}

/** Input backing the given category (0 = To, 1 = Cc, 2 = Bcc). */
internal fun MainActivity.composeInputFor(category: Int): EditText = when (category) {
    1 -> composeCcInput
    2 -> composeBccInput
    else -> composeToInput
}

/** Wires commit-on-separator / Enter / focus-loss for one recipient row. */
private fun MainActivity.setupRecipientInput(category: Int) {
    val input = composeInputFor(category)

    // Track which row is active so the address book and sends land in the right list.
    input.setOnFocusChangeListener { _, hasFocus ->
        if (hasFocus) {
            composeCategory = category
        } else {
            val token = input.text.toString().trim()
            if (token.isNotBlank()) addRecipientChip(token, category)
        }
    }

    // Commit when the user types a separator (space / comma / ; / newline), so an
    // address becomes a chip without having to press Enter.
    input.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            val str = s?.toString() ?: return
            if (str.isEmpty()) return
            if (str.last() in RECIPIENT_SEPARATORS) commitRecipientInput(category)
        }
    })

    input.setOnEditorActionListener { _, actionId, _ ->
        if (actionId == EditorInfo.IME_ACTION_DONE) {
            commitRecipientInput(category)
            true
        } else false
    }
    input.setOnKeyListener { _, keyCode, event ->
        if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
            commitRecipientInput(category)
            true
        } else false
    }
}

private val RECIPIENT_SEPARATORS = charArrayOf(' ', ',', ';', '\n')

/** Commits the text currently in [category]'s input as a chip (no-op when blank). */
internal fun MainActivity.commitRecipientInput(category: Int) {
    val input = composeInputFor(category)
    val token = input.text.toString().trim()
    if (token.isNotBlank()) addRecipientChip(token, category) else input.text.clear()
}

/** Commits whatever is typed in every recipient row (used on send / save draft). */
internal fun MainActivity.commitAllRecipientInputs() {
    for (category in 0..2) commitRecipientInput(category)
}

internal fun MainActivity.performSend() {
    val fromEmail = selectedFromEmail.ifBlank { null }
    commitAllRecipientInputs()
    val to = recipientEmails.joinToString(", ")
    val cc = ccEmails.joinToString(", ")
    val bcc = bccEmails.joinToString(", ")
    val subject = composeSubjectInput.text.toString()
    @Suppress("DEPRECATION")
    val userHtml = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
        Html.toHtml(composeBodyInput.text, Html.TO_HTML_PARAGRAPH_LINES_INDIVIDUAL)
    else
        Html.toHtml(composeBodyInput.text)
    // Append the faithful original-message HTML (reply/forward quote) verbatim.
    val body = userHtml + (pendingQuoteHtml ?: "")

    if (fromEmail == null || recipientEmails.isEmpty()) {
        showThemedSnackbar("Please provide 'From' and 'To'")
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
        showThemedSnackbar("No active account")
        return
    }

    val outgoingAttachments = pendingAttachments.map { att ->
        OutgoingAttachment(att.name, att.mimeType, att.size, uri = att.uri)
    } + carriedAttachments.map { att ->
        // Already on the server (carried over from an edited draft): reuse the blob.
        OutgoingAttachment(att.name, att.mimeType, att.size, existingBlobId = att.blobId)
    }

    val request = EmailSender.Request(
        accountEmail = accountToUse.email,
        to = to,
        cc = cc,
        bcc = bcc,
        subject = subject,
        bodyHtml = body,
        attachments = outgoingAttachments,
        oldDraftId = editingDraftId
    )

    // Leave the compose screen at once. Staging, upload and submission run in the
    // background; the notification reports the real outcome once the server accepts it.
    clearComposeFields()
    hideCompose()
    showThemedSnackbar(getString(R.string.compose_sending))
    EmailSender.send(this, request)
}

/**
 * Opens the compose editor pre-filled with a draft's recipients, subject, body and
 * attachments. The row passed in usually comes from a folder list fetch, which only
 * carries subject/preview — never the full body, Cc/Bcc or attachment blobs — so this
 * re-fetches the draft by its (real, server-assigned) id before populating the fields.
 * A still-unsaved local draft (fake "local-draft-..." id) has nothing to fetch and
 * already carries everything locally, so that case just uses [email] directly.
 */
internal fun MainActivity.openDraftForEdit(email: DisplayEmail) {
    showComposeView()
    editingDraftId = email.id

    fun populate(draft: DisplayEmail) {
        selectComposeAccount(draft.accountEmail.ifBlank { draft.fromEmail })
        draft.toEmail.split(",").map { it.trim() }.filter { it.isNotBlank() }
            .forEach { addRecipientChip(it, category = 0) }
        draft.ccEmail.split(",").map { it.trim() }.filter { it.isNotBlank() }
            .forEach { addRecipientChip(it, category = 1) }
        draft.bccEmail.split(",").map { it.trim() }.filter { it.isNotBlank() }
            .forEach { addRecipientChip(it, category = 2) }
        composeSubjectInput.setText(
            if (draft.subject == "(No Subject)") "" else draft.subject
        )
        @Suppress("DEPRECATION")
        val bodySpanned = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            Html.fromHtml(draft.fullBody, Html.FROM_HTML_MODE_LEGACY)
        else
            Html.fromHtml(draft.fullBody)
        composeBodyInput.setText(bodySpanned)
        composeBodyInput.requestFocus()
        carriedAttachments.clear()
        carriedAttachments.addAll(draft.attachments)
        refreshAttachmentChips()
    }

    if (email.id.startsWith("local-draft-")) {
        populate(email)
        return
    }
    val account = resolveAccountFor(email) ?: connectedAccount
    if (account == null) {
        populate(email)
        return
    }
    lifecycleScope.launch {
        val fresh = try {
            jmapClient.fetchEmailsById(account, listOf(email.id)).firstOrNull()
        } catch (e: Exception) {
            Log.e(MainActivity.TAG, "openDraftForEdit fetch failed", e)
            null
        }
        if (editingDraftId != email.id) return@launch // user navigated away while fetching
        populate(
            if (fresh != null) email.copy(
                fullBody = fresh.fullBody,
                toEmail = fresh.toEmail,
                ccEmail = fresh.ccEmail,
                bccEmail = fresh.bccEmail,
                attachments = fresh.attachments
            ) else email
        )
    }
}

/** Picks the From account for a reply/forward, defaulting to the account that owns the email. */
private fun MainActivity.selectComposeAccount(accountEmail: String) {
    if (accountEmail.isBlank()) return
    val match = savedAccounts.firstOrNull { it.email.equals(accountEmail, ignoreCase = true) } ?: return
    selectedFromEmail = match.email
    composeFromText.text = match.email
}

private fun composeQuoteDate(receivedAt: Long): String =
    java.text.SimpleDateFormat("MMM d, yyyy 'at' h:mm a", java.util.Locale.ENGLISH)
        .format(java.util.Date(receivedAt))

/**
 * Inline style for the quoted-html island, so it renders even in clients that strip
 * CSS classes. The accent bar uses the app's theme accent color and is detached a few
 * pixels from the container edge so the quoted block reads as clearly separate.
 */
private fun MainActivity.quoteIslandStyle(): String =
    "border-left:3px solid $currentAccentColor;margin-left:4px;padding-left:12px;margin-top:8px"

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
        "<div data-quoted-html=\"\" class=\"quoted-html-island\" style=\"${quoteIslandStyle()}\">" +
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
    val island = "<br><br><div data-forwarded-html=\"\" class=\"quoted-html-island\" style=\"${quoteIslandStyle()}\">" +
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

/**
 * Opens compose pre-filled from a mailto: intent (Android default-email-app links).
 * Parses to/subject/body via android.net.MailTo. Consumes the intent so a config
 * change (rotation) does not reopen compose on the same link.
 */
internal fun MainActivity.handleMailtoIntent(intent: android.content.Intent?) {
    val action = intent?.action ?: return
    if (action != android.content.Intent.ACTION_VIEW &&
        action != android.content.Intent.ACTION_SENDTO) return
    val data = intent.data ?: return
    if (data.scheme?.lowercase() != "mailto") return
    if (savedAccounts.isEmpty()) return

    val mailTo = try {
        android.net.MailTo.parse(data.toString())
    } catch (e: Exception) {
        return
    }

    showComposeView()
    mailTo.to
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        ?.forEach { addRecipientChip(it) }
    mailTo.subject?.takeIf { it.isNotBlank() }?.let { composeSubjectInput.setText(it) }
    mailTo.body?.takeIf { it.isNotBlank() }?.let { composeBodyInput.setText(it) }
    composeBodyInput.requestFocus()

    // Consume so onCreate after rotation does not reopen.
    intent.action = null
    intent.data = null
}

internal fun MainActivity.showComposeView() {
    editingDraftId = null
    carriedAttachments.clear()
    clearPendingQuote()
    activeFormats.clear()
    composeListMode = 0
    composeListNextNumber = 1
    composeSelfEdit = false
    val emails = savedAccounts.map { it.email }

    val isLight = currentTheme == "light"
    val bgColor = when (currentTheme) {
        "light"  -> "#FFFFFF".toColorInt()
        "oled"   -> "#000000".toColorInt()
        "violet" -> "#160E24".toColorInt()
        else     -> "#1A1A1A".toColorInt()
    }
    val textColor  = if (isLight) "#212121".toColorInt() else Color.WHITE
    val hintColor  = if (isLight) "#9E9E9E".toColorInt() else "#4A4A4A".toColorInt()
    composeContainer.setBackgroundColor(bgColor)
    listOf(composeToInput, composeCcInput, composeBccInput, composeSubjectInput, composeBodyInput).forEach {
        it.setTextColor(textColor)
        it.setHintTextColor(hintColor)
    }
    formatToolbarRow.setBackgroundColor(tokens.surfaceVariant)
    buildFormatToolbar()
    updateFormatButtonStates()

    // Setup From dropdown
    selectedFromEmail = currentAccountEmail.takeIf { !it.isNullOrBlank() && it in emails }
        ?: emails.firstOrNull() ?: ""
    composeFromText.text = selectedFromEmail
    val onAccent = getOnAccentColor()
    composeFromText.setTextColor(onAccent)
    composeFromText.setTypeface(null, Typeface.BOLD)
    // Recolor the trailing ▾ chevron (second child) to match the on-accent text.
    (composeFromLabel.getChildAt(1) as? TextView)?.setTextColor(onAccent)
    val dp = resources.displayMetrics.density
    composeFromLabel.background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 10 * dp
        setColor(currentAccentColor.toColorInt())
    }
    composeFromLabel.setOnClickListener {
        val idx = emails.indexOf(selectedFromEmail).let { if (it >= 0) it else 0 }
        showSettingsDropdown(composeFromLabel, emails, idx) { i ->
            selectedFromEmail = emails[i]
            composeFromText.text = selectedFromEmail
        }
    }

    // Address book shortcut: picks land in whatever category (To/Cc/Bcc) is active.
    composeContactsButton.imageTintList = ColorStateList.valueOf(currentAccentColor.toColorInt())
    composeContactsButton.setOnClickListener {
        ContactPicker(this) { addresses -> addresses.forEach { addRecipientChip(it) } }.show()
    }

    // To is always visible; Cc/Bcc are revealed by the chevrons on the left of the
    // row above them. The chevrons are tinted here so they follow the theme.
    val chevronTint = ColorStateList.valueOf(tokens.textSecondary)
    composeToExpand.imageTintList = chevronTint
    composeCcExpand.imageTintList = chevronTint
    composeToExpand.setOnClickListener { selectComposeCategory(1) }
    composeCcExpand.setOnClickListener { selectComposeCategory(2) }

    resetRecipientRows()

    setDrawerIndicator(false)
    drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
    drawerToggle.syncState()
    applyNavIconTint(topBarContentColor())

    folderLabel.visibility = View.GONE
    topBarSendButton.visibility = View.VISIBLE
    composeContainer.isClickable = true
    composeContainer.isFocusable = true
    composeContainer.bringToFront()
    composeContainer.visibility = View.VISIBLE
    refreshBottomNav()
    composeToInput.requestFocus()
}

internal fun MainActivity.hideCompose() {
    editingDraftId = null
    clearPendingQuote()
    composeContainer.visibility = View.GONE
    refreshBottomNav()
    activeFormats.clear()
    updateFormatButtonStates()
    pendingAttachments.clear()
    carriedAttachments.clear()
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
    recipientEmails.isEmpty() && ccEmails.isEmpty() && bccEmails.isEmpty() &&
        composeToInput.text.isNullOrBlank() &&
        composeCcInput.text.isNullOrBlank() &&
        composeBccInput.text.isNullOrBlank() &&
        composeSubjectInput.text.isNullOrBlank() &&
        composeBodyInput.text.isNullOrBlank() &&
        pendingAttachments.isEmpty() && carriedAttachments.isEmpty()

internal fun MainActivity.clearComposeFields() {
    resetRecipientRows()
    composeSubjectInput.text.clear()
    composeBodyInput.text.clear()
}

/** Empties all three recipient rows and hides Cc/Bcc again. */
internal fun MainActivity.resetRecipientRows() {
    for (category in 0..2) {
        val group = chipGroupFor(category)
        // The inline input lives in the group too, so remove only the chips.
        for (i in group.childCount - 1 downTo 0) {
            if (group.getChildAt(i) is Chip) group.removeViewAt(i)
        }
        composeInputFor(category).text.clear()
    }
    recipientEmails.clear(); ccEmails.clear(); bccEmails.clear()
    composeCategory = 0
    composeCcRow.visibility = View.GONE
    composeBccRow.visibility = View.GONE
    composeToRow.visibility = View.VISIBLE
    updateRecipientChevrons()
}

/** Recipient list backing the given category (0 = To, 1 = Cc, 2 = Bcc). */
internal fun MainActivity.recipientListFor(category: Int) = when (category) {
    1 -> ccEmails
    2 -> bccEmails
    else -> recipientEmails
}

/** Chip group rendering the given category. */
internal fun MainActivity.chipGroupFor(category: Int) = when (category) {
    1 -> composeCcChipsGroup
    2 -> composeBccChipsGroup
    else -> composeToChipsGroup
}

internal fun MainActivity.addRecipientChip(email: String, category: Int = composeCategory) {
    val trimmed = email.trim()
    val list = recipientListFor(category)
    val group = chipGroupFor(category)
    val input = composeInputFor(category)
    if (trimmed.isBlank() || trimmed in list) { input.text.clear(); return }
    list.add(trimmed)
    val dp = resources.displayMetrics.density
    val chip = Chip(this).apply {
        text = trimmed
        // No trailing "x": a tap opens the options menu, which carries Delete.
        isCloseIconVisible = false
        isClickable = true
        isFocusable = true
        chipBackgroundColor = ColorStateList.valueOf(when (currentTheme) {
            "light" -> 0xFFE0E0E0.toInt()
            "oled"  -> 0xFF1E1E1E.toInt()
            else    -> 0xFF2A2A2A.toInt()
        })
        setTextColor(if (currentTheme == "light") 0xFF212121.toInt() else Color.WHITE)
        chipStrokeWidth = 1f * dp
        chipStrokeColor = ColorStateList.valueOf(currentAccentColor.toColorInt())
        contentDescription = getString(R.string.cd_recipient_options) + ": " + trimmed
        setOnClickListener { showRecipientChipMenu(trimmed, category) }
    }
    // The inline input is the group's last child; keep chips before it so typing
    // stays at the end of the flow and wraps with the chips.
    group.addView(chip, (group.childCount - 1).coerceAtLeast(0))
    showRecipientRow(category)
    input.text.clear()
}

/** Categories a chip in [category] can be moved to (everything but its own). */
internal fun recipientTransferTargets(category: Int): List<Int> =
    listOf(0, 1, 2).filter { it != category }

/** Reveals the row(s) needed to show a chip of [category], expanding down from To. */
internal fun MainActivity.showRecipientRow(category: Int) {
    when (category) {
        1 -> composeCcRow.visibility = View.VISIBLE
        2 -> {
            composeCcRow.visibility = View.VISIBLE
            composeBccRow.visibility = View.VISIBLE
        }
        else -> composeToRow.visibility = View.VISIBLE
    }
    updateRecipientChevrons()
}

/**
 * Only the last visible row keeps its chevron: expanding To moves the chevron down
 * to Cc, expanding Cc makes it disappear on Bcc. "Remove" means invisible, so the
 * To/Cc/Bcc labels stay aligned in their column.
 */
internal fun MainActivity.updateRecipientChevrons() {
    val ccVisible = composeCcRow.visibility == View.VISIBLE
    val bccVisible = composeBccRow.visibility == View.VISIBLE
    composeToExpand.visibility = if (ccVisible) View.INVISIBLE else View.VISIBLE
    composeCcExpand.visibility = if (ccVisible && !bccVisible) View.VISIBLE else View.INVISIBLE
}

private val COMPOSE_CATEGORY_LABELS = listOf("To", "Cc", "Bcc")

/** Moves [email] from one recipient list to another, re-rendering both rows. */
internal fun MainActivity.moveRecipient(email: String, from: Int, to: Int) {
    if (from == to) return
    removeRecipient(email, from)
    addRecipientChip(email, to)
}

/** Removes [email]'s chip from [category] and from its backing list. */
internal fun MainActivity.removeRecipient(email: String, category: Int) {
    val list = recipientListFor(category)
    val group = chipGroupFor(category)
    if (!list.remove(email)) return
    for (i in group.childCount - 1 downTo 0) {
        val child = group.getChildAt(i)
        if (child is Chip && child.text?.toString() == email) {
            group.removeViewAt(i)
            break
        }
    }
}

internal fun MainActivity.copyAddressToClipboard(email: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("email", email))
    showThemedSnackbar("Address copied")
}

/**
 * Options shown when a recipient chip is tapped: move it to the other categories,
 * copy it, or delete it. The current category is never offered as a destination.
 */
internal fun MainActivity.showRecipientChipMenu(email: String, category: Int) {
    val dp = resources.displayMetrics.density
    val dialogBg = getDialogBackgroundColor()
    val textColor = tokens.textPrimary
    val secondaryColor = tokens.textSecondary
    val dangerColor = "#EF5350".toColorInt()

    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt())
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 20 * dp
            setColor(dialogBg)
        }
    }

    root.addView(TextView(this).apply {
        text = email
        textSize = 13f
        setTextColor(secondaryColor)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
    })

    val dialog = AlertDialog.Builder(this).setView(root).create()

    fun addRow(label: String, color: Int, bold: Boolean = false, onClick: () -> Unit) {
        root.addView(TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
            setPadding((12 * dp).toInt(), (13 * dp).toInt(), (12 * dp).toInt(), (13 * dp).toInt())
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { dialog.dismiss(); onClick() }
        })
    }

    for (target in recipientTransferTargets(category)) {
        addRow("Move to ${COMPOSE_CATEGORY_LABELS[target]}", textColor) {
            moveRecipient(email, category, target)
        }
    }

    root.addView(View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt().coerceAtLeast(1)
        )
        setBackgroundColor(tokens.divider)
    })

    addRow("Copy address", textColor) { copyAddressToClipboard(email) }
    addRow("Delete", dangerColor, bold = true) { removeRecipient(email, category) }

    dialog.show()
    dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
    dialog.window?.attributes?.let { lp ->
        lp.width = (resources.displayMetrics.widthPixels * 0.80f).toInt()
        dialog.window?.attributes = lp
    }
}

/**
 * Reveals and focuses a recipient row. Called by the expand chevrons and the
 * address-book picker path, which used to switch the old category dropdown.
 */
internal fun MainActivity.selectComposeCategory(category: Int) {
    commitRecipientInput(composeCategory)
    composeCategory = category
    when (category) {
        1 -> {
            composeCcRow.visibility = View.VISIBLE
            composeCcInput.requestFocus()
        }
        2 -> {
            composeCcRow.visibility = View.VISIBLE
            composeBccRow.visibility = View.VISIBLE
            composeBccInput.requestFocus()
        }
        else -> composeToInput.requestFocus()
    }
    updateRecipientChevrons()
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
    commitAllRecipientInputs()
    val to = recipientEmails.joinToString(", ")
    val cc = ccEmails.joinToString(", ")
    val bcc = bccEmails.joinToString(", ")
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
        showThemedSnackbar("No active account")
        return
    }

    val outgoingAttachments = pendingAttachments.map { att ->
        OutgoingAttachment(att.name, att.mimeType, att.size, uri = att.uri)
    } + carriedAttachments.map { att ->
        // Already on the server (carried over from an edited draft): reuse the blob.
        OutgoingAttachment(att.name, att.mimeType, att.size, existingBlobId = att.blobId)
    }

    val oldDraftId = editingDraftId

    // Show the draft in the Drafts list immediately; the server save happens in the background.
    val localDraftId = insertOptimisticDraft(to, subject, body, accountToUse.email, oldDraftId)

    // Leave the screen immediately; the save happens in the background.
    clearComposeFields()
    hideCompose()

    lifecycleScope.launch {
        val staged = withContext(Dispatchers.IO) {
            runCatching { stageAttachments(this@saveDraftFromCompose, outgoingAttachments) }
        }
        val realId = if (staged.isSuccess) {
            jmapClient.saveDraft(
                accountToUse, to, subject, body, "text/html",
                staged.getOrThrow().attachments, cc, bcc
            )
        } else {
            Log.e(MainActivity.TAG, "Could not stage draft attachments", staged.exceptionOrNull())
            null
        }
        staged.getOrNull()?.cleanup()
        if (realId != null) {
            replaceOptimisticDraftId(localDraftId, realId)
            if (oldDraftId != null) jmapClient.destroyEmail(accountToUse, oldDraftId)
        }
        showThemedSnackbar(if (realId != null) "Draft saved" else "Failed to save draft")
    }
}

internal fun MainActivity.pickPhoto() {
    // The system photo picker grants read access to the chosen item on its own: asking
    // for READ_MEDIA_* first only added a permission detour (and could block the picker
    // entirely when the user had denied it).
    pickMediaLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
}

internal fun MainActivity.pickVideo() {
    if (ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(this)) {
        pickMediaLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
    } else {
        // Devices without the system picker fall back to the document picker, which
        // opens video/mp4 files reliably and needs no storage permission either.
        pickFileLauncher.launch(arrayOf("video/*"))
    }
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
