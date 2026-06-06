package com.falseenvironment.jmapjolt

import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup
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
                setColor("#757575".toColorInt())
            }
            setOnClickListener { removeAttachment(index) }
        }

        card.addView(body)
        card.addView(removeBtn)
        attachmentChipContainer.addView(card)
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
    wrapper.addView(scroll)
    wrapper.addView(android.view.View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        setBackgroundColor(dividerColor)
    })
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
        withContext(Dispatchers.IO) {
            val dir = File(cacheDir, "attachments").also { it.mkdirs() }
            val file = File(dir, att.name)
            file.writeBytes(bytes)
        }
        val dir = File(cacheDir, "attachments")
        val file = File(dir, att.name)
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
        val saved = withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, att.name)
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
                    File(dir, att.name).writeBytes(bytes)
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
