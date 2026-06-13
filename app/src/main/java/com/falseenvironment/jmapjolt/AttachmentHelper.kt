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

    val scroll = android.widget.HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
    }

    attachments.forEach { att ->
        val iconRes = attachmentIcon(att.mimeType)

        val card = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                (80 * dp).toInt(), (80 * dp).toInt()
            ).also { it.setMargins(0, 0, (8 * dp).toInt(), 0) }
            isClickable = true; isFocusable = true
            background = ContextCompat.getDrawable(this@buildEmailAttachmentRow,
                android.util.TypedValue().also {
                    theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
                }.resourceId)
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
            imageTintList = android.content.res.ColorStateList.valueOf(accentInt)
            val sz = (28 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz).also { it.bottomMargin = (4 * dp).toInt() }
            scaleType = ImageView.ScaleType.FIT_CENTER
        })
        body.addView(TextView(this).apply {
            text = if (att.name.length > 9) att.name.take(8) + "…" else att.name
            textSize = 9f
            maxLines = 1
            gravity = Gravity.CENTER
            setTextColor(if (isLight) "#555555".toColorInt() else "#BDBDBD".toColorInt())
        })
        card.addView(body)

        // Image/video attachments: download + decode a thumbnail in the background.
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
                visibility = View.GONE
            }
            card.addView(thumb)
            val play = if (isVideo) TextView(this).apply {
                text = "▶"
                textSize = 16f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
                visibility = View.GONE
            }.also { card.addView(it) } else null
            val targetPx = (80 * dp).toInt()
            lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    if (isVideo) {
                        // Stream ranged reads via the download URL instead of pulling the whole file.
                        val req = jmapClient.blobDownloadRequest(account, att.blobId, att.name, att.mimeType)
                        if (req == null) {
                            Log.w("AttachmentThumb", "url null for ${att.name} (${att.mimeType})")
                            return@withContext null
                        }
                        val out = decodeVideoUrlThumbnail(req.first, req.second)
                        if (out == null) Log.w("AttachmentThumb", "decode null for ${att.name} (${att.mimeType})")
                        out
                    } else {
                        val bytes = jmapClient.downloadBlob(account, att.blobId, att.name, att.mimeType)
                        if (bytes == null) {
                            Log.w("AttachmentThumb", "download null for ${att.name} (${att.mimeType})")
                            return@withContext null
                        }
                        val out = decodeBytesThumbnail(bytes, targetPx)
                        if (out == null) Log.w("AttachmentThumb", "decode null for ${att.name} (${att.mimeType})")
                        out
                    }
                }
                if (bmp != null) {
                    thumb.setImageBitmap(bmp)
                    thumb.visibility = View.VISIBLE
                    play?.visibility = View.VISIBLE
                    body.visibility = View.GONE
                }
            }
        }

        card.setOnClickListener { showAttachmentOptions(att, account) }

        row.addView(card)
    }

    scroll.addView(row)

    val stripBg = when (currentTheme) {
        "light" -> "#F5F5F5".toColorInt()
        "oled"  -> android.graphics.Color.BLACK
        else    -> "#212121".toColorInt()
    }
    val dividerColor = when (currentTheme) {
        "light" -> "#E0E0E0".toColorInt()
        "oled"  -> "#222222".toColorInt()
        else    -> "#333333".toColorInt()
    }
    val wrapper = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(stripBg)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }
    wrapper.addView(android.view.View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        setBackgroundColor(dividerColor)
    })
    wrapper.addView(scroll)
    return wrapper
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
        Snackbar.make(drawerLayout, "Downloading…", Snackbar.LENGTH_SHORT).show()
        val bytes = jmapClient.downloadBlob(account, att.blobId, att.name, att.mimeType)
        if (bytes == null) {
            Snackbar.make(drawerLayout, "Download failed", Snackbar.LENGTH_SHORT).show()
            return@launch
        }
        val safeName = sanitizeAttachmentName(att.name)
        val saved = withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                        put(MediaStore.Downloads.MIME_TYPE, att.mimeType)
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: return@withContext false
                    contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                } else {
                    @Suppress("DEPRECATION")
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    dir.mkdirs()
                    File(dir, safeName).writeBytes(bytes)
                }
                true
            } catch (e: Exception) {
                false
            }
        }
        Snackbar.make(drawerLayout,
            if (saved) "Saved to Downloads" else "Failed to save",
            Snackbar.LENGTH_SHORT).show()
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
