package com.falseenvironment.jmapjolt

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.toColorInt
import com.google.android.material.snackbar.Snackbar
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Bounded LRU cache of downloaded image blob bytes, keyed by blobId. Lets the email
 * thumbnail and the fullscreen viewer share a single download instead of fetching the
 * full-res blob twice. Sized by byte length, capped at 24 MB.
 */
private val blobByteCache = object : android.util.LruCache<String, ByteArray>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ByteArray): Int = value.size
}

// ── Compose: styled attach-picker popup ──────────────────────────────────────

internal fun MainActivity.showAttachMenu() {
    val dp = resources.displayMetrics.density
    val bgColor = getDialogBackgroundColor()
    val textColor = if (currentTheme == "light") "#212121".toColorInt() else Color.WHITE
    val accentInt = currentAccentColor.toColorInt()

    val view = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16 * dp
            setColor(bgColor)
        }
        elevation = 8 * dp
        setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
    }

    var dialog: android.app.AlertDialog? = null

    fun addRow(label: String, iconRes: Int, action: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (52 * dp).toInt()
            )
            setPadding((20 * dp).toInt(), 0, (20 * dp).toInt(), 0)
            isClickable = true; isFocusable = true
            background = ContextCompat.getDrawable(this@showAttachMenu,
                android.util.TypedValue().also {
                    theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
                }.resourceId)
            setOnClickListener { dialog?.dismiss(); action() }
        }
        row.addView(ImageView(this).apply {
            setImageResource(iconRes)
            imageTintList = android.content.res.ColorStateList.valueOf(accentInt)
            val sz = (22 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz).also { it.marginEnd = (16 * dp).toInt() }
            scaleType = ImageView.ScaleType.FIT_CENTER
        })
        row.addView(TextView(this).apply {
            text = label; textSize = 15f; setTextColor(textColor)
        })
        view.addView(row)
    }

    addRow("Photo", R.drawable.ic_lucide_image) { pickPhoto() }
    addRow("Video", R.drawable.ic_lucide_video) { pickVideo() }
    addRow("File", R.drawable.ic_lucide_paperclip) { pickFile() }

    dialog = android.app.AlertDialog.Builder(this)
        .setView(view)
        .create()
    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    dialog.show()
}

// ── Compose: redesigned attachment chips ─────────────────────────────────────

internal fun MainActivity.refreshAttachmentChips() {
    attachmentChipContainer.removeAllViews()
    val dp = resources.displayMetrics.density
    val hasAttachments = pendingAttachments.isNotEmpty()
    attachmentChipScroll.visibility = if (hasAttachments) android.view.View.VISIBLE else android.view.View.GONE
    attachmentChipDivider.visibility = if (hasAttachments) android.view.View.VISIBLE else android.view.View.GONE

    pendingAttachments.forEachIndexed { index, att ->
        val iconRes = attachmentIcon(att.mimeType)

        val card = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                (88 * dp).toInt(), (76 * dp).toInt()
            ).also { it.setMargins(0, 0, (8 * dp).toInt(), 0) }
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 10 * dp
                setColor(when (currentTheme) {
                    "light" -> "#EEEEEE".toColorInt()
                    "oled"  -> "#111111".toColorInt()
                    else    -> "#2A2A2A".toColorInt()
                })
            }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            val pad = (8 * dp).toInt()
            setPadding(pad, pad, pad, pad)
        }

        body.addView(ImageView(this).apply {
            setImageResource(iconRes)
            imageTintList = android.content.res.ColorStateList.valueOf(currentAccentColor.toColorInt())
            val sz = (26 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz).also {
                it.bottomMargin = (4 * dp).toInt()
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
        })
        body.addView(TextView(this).apply {
            val ext = att.name.substringAfterLast('.', "").take(4)
            text = if (att.name.length > 10) att.name.take(8) + "…" else att.name
            textSize = 10f
            maxLines = 1
            gravity = Gravity.CENTER
            setTextColor(if (currentTheme == "light") "#555555".toColorInt() else "#BDBDBD".toColorInt())
        })

        val removeBtn = TextView(this).apply {
            text = "✕"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            val sz = (18 * dp).toInt()
            layoutParams = FrameLayout.LayoutParams(sz, sz, Gravity.TOP or Gravity.END).also {
                it.topMargin = (2 * dp).toInt()
                it.marginEnd = (2 * dp).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor("#E53935".toColorInt())
            }
            setOnClickListener { removeAttachment(index) }
        }

        card.addView(body)

        // Edit-name badge (top-left): rename the file before sending.
        val editBtn = TextView(this).apply {
            text = "✎"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            val sz = (18 * dp).toInt()
            layoutParams = FrameLayout.LayoutParams(sz, sz, Gravity.TOP or Gravity.START).also {
                it.topMargin = (2 * dp).toInt()
                it.marginStart = (2 * dp).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(currentAccentColor.toColorInt())
            }
            setOnClickListener { renameAttachmentDialog(index) }
        }

        // For images/videos, overlay a real thumbnail (decoded off the main thread).
        val isImage = att.mimeType.startsWith("image/")
        val isVideo = att.mimeType.startsWith("video/")
        if (isImage || isVideo) {
            val radius = 10 * dp
            val thumb = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) =
                        o.setRoundRect(0, 0, v.width, v.height, radius)
                }
                visibility = android.view.View.GONE
            }
            card.addView(thumb)
            if (isVideo) {
                card.addView(TextView(this).apply {
                    text = "▶"
                    textSize = 16f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER
                    )
                })
            }
            val targetPx = (88 * dp).toInt()
            lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) { decodeAttachmentThumbnail(att, targetPx) }
                if (bmp != null) {
                    thumb.setImageBitmap(bmp)
                    thumb.visibility = android.view.View.VISIBLE
                    body.visibility = android.view.View.GONE
                }
            }
        }

        card.addView(removeBtn)
        card.addView(editBtn)
        attachmentChipContainer.addView(card)
    }
}

/** Renames a pending attachment, keeping its original extension if the user drops it. */
internal fun MainActivity.renameAttachmentDialog(index: Int) {
    if (index !in pendingAttachments.indices) return
    val att = pendingAttachments[index]
    val dp = resources.displayMetrics.density
    val textColor = if (currentTheme == "light") "#212121".toColorInt() else Color.WHITE
    val secondaryColor = if (currentTheme == "light") "#757575".toColorInt() else "#9E9E9E".toColorInt()
    val accentInt = currentAccentColor.toColorInt()

    val input = android.widget.EditText(this).apply {
        setText(att.name)
        setSelection(att.name.length)
        setTextColor(textColor)
        setHintTextColor(secondaryColor)
        backgroundTintList = android.content.res.ColorStateList.valueOf(secondaryColor)
        textSize = 15f
        maxLines = 1
    }
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val p = (22 * dp).toInt()
        setPadding(p, p, p, (14 * dp).toInt())
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 20 * dp
            setColor(getDialogBackgroundColor())
        }
        addView(TextView(this@renameAttachmentDialog).apply {
            text = "Rename file"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (16 * dp).toInt() }
        })
        addView(input)
    }
    val dialog = androidx.appcompat.app.AlertDialog.Builder(this).setView(root).create()
    val btnRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = (8 * dp).toInt() }
    }
    fun btn(label: String, color: Int, bold: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14f; setTextColor(color)
        if (bold) setTypeface(null, Typeface.BOLD)
        setPadding((14 * dp).toInt(), (10 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }
    btnRow.addView(btn("Cancel", secondaryColor, false) { dialog.dismiss() })
    btnRow.addView(btn("Rename", accentInt, true) {
        var newName = input.text.toString().trim()
        if (newName.isBlank()) { dialog.dismiss(); return@btn }
        // Preserve the original extension if the user removed it.
        val origExt = att.name.substringAfterLast('.', "")
        if (origExt.isNotBlank() && !newName.contains('.')) newName = "$newName.$origExt"
        pendingAttachments[index] = att.copy(name = newName)
        refreshAttachmentChips()
        dialog.dismiss()
    })
    root.addView(btnRow)
    dialog.show()
    dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
    dialog.window?.attributes?.let { lp ->
        lp.width = (resources.displayMetrics.widthPixels * 0.88f).toInt()
        dialog.window?.attributes = lp
    }
}

/** Decodes a downsampled thumbnail for image/video attachments, or null on failure. */
private fun MainActivity.decodeAttachmentThumbnail(att: MainActivity.AttachmentData, targetPx: Int): Bitmap? {
    return try {
        if (att.mimeType.startsWith("video/")) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(this, att.uri)
                r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally {
                r.release()
            }
        } else {
            // Bounds pass first, then decode with an inSampleSize that fits the target.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(att.uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            var sample = 1
            val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
            while (maxDim / sample > targetPx * 2) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            contentResolver.openInputStream(att.uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        }
    } catch (e: Exception) {
        null
    }
}

/** Decodes image bytes at (near) full resolution, downsampling only past a safe ceiling to avoid OOM. */
private fun decodeFullBitmap(bytes: ByteArray, maxDimPx: Int = 4096): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxDim / sample > maxDimPx) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    } catch (e: Exception) {
        null
    }
}

/** Decodes a downsampled thumbnail from in-memory image bytes, or null on failure. */
private fun decodeBytesThumbnail(bytes: ByteArray, targetPx: Int): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxDim / sample > targetPx * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    } catch (e: Exception) {
        null
    }
}

/**
 * Decodes the first frame of a remote video via its authenticated URL. MediaMetadataRetriever
 * issues its own ranged HTTP reads, so only the metadata + one frame are fetched, not the whole file.
 */
private fun decodeVideoUrlThumbnail(url: String, headers: Map<String, String>): Bitmap? {
    return try {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(url, headers)
            r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } finally {
            r.release()
        }
    } catch (e: Exception) {
        null
    }
}

// ── Email detail: attachment strip at top ─────────────────────────────────────

internal fun MainActivity.buildEmailAttachmentRow(
    attachments: List<EmailAttachmentInfo>,
    account: JMapClient.ConnectedAccount
): LinearLayout {
    val dp = resources.displayMetrics.density
    val accentInt = currentAccentColor.toColorInt()
    val isLight = currentTheme == "light"

    val cardBg = when (currentTheme) {
        "light" -> "#EEEEEE".toColorInt()
        "oled"  -> "#111111".toColorInt()
        else    -> "#2A2A2A".toColorInt()
    }
    val nameColor = if (isLight) "#212121".toColorInt() else Color.WHITE
    val subColor = if (isLight) "#757575".toColorInt() else "#9E9E9E".toColorInt()

    // Vertical list of attachment cards, rendered below the email body with no separator bar.
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((12 * dp).toInt(), (4 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    fun selectableBg(): android.graphics.drawable.Drawable? =
        ContextCompat.getDrawable(this,
            android.util.TypedValue().also {
                theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true)
            }.resourceId)

    // Compact media card (image/video): name + download on top, thumbnail below. Laid horizontally.
    fun buildMediaCard(att: EmailAttachmentInfo): View {
        val isVideo = att.mimeType.startsWith("video/")
        val cardW = (104 * dp).toInt()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(cardW, ViewGroup.LayoutParams.WRAP_CONTENT)
                .also { it.marginEnd = (8 * dp).toInt() }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
        }
        header.addView(TextView(this).apply {
            text = att.name
            textSize = 11f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setTextColor(nameColor)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_lucide_download)
            imageTintList = android.content.res.ColorStateList.valueOf(accentInt)
            val sz = (18 * dp).toInt()
            val pad = (3 * dp).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(sz + pad * 2, sz + pad * 2)
            scaleType = ImageView.ScaleType.FIT_CENTER
            isClickable = true; isFocusable = true
            background = selectableBg()
            setOnClickListener { saveAttachmentToDownloads(att, account) }
        })
        card.addView(header)

        val previewH = (76 * dp).toInt()
        val preview = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, previewH
            )
            isClickable = true; isFocusable = true
        }
        val thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        }
        preview.addView(thumb)
        val play = if (isVideo) TextView(this).apply {
            text = "▶"
            textSize = 30f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            visibility = View.GONE
        }.also { preview.addView(it) } else null
        preview.setOnClickListener { showAttachmentInApp(att, account) }
        card.addView(preview)

        val targetPx = cardW
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                if (isVideo) {
                    val req = jmapClient.blobDownloadRequest(account, att.blobId, att.name, att.mimeType)
                        ?: return@withContext null
                    decodeVideoUrlThumbnail(req.first, req.second)
                } else {
                    val bytes = blobByteCache.get(att.blobId)
                        ?: jmapClient.downloadBlob(account, att.blobId, att.name, att.mimeType)
                            ?.also { blobByteCache.put(att.blobId, it) }
                        ?: return@withContext null
                    decodeBytesThumbnail(bytes, targetPx)
                }
            }
            if (bmp != null) {
                thumb.setImageBitmap(bmp)
                play?.visibility = View.VISIBLE
            }
        }
        return card
    }

    // Full-width row for non-media files: icon + name/size + download.
    fun buildFileCard(att: EmailAttachmentInfo): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (10 * dp).toInt(), 0, (10 * dp).toInt())
        }
        header.addView(ImageView(this).apply {
            setImageResource(attachmentIcon(att.mimeType))
            imageTintList = android.content.res.ColorStateList.valueOf(accentInt)
            val sz = (20 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz).also { it.marginEnd = (10 * dp).toInt() }
            scaleType = ImageView.ScaleType.FIT_CENTER
        })
        val nameCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        nameCol.addView(TextView(this).apply {
            text = att.name
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setTextColor(nameColor)
        })
        nameCol.addView(TextView(this).apply {
            text = formatAttachmentSize(att.size)
            textSize = 11f
            setTextColor(subColor)
        })
        header.addView(nameCol)
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_lucide_download)
            imageTintList = android.content.res.ColorStateList.valueOf(accentInt)
            val sz = (24 * dp).toInt()
            val pad = (4 * dp).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(sz + pad * 2, sz + pad * 2)
            scaleType = ImageView.ScaleType.FIT_CENTER
            isClickable = true; isFocusable = true
            background = selectableBg()
            setOnClickListener { saveAttachmentToDownloads(att, account) }
        })
        return header
    }

    val media = attachments.filter { it.mimeType.startsWith("image/") || it.mimeType.startsWith("video/") }
    val files = attachments.filterNot { it.mimeType.startsWith("image/") || it.mimeType.startsWith("video/") }

    // Media laid out horizontally in a scrollable row; show 2, "+N" reveals the rest.
    val mediaRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    if (media.isNotEmpty()) {
        container.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(mediaRow)
        })
    }

    val maxVisibleMedia = 2
    media.take(maxVisibleMedia).forEach { mediaRow.addView(buildMediaCard(it)) }

    val hiddenMedia = media.drop(maxVisibleMedia)
    fun addFiles() = files.forEach { container.addView(buildFileCard(it)) }

    if (hiddenMedia.isEmpty()) {
        addFiles()
    } else {
        val moreRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
            isClickable = true; isFocusable = true
            background = selectableBg()
        }
        val extra = hiddenMedia.size + files.size
        moreRow.addView(TextView(this).apply {
            text = "+$extra more attachment${if (extra > 1) "s" else ""}"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accentInt)
        })
        moreRow.setOnClickListener {
            container.removeView(moreRow)
            hiddenMedia.forEach { mediaRow.addView(buildMediaCard(it)) }
            addFiles()
        }
        container.addView(moreRow)
    }

    return container
}

/**
 * Opens an image or video attachment full-screen inside the app instead of handing it
 * to an external viewer. Images render in a zoomable view; videos play inline with controls.
 */
private fun MainActivity.showAttachmentInApp(
    att: EmailAttachmentInfo,
    account: JMapClient.ConnectedAccount
) {
    val isVideo = att.mimeType.startsWith("video/")
    val isImage = att.mimeType.startsWith("image/")
    if (!isVideo && !isImage) {
        showAttachmentOptions(att, account)
        return
    }

    val dp = resources.displayMetrics.density
    val bgColor = getDialogBackgroundColor()
    val textColor = if (currentTheme == "light") "#212121".toColorInt() else Color.WHITE
    val accentInt = currentAccentColor.toColorInt()

    // Styled centered card, like the popup menus.
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16 * dp
            setColor(bgColor)
        }
        clipToOutline = true
        elevation = 12 * dp
        val w = (resources.displayMetrics.widthPixels * 0.9f).toInt()
        layoutParams = FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
    }

    lateinit var dialog: android.app.Dialog
    // Tapping the scrim outside the card closes the viewer, same as the X.
    val outer = FrameLayout(this).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        setBackgroundColor(0xCC000000.toInt())
        isClickable = true; isFocusable = true
        setOnClickListener { dialog.dismiss() }
        addView(card)
    }
    // The card swallows its own taps so they do not bubble up to the scrim.
    card.isClickable = true
    dialog = android.app.Dialog(this).apply {
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        setContentView(outer)
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        setCancelable(true)
    }

    // Header: filename top-left; download then close (X) top-right.
    val header = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding((16 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt())
    }
    header.addView(TextView(this).apply {
        text = att.name
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        setTextColor(textColor)
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    })
    fun headerBtn(iconRes: Int, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(iconRes)
        imageTintList = android.content.res.ColorStateList.valueOf(accentInt)
        val sz = (24 * dp).toInt()
        val pad = (6 * dp).toInt()
        setPadding(pad, pad, pad, pad)
        layoutParams = LinearLayout.LayoutParams(sz + pad * 2, sz + pad * 2)
            .also { it.marginStart = (4 * dp).toInt() }
        scaleType = ImageView.ScaleType.FIT_CENTER
        isClickable = true; isFocusable = true
        background = ContextCompat.getDrawable(this@showAttachmentInApp,
            android.util.TypedValue().also {
                theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true)
            }.resourceId)
        setOnClickListener { onClick() }
    }
    header.addView(headerBtn(R.drawable.ic_lucide_download) { saveAttachmentToDownloads(att, account) })
    header.addView(headerBtn(R.drawable.ic_lucide_x) { dialog.dismiss() })
    card.addView(header)

    // Media area: bounded, centred, cropping overflow (not full screen).
    val mediaH = (resources.displayMetrics.heightPixels * 0.55f).toInt()
    val mediaFrame = FrameLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, mediaH
        )
        setBackgroundColor(bgColor)
    }
    val progress = android.widget.ProgressBar(this).apply {
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        )
        indeterminateTintList = android.content.res.ColorStateList.valueOf(accentInt)
    }
    mediaFrame.addView(progress)
    card.addView(mediaFrame)

    lifecycleScope.launch {
        if (isImage) {
            val bytes = withContext(Dispatchers.IO) {
                blobByteCache.get(att.blobId)
                    ?: jmapClient.downloadBlob(account, att.blobId, att.name, att.mimeType)
                        ?.also { blobByteCache.put(att.blobId, it) }
            }
            val bmp = bytes?.let {
                withContext(Dispatchers.IO) { decodeFullBitmap(it) }
            }
            progress.visibility = View.GONE
            if (bmp == null) {
                Snackbar.make(drawerLayout, "Cannot display image", Snackbar.LENGTH_SHORT).show()
                dialog.dismiss()
                return@launch
            }
            // Let the frame shrink to the image's aspect (capped at mediaH) so the
            // whole image shows with no black bands and no zoom/crop.
            mediaFrame.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            mediaFrame.addView(ImageView(this@showAttachmentInApp).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
                maxHeight = mediaH
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            }, 0)
        } else {
            val req = withContext(Dispatchers.IO) {
                jmapClient.blobDownloadRequest(account, att.blobId, att.name, att.mimeType)
            }
            progress.visibility = View.GONE
            if (req == null) {
                Snackbar.make(drawerLayout, "Load failed", Snackbar.LENGTH_SHORT).show()
                dialog.dismiss()
                return@launch
            }
            val videoView = android.widget.VideoView(this@showAttachmentInApp).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                )
            }
            // Custom transparent controls centred inside the video (no MediaController bar):
            // [rewind 5s] [play/pause] [forward 5s].
            val controls = LinearLayout(this@showAttachmentInApp).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                )
            }
            // Circular-arrow skip button with a "5" badge in the centre.
            fun skipButton(iconRes: Int, deltaMs: Int): View {
                val sz = (52 * dp).toInt()
                return FrameLayout(this@showAttachmentInApp).apply {
                    layoutParams = LinearLayout.LayoutParams(sz, sz)
                        .also { it.marginStart = (16 * dp).toInt(); it.marginEnd = (16 * dp).toInt() }
                    isClickable = true; isFocusable = true
                    addView(ImageView(this@showAttachmentInApp).apply {
                        setImageResource(iconRes)
                        imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    })
                    addView(TextView(this@showAttachmentInApp).apply {
                        text = "5"
                        textSize = 10f
                        setTextColor(Color.WHITE)
                        typeface = Typeface.DEFAULT_BOLD
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                            Gravity.CENTER
                        )
                    })
                    setOnClickListener {
                        val target = (videoView.currentPosition + deltaMs)
                            .coerceIn(0, videoView.duration.coerceAtLeast(0))
                        videoView.seekTo(target)
                    }
                }
            }
            val playPause = ImageView(this@showAttachmentInApp).apply {
                setImageResource(R.drawable.ic_lucide_pause)
                imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                val sz = (56 * dp).toInt()
                setPadding((12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(sz, sz)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x66000000)
                }
                isClickable = true; isFocusable = true
            }
            fun syncIcon() = playPause.setImageResource(
                if (videoView.isPlaying) R.drawable.ic_lucide_pause else R.drawable.ic_lucide_play
            )
            playPause.setOnClickListener {
                if (videoView.isPlaying) videoView.pause() else videoView.start()
                syncIcon()
            }
            controls.addView(skipButton(R.drawable.ic_rotate_ccw, -5000))
            controls.addView(playPause)
            controls.addView(skipButton(R.drawable.ic_rotate_cw, 5000))

            // Bottom seek bar showing/scrubbing video progress.
            val seekBar = android.widget.SeekBar(this@showAttachmentInApp).apply {
                progressTintList = android.content.res.ColorStateList.valueOf(accentInt)
                thumbTintList = android.content.res.ColorStateList.valueOf(accentInt)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM
                ).also { it.setMargins((8 * dp).toInt(), 0, (8 * dp).toInt(), (4 * dp).toInt()) }
            }

            // A single overlay group so a tap shows/hides both the buttons and the seek bar,
            // and they auto-hide while playing.
            val hideRunnable = Runnable {
                if (videoView.isPlaying) {
                    controls.visibility = View.GONE
                    seekBar.visibility = View.GONE
                }
            }
            fun showControls() {
                controls.visibility = View.VISIBLE
                seekBar.visibility = View.VISIBLE
                mediaFrame.removeCallbacks(hideRunnable)
                mediaFrame.postDelayed(hideRunnable, 3000)
            }

            playPause.setOnClickListener {
                if (videoView.isPlaying) videoView.pause() else videoView.start()
                syncIcon()
                showControls()
            }
            mediaFrame.setOnClickListener {
                if (controls.visibility == View.VISIBLE) {
                    controls.visibility = View.GONE
                    seekBar.visibility = View.GONE
                    mediaFrame.removeCallbacks(hideRunnable)
                } else showControls()
            }

            var userSeeking = false
            seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) videoView.seekTo(p)
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar) {
                    userSeeking = true; mediaFrame.removeCallbacks(hideRunnable)
                }
                override fun onStopTrackingTouch(sb: android.widget.SeekBar) {
                    userSeeking = false; mediaFrame.postDelayed(hideRunnable, 3000)
                }
            })
            // Poll progress into the seek bar while the dialog is open.
            val tick = object : Runnable {
                override fun run() {
                    if (!userSeeking && videoView.duration > 0) {
                        seekBar.max = videoView.duration
                        seekBar.progress = videoView.currentPosition
                    }
                    seekBar.postDelayed(this, 500)
                }
            }

            videoView.setVideoURI(Uri.parse(req.first), req.second)
            videoView.setOnPreparedListener {
                // Size the VideoView to the video's exact aspect (fitting within the
                // frame) and centre it, so the video is never distorted and the frame
                // background (dialog colour) fills any remaining space - no app bleed.
                val vw = it.videoWidth.toFloat()
                val vh = it.videoHeight.toFloat()
                if (vw > 0 && vh > 0) {
                    val aspect = vw / vh
                    var w = mediaFrame.width.toFloat().let { fw -> if (fw > 0) fw else resources.displayMetrics.widthPixels.toFloat() }
                    var h = w / aspect
                    if (h > mediaH) { h = mediaH.toFloat(); w = h * aspect }
                    videoView.layoutParams = FrameLayout.LayoutParams(w.toInt(), h.toInt(), Gravity.CENTER)
                    videoView.requestLayout()
                }
                it.start(); syncIcon()
                seekBar.max = videoView.duration
                seekBar.post(tick)
                // Controls hidden while playing; reveal only on tap.
                controls.visibility = View.GONE
                seekBar.visibility = View.GONE
            }
            videoView.setOnCompletionListener {
                syncIcon(); showControls()
            }
            mediaFrame.addView(seekBar)
            videoView.setOnErrorListener { _, _, _ ->
                Snackbar.make(drawerLayout, "Cannot play video", Snackbar.LENGTH_SHORT).show()
                true
            }
            mediaFrame.addView(videoView, 0)
            mediaFrame.addView(controls)
        }
    }

    dialog.show()
}

private fun MainActivity.showAttachmentOptions(
    att: EmailAttachmentInfo,
    account: JMapClient.ConnectedAccount
) {
    val dp = resources.displayMetrics.density
    val bgColor = getDialogBackgroundColor()
    val textColor = if (currentTheme == "light") "#212121".toColorInt() else Color.WHITE
    val accentInt = currentAccentColor.toColorInt()
    val sizeStr = formatAttachmentSize(att.size)

    val view = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16 * dp
            setColor(bgColor)
        }
        elevation = 8 * dp
    }

    // Header
    view.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding((20 * dp).toInt(), (16 * dp).toInt(), (20 * dp).toInt(), (12 * dp).toInt())

        addView(ImageView(this@showAttachmentOptions).apply {
            setImageResource(attachmentIcon(att.mimeType))
            imageTintList = android.content.res.ColorStateList.valueOf(accentInt)
            val sz = (28 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz).also { it.marginEnd = (12 * dp).toInt() }
            scaleType = ImageView.ScaleType.FIT_CENTER
        })
        val textCol = LinearLayout(this@showAttachmentOptions).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(this@showAttachmentOptions).apply {
            text = att.name
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textColor)
            maxLines = 2
        })
        textCol.addView(TextView(this@showAttachmentOptions).apply {
            text = sizeStr
            textSize = 12f
            setTextColor(if (currentTheme == "light") "#757575".toColorInt() else "#9E9E9E".toColorInt())
        })
        addView(textCol)
    })

    view.addView(android.view.View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        setBackgroundColor(0x22808080)
    })
    view.setPadding(0, 0, 0, (8 * dp).toInt())

    var dialog: android.app.AlertDialog? = null

    fun addRow(label: String, iconRes: Int, action: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (52 * dp).toInt()
            )
            setPadding((20 * dp).toInt(), 0, (20 * dp).toInt(), 0)
            isClickable = true; isFocusable = true
            background = ContextCompat.getDrawable(this@showAttachmentOptions,
                android.util.TypedValue().also {
                    theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
                }.resourceId)
            setOnClickListener { dialog?.dismiss(); action() }
        }
        row.addView(ImageView(this).apply {
            setImageResource(iconRes)
            imageTintList = android.content.res.ColorStateList.valueOf(accentInt)
            val sz = (20 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz).also { it.marginEnd = (16 * dp).toInt() }
            scaleType = ImageView.ScaleType.FIT_CENTER
        })
        row.addView(TextView(this).apply {
            text = label; textSize = 15f; setTextColor(textColor)
        })
        view.addView(row)
    }

    addRow("Open", R.drawable.ic_lucide_eye) { openAttachment(att, account) }
    addRow("Save to Downloads", R.drawable.ic_lucide_file_text) { saveAttachmentToDownloads(att, account) }

    dialog = android.app.AlertDialog.Builder(this)
        .setView(view)
        .create()
    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    dialog.show()
}

private fun MainActivity.openAttachment(
    att: EmailAttachmentInfo,
    account: JMapClient.ConnectedAccount
) {
    lifecycleScope.launch {
        val bytes = jmapClient.downloadBlob(account, att.blobId, att.name, att.mimeType)
        if (bytes == null) {
            Snackbar.make(drawerLayout, "Download failed", Snackbar.LENGTH_SHORT).show()
            return@launch
        }
        val safeName = sanitizeAttachmentName(att.name)
        withContext(Dispatchers.IO) {
            val dir = File(cacheDir, "attachments").also { it.mkdirs() }
            val file = File(dir, safeName)
            file.writeBytes(bytes)
        }
        val dir = File(cacheDir, "attachments")
        val file = File(dir, safeName)
        val uri = FileProvider.getUriForFile(this@openAttachment,
            "${packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, att.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, "Open with"))
        } catch (e: Exception) {
            Snackbar.make(drawerLayout, "No app found to open this file", Snackbar.LENGTH_SHORT).show()
        }
    }
}

private fun MainActivity.saveAttachmentToDownloads(
    att: EmailAttachmentInfo,
    account: JMapClient.ConnectedAccount
) {
    lifecycleScope.launch {
        showThemedSnackbar("Downloading…")
        val bytes = jmapClient.downloadBlob(account, att.blobId, att.name, att.mimeType)
        if (bytes == null) {
            showThemedSnackbar("Download failed")
            return@launch
        }
        val safeName = sanitizeAttachmentName(att.name)
        val savedUri = withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                        put(MediaStore.Downloads.MIME_TYPE, att.mimeType)
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: return@withContext null
                    contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                    uri
                } else {
                    @Suppress("DEPRECATION")
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    dir.mkdirs()
                    val f = File(dir, safeName).apply { writeBytes(bytes) }
                    FileProvider.getUriForFile(this@saveAttachmentToDownloads,
                        "${packageName}.fileprovider", f)
                }
            } catch (e: Exception) {
                null
            }
        }
        if (savedUri == null) {
            showThemedSnackbar("Failed to save")
            return@launch
        }
        showThemedSnackbar(
            "Saved to Downloads",
            actionLabel = "Open",
            actionIcon = R.drawable.ic_lucide_external_link
        ) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(savedUri, att.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(Intent.createChooser(intent, "Open with"))
            } catch (e: Exception) {
                showThemedSnackbar("No app found to open this file")
            }
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

/**
 * Attachment names come from the untrusted email part name; strip path components
 * so a name like "../../shared_prefs/x.xml" cannot escape the target directory.
 */
internal fun sanitizeAttachmentName(name: String): String =
    name.substringAfterLast('/')
        .substringAfterLast('\\')
        .replace(Regex("[^A-Za-z0-9 ()._-]"), "_")
        .trim()
        .removePrefix("..")
        .ifBlank { "attachment" }

internal fun attachmentIcon(mimeType: String): Int = when {
    mimeType.startsWith("image/") -> R.drawable.ic_lucide_image
    mimeType.startsWith("video/") -> R.drawable.ic_lucide_video
    else -> R.drawable.ic_lucide_file_text
}

private fun formatAttachmentSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format("%.1f MB", bytes / 1048576.0)
}
