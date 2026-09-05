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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What happens to an attachment once the user taps it: the in-app viewer, the
 * options sheet, and handing the file to another app (open / save / share).
 * The chips and rows that display attachments stay in AttachmentHelper.
 */

internal fun MainActivity.showAttachmentInApp(
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
    header.addView(headerBtn(R.drawable.ic_lucide_share_2) { shareAttachment(att, account) })
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
                showThemedSnackbar("Cannot display image")
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
                showThemedSnackbar("Load failed")
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
                showThemedSnackbar("Cannot play video")
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
            showThemedSnackbar("Download failed")
            return@launch
        }
        val safeName = sanitizeAttachmentName(att.name)
        val file = withContext(Dispatchers.IO) {
            File(AttachmentCache.prepare(this@openAttachment), safeName).apply { writeBytes(bytes) }
        }
        val uri = FileProvider.getUriForFile(this@openAttachment,
            "${packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, att.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, "Open with"))
        } catch (e: Exception) {
            showThemedSnackbar("No app found to open this file")
        }
    }
}

internal fun MainActivity.saveAttachmentToDownloads(
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

/** Download the blob to the FileProvider cache and open the Android share sheet. */
private fun MainActivity.shareAttachment(
    att: EmailAttachmentInfo,
    account: JMapClient.ConnectedAccount
) {
    lifecycleScope.launch {
        showThemedSnackbar("Preparing…")
        val bytes = jmapClient.downloadBlob(account, att.blobId, att.name, att.mimeType)
        if (bytes == null) {
            showThemedSnackbar("Download failed")
            return@launch
        }
        val safeName = sanitizeAttachmentName(att.name)
        val uri = withContext(Dispatchers.IO) {
            try {
                val dir = AttachmentCache.prepare(this@shareAttachment)
                val f = File(dir, safeName).apply { writeBytes(bytes) }
                FileProvider.getUriForFile(this@shareAttachment, "${packageName}.fileprovider", f)
            } catch (e: Exception) {
                null
            }
        }
        if (uri == null) {
            showThemedSnackbar("Failed to share")
            return@launch
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = att.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, "Share"))
        } catch (e: Exception) {
            showThemedSnackbar("No app found to share this file")
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

internal fun formatAttachmentSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format("%.1f MB", bytes / 1048576.0)
}

/**
 * Staging area for attachments handed to other apps through the FileProvider.
 *
 * Files written here used to live forever: every open or share left a copy behind, so the
 * cache grew with the mailbox. Contacts already sweep their own share directory
 * (`ContactsPanel.shareSelected`); this applies the same idea with an age limit instead of a
 * blanket wipe, because the receiving app may still be reading the URI it was just granted.
 */
internal object AttachmentCache {

    private const val DIR_NAME = "attachments"
    private const val MAX_AGE_MS = 24L * 60 * 60 * 1000

    /** Blocking — call from [Dispatchers.IO]. Returns the directory, swept and ready to write. */
    fun prepare(context: android.content.Context): File {
        purgeExpired(context)
        return File(context.cacheDir, DIR_NAME).apply { mkdirs() }
    }

    /** Blocking — call from [Dispatchers.IO]. Drops cached attachments older than 24h. */
    fun purgeExpired(context: android.content.Context) {
        val dir = File(context.cacheDir, DIR_NAME)
        if (!dir.isDirectory) return
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        val stale = dir.listFiles()?.filter { it.lastModified() < cutoff } ?: return
        val deleted = stale.count { runCatching { it.delete() }.getOrDefault(false) }
        if (deleted > 0) Log.d("AttachmentCache", "Purged $deleted expired cached attachment(s)")
    }
}
