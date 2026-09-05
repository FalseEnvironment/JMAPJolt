package com.falseenvironment.jmapjolt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.Html
import android.text.Layout
import android.text.Spannable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.text.style.AlignmentSpan
import android.text.style.BulletSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.Log
import android.util.Patterns
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Gravity
import android.view.Menu
import android.widget.HorizontalScrollView
import android.widget.PopupMenu
import android.graphics.PorterDuff
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import androidx.appcompat.widget.SwitchCompat
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.core.view.GravityCompat
import androidx.core.widget.CompoundButtonCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.viewpager2.widget.ViewPager2
import coil.load
import com.google.android.material.navigation.NavigationView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.unifiedpush.android.connector.INSTANCE_DEFAULT
import org.unifiedpush.android.connector.UnifiedPush

/**
 * Multi-account state: the saved-account list and its JSON persistence, per-account
 * display name, colour and avatar, the account switcher, and the profile/avatar
 * dialogs that edit them.
 */

internal fun MainActivity.persistConnectedAccount(account: JMapClient.ConnectedAccount, serverUrl: String) {
    val existingIndex =
            savedAccounts.indexOfFirst { it.email.equals(account.email, ignoreCase = true) }
    val entry =
            AccountEntry(
                    email = account.email,
                    password = account.password,
                    serverUrl = serverUrl,
                    sessionUrl = account.sessionUrl,
                    apiUrl = account.apiUrl,
                    accountId = account.accountId
            )
    if (existingIndex >= 0) savedAccounts[existingIndex] = entry else savedAccounts.add(entry)
    currentAccountEmail = account.email
    saveAccounts()
    renderAccountHeader()
}

internal fun MainActivity.loadAccounts() {
    val raw =
            SecureStorage.prefs(this).getString(MainActivity.KEY_ACCOUNTS_JSON, null)
                    ?: return
    val root = JSONObject(raw)
    val list = root.optJSONArray("accounts") ?: JSONArray()
    savedAccounts.clear()
    for (i in 0 until list.length()) {
        val item = list.optJSONObject(i) ?: continue
        savedAccounts.add(
                AccountEntry(
                        email = item.optString("email"),
                        password = item.optString("password"),
                        serverUrl = item.optString("serverUrl"),
                        sessionUrl = item.optString("sessionUrl"),
                        apiUrl = item.optString("apiUrl"),
                        accountId = item.optString("accountId")
                )
        )
    }
    val current = root.optString("current", "")
    currentAccountEmail = current.ifBlank { null }
    renderAccountHeader()
}

internal fun MainActivity.saveAccounts() {
    val accounts = JSONArray()
    savedAccounts.forEach {
        accounts.put(
                JSONObject()
                        .put("email", it.email)
                        .put("password", it.password)
                        .put("serverUrl", it.serverUrl)
                        .put("sessionUrl", it.sessionUrl)
                        .put("apiUrl", it.apiUrl)
                        .put("accountId", it.accountId)
        )
    }
    val root = JSONObject().put("accounts", accounts).put("current", currentAccountEmail ?: "")
    SecureStorage.prefs(this)
            .edit()
            .putString(MainActivity.KEY_ACCOUNTS_JSON, root.toString())
            .apply()
}

private fun MainActivity.closeAccountsList() {
    drawerAccountsList.visibility = View.GONE
    drawerAccountArrow.rotation = 0f
}

/** Internal storage file holding the custom avatar photo for an account. */
private fun MainActivity.accountAvatarFile(email: String): java.io.File =
    java.io.File(filesDir, "avatar_" + email.lowercase().replace(Regex("[^a-z0-9]"), "_") + ".jpg")

/** Display name shown in the account section; falls back to the local part of the email. */
internal fun MainActivity.getAccountDisplayName(email: String): String {
    val saved = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE).getString("account_name_$email", null)
    return saved?.takeIf { it.isNotBlank() } ?: email.substringBefore('@')
}

private fun MainActivity.setAccountDisplayName(email: String, name: String) {
    getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE).edit()
        .putString("account_name_$email", name.trim()).apply()
}

private fun MainActivity.centerCropSquare(src: android.graphics.Bitmap): android.graphics.Bitmap {
    val dim = minOf(src.width, src.height)
    val x = (src.width - dim) / 2
    val y = (src.height - dim) / 2
    return android.graphics.Bitmap.createBitmap(src, x, y, dim, dim)
}

/** Circular avatar: custom photo if present, otherwise a colored disc with the first initial. */
internal fun MainActivity.buildAccountAvatar(email: String, sizePx: Int): android.graphics.Bitmap {
    val bmp = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val r = sizePx / 2f
    val file = accountAvatarFile(email)
    val photo = if (file.exists())
        try { android.graphics.BitmapFactory.decodeFile(file.absolutePath) } catch (_: Throwable) { null }
    else null
    if (photo != null) {
        val scaled = android.graphics.Bitmap.createScaledBitmap(centerCropSquare(photo), sizePx, sizePx, true)
        paint.shader = android.graphics.BitmapShader(
            scaled,
            android.graphics.Shader.TileMode.CLAMP,
            android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawCircle(r, r, r, paint)
    } else {
        paint.color = getAccountColor(email)
        canvas.drawCircle(r, r, r, paint)
        val letter = getAccountDisplayName(email).firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        val tp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = sizePx * 0.42f
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val baseline = r - (tp.descent() + tp.ascent()) / 2f
        canvas.drawText(letter, r, baseline, tp)
    }
    return bmp
}

internal fun MainActivity.renderAccountHeader() {
    val current = currentAccountEmail ?: savedAccounts.firstOrNull()?.email.orEmpty()
    val dp = resources.displayMetrics.density

    val textInt = if (currentTheme == "light") "#212121".toColorInt() else Color.WHITE
    val secondaryTextInt =
            if (currentTheme == "light") "#5A5A5A".toColorInt() else "#BDBDBD".toColorInt()
    val accentInt = currentAccentColor.toColorInt()
    val logoutRed = "#E53935".toColorInt()

    // Keep the header background in sync with the active theme (was hardcoded dark).
    val headerBg = when (currentTheme) {
        "light"  -> "#F6F6F8".toColorInt()
        "oled"   -> "#000000".toColorInt()
        "violet" -> "#160E24".toColorInt()
        else     -> "#212126".toColorInt()
    }
    (drawerAccountRow.parent as? View)?.setBackgroundColor(headerBg)
    findViewById<View>(R.id.drawerHeaderDivider)?.setBackgroundColor(
        android.graphics.Color.argb(40, android.graphics.Color.red(textInt), android.graphics.Color.green(textInt), android.graphics.Color.blue(textInt))
    )

    // Header: avatar + display name (bold, primary) + email (secondary).
    drawerAccountName.setCompoundDrawablesRelative(null, null, null, null)
    drawerAccountName.text = if (current.isBlank()) "" else getAccountDisplayName(current)
    drawerAccountName.setTextColor(textInt)
    drawerAccountEmail.text = current
    drawerAccountEmail.setTextColor(secondaryTextInt)
    drawerAccountEmail.visibility = if (current.isBlank()) View.GONE else View.VISIBLE
    if (current.isNotBlank()) {
        drawerAccountAvatar.setImageBitmap(buildAccountAvatar(current, (44 * dp).toInt()))
        drawerAccountAvatar.visibility = View.VISIBLE
    } else {
        drawerAccountAvatar.visibility = View.GONE
    }
    drawerAccountArrow.imageTintList = ColorStateList.valueOf(accentInt)

    drawerAccountsList.removeAllViews()

    // Per-account row background: a darkened shade of the active theme so the rows
    // (with pencil/exit icons) match the theme instead of a generic dark grey
    // that clashes under e.g. the iris/violet theme.
    val rowBg = when (currentTheme) {
        "light"  -> "#F0F0F0".toColorInt()
        "oled"   -> "#181818".toColorInt()
        "violet" -> "#0F0918".toColorInt()
        else     -> "#2A2A2A".toColorInt()
    }

    // All accounts, including the currently logged-in one (it shows a red sign-out icon).
    savedAccounts.forEach { account ->
        val isCurrent = account.email.equals(current, ignoreCase = true)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * dp).toInt() }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 10 * dp
                setColor(rowBg)
                if (isCurrent) setStroke((1.5f * dp).toInt(), accentInt)
            }
            setPadding((10 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt())
        }

        // Circular avatar for the account.
        row.addView(ImageView(this).apply {
            val sz = (32 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply { marginEnd = (10 * dp).toInt() }
            setImageBitmap(buildAccountAvatar(account.email, sz))
        })

        // Name (bold) over email (secondary).
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@renderAccountHeader).apply {
                text = getAccountDisplayName(account.email)
                textSize = 14f
                setTextColor(textInt)
                setTypeface(null, android.graphics.Typeface.BOLD)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(TextView(this@renderAccountHeader).apply {
                text = account.email
                textSize = 12f
                setTextColor(secondaryTextInt)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            if (!isCurrent) setOnClickListener {
                showThemedConfirmDialog(
                    title = "Switch Account",
                    message = "Switch to ${account.email}?",
                    confirmLabel = "Switch"
                ) {
                    switchToSavedAccount(account)
                    closeAccountsList()
                    drawerLayout.closeDrawer(GravityCompat.START)
                }
            }
        })

        // Pencil: edit this account's profile (display name + photo).
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_lucide_pencil)
            imageTintList = ColorStateList.valueOf(accentInt)
            setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams((36 * dp).toInt(), (36 * dp).toInt())
            setOnClickListener { showEditProfileDialog(account.email) }
        })

        // Red sign-out / remove icon.
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_lucide_log_out)
            imageTintList = ColorStateList.valueOf(logoutRed)
            setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams((36 * dp).toInt(), (36 * dp).toInt())
            setOnClickListener {
                showThemedConfirmDialog(
                    title = if (isCurrent) "Sign Out" else "Remove Account",
                    message = if (isCurrent) "Sign out of ${account.email}?" else "Remove ${account.email}?",
                    confirmLabel = if (isCurrent) "Sign Out" else "Remove",
                    isDangerous = true
                ) { deleteAccount(account) }
            }
        })

        drawerAccountsList.addView(row)
    }

    // Small text link (not a button) to add another account, tinted with the accent color.
    drawerAccountsList.addView(TextView(this).apply {
        text = getString(R.string.drawer_add_account_action)
        setTextColor(accentInt)
        textSize = 13f
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        background = android.util.TypedValue().let { tv ->
            theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            ContextCompat.getDrawable(this@renderAccountHeader, tv.resourceId)
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (10 * dp).toInt() }
        setPadding((4 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt())
        setOnClickListener {
            showAddAccountDialog()
            closeAccountsList()
            drawerLayout.closeDrawer(GravityCompat.START)
        }
    })
}

/** Crop/rotate editor for the account avatar; shares the editor with contact photos. */
internal fun MainActivity.showAvatarCropDialog(uri: android.net.Uri, email: String) {
    showImageCropDialog(uri) { cropped ->
        try {
            accountAvatarFile(email).outputStream().use { out ->
                cropped.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
            }
            renderAccountHeader()
            editProfileAvatarRefresh?.invoke()
        } catch (e: Throwable) {
            Log.e(MainActivity.TAG, "Avatar save failed", e)
        }
    }
}

/**
 * Discord-style crop/rotate editor. Hands the square 512px result to [onCropped],
 * so both the account avatar and the contact photo use the same UX.
 */
internal fun MainActivity.showImageCropDialog(uri: android.net.Uri, onCropped: (android.graphics.Bitmap) -> Unit) {
    val dp = resources.displayMetrics.density
    val source = try {
        contentResolver.openInputStream(uri)?.use { input ->
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeStream(input, null, opts)
            val maxDim = 1600
            var sample = 1
            while (opts.outWidth / sample > maxDim || opts.outHeight / sample > maxDim) sample *= 2
            opts.inJustDecodeBounds = false
            opts.inSampleSize = sample
            contentResolver.openInputStream(uri)?.use { s2 ->
                android.graphics.BitmapFactory.decodeStream(s2, null, opts)
            }
        }
    } catch (e: Throwable) {
        Log.e(MainActivity.TAG, "Avatar decode failed", e)
        null
    }
    if (source == null) {
        android.widget.Toast.makeText(this, "Could not load image", android.widget.Toast.LENGTH_SHORT).show()
        return
    }

    val dialogBg = getDialogBackgroundColor()
    val accentInt = currentAccentColor.toColorInt()
    val secondaryColor = if (currentTheme == "light") "#757575".toColorInt() else "#9E9E9E".toColorInt()

    val cropView = AvatarCropView(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (280 * dp).toInt()
        )
        setBitmap(source)
    }

    // Center magnet: snap to 0° when the thumb is within this many units of center.
    val snapThreshold = 8
    val rotateSlider = android.widget.SeekBar(this).apply {
        max = 360
        progress = 180
        progressTintList = ColorStateList.valueOf(accentInt)
        thumbTintList = ColorStateList.valueOf(accentInt)
        progressBackgroundTintList = ColorStateList.valueOf(
            if (currentTheme == "light") "#C0C0C4".toColorInt() else "#5A5A5A".toColorInt()
        )
        layoutParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        )
        setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser && kotlin.math.abs(value - 180) <= snapThreshold && value != 180) {
                    progress = 180
                    return
                }
                cropView.rotationDeg = (value - 180).toFloat()
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })
    }

    // Rotate icon (left) + slider with a centered "|" marker showing the image's centre.
    val rotateBar = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (12 * dp).toInt() }
        addView(ImageView(this@showImageCropDialog).apply {
            val sz = (24 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply { marginEnd = (8 * dp).toInt() }
            setImageResource(R.drawable.ic_rotate_cw)
            imageTintList = ColorStateList.valueOf(accentInt)
        })
        addView(android.widget.FrameLayout(this@showImageCropDialog).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            // Centre marker first so it sits UNDER the slider thumb; exact same colour
            // as the slider track background (not lighter), so it reads as part of the bar.
            addView(View(this@showImageCropDialog).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    (2 * dp).toInt(), (14 * dp).toInt(), Gravity.CENTER
                )
                setBackgroundColor(
                    if (currentTheme == "light") "#C0C0C4".toColorInt() else "#5A5A5A".toColorInt()
                )
            })
            addView(rotateSlider)
        })
    }

    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 20 * dp
            setColor(dialogBg)
        }
        setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
        addView(cropView)
        addView(rotateBar)
    }

    val btnRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (8 * dp).toInt() }
    }
    root.addView(btnRow)

    val dialog = AlertDialog.Builder(this).setView(root).create()

    btnRow.addView(TextView(this).apply {
        text = getString(R.string.action_cancel)
        textSize = 14f
        setTextColor(secondaryColor)
        setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
        isClickable = true; isFocusable = true
        setOnClickListener { dialog.dismiss() }
    })
    btnRow.addView(TextView(this).apply {
        text = getString(R.string.action_save)
        textSize = 14f
        setTextColor(accentInt)
        setTypeface(null, android.graphics.Typeface.BOLD)
        setPadding((16 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt())
        isClickable = true; isFocusable = true
        setOnClickListener {
            cropView.getCroppedBitmap(512)?.let(onCropped)
            dialog.dismiss()
        }
    })

    dialog.show()
    dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
    dialog.window?.attributes?.let { lp ->
        lp.width = (resources.displayMetrics.widthPixels * 0.92f).toInt()
        dialog.window?.attributes = lp
    }
}

/** Edit dialog: change the display name and the avatar photo for an account. */
internal fun MainActivity.showEditProfileDialog(email: String) {
    val dp = resources.displayMetrics.density
    val dialogBg = getDialogBackgroundColor()
    val accentInt = currentAccentColor.toColorInt()
    val textColor = if (currentTheme == "light") "#212121".toColorInt() else Color.WHITE
    val hintColor = if (currentTheme == "light") "#9E9E9E".toColorInt() else "#616161".toColorInt()
    val secondaryColor = if (currentTheme == "light") "#757575".toColorInt() else "#9E9E9E".toColorInt()

    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 20 * dp
            setColor(dialogBg)
        }
        setPadding((20 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt(), (16 * dp).toInt())
    }

    val avatarSz = (96 * dp).toInt()
    val avatarView = ImageView(this).apply {
        layoutParams = android.widget.FrameLayout.LayoutParams(avatarSz, avatarSz)
        setImageBitmap(buildAccountAvatar(email, avatarSz))
    }
    // Pencil centered over a slight dark scrim that dims the photo.
    val scrim = View(this).apply {
        layoutParams = android.widget.FrameLayout.LayoutParams(avatarSz, avatarSz)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x55000000)
        }
    }
    val pencil = ImageView(this).apply {
        val sz = (30 * dp).toInt()
        layoutParams = android.widget.FrameLayout.LayoutParams(sz, sz, Gravity.CENTER)
        setImageResource(R.drawable.ic_lucide_pencil)
        imageTintList = ColorStateList.valueOf(Color.WHITE)
    }
    val avatarFrame = android.widget.FrameLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(avatarSz, avatarSz).apply {
            bottomMargin = (18 * dp).toInt()
        }
        isClickable = true
        isFocusable = true
        addView(avatarView)
        addView(scrim)
        addView(pencil)
    }
    root.addView(avatarFrame)

    // "Change name" label on the left + tappable account-color swatch on the right.
    val colorSwatch = View(this).apply {
        val sz = (22 * dp).toInt()
        layoutParams = LinearLayout.LayoutParams(sz, sz)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(getAccountColor(email))
            setStroke((1.5f * dp).toInt(), Color.argb(60, 255, 255, 255))
        }
        isClickable = true
        isFocusable = true
        // The account color only distinguishes rows in the unified inbox, so it is
        // pointless (and confusing) with a single account.
        visibility = if (savedAccounts.size >= 2) View.VISIBLE else View.GONE
    }
    root.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (6 * dp).toInt() }
        addView(TextView(this@showEditProfileDialog).apply {
            text = getString(R.string.drawer_change_name)
            textSize = 13f
            setTextColor(accentInt)
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(colorSwatch)
    })
    colorSwatch.setOnClickListener {
        showAccountColorDialog(email) {
            (colorSwatch.background as? GradientDrawable)?.setColor(getAccountColor(email))
            editProfileAvatarRefresh?.invoke()
        }
    }

    val nameInput = EditText(this).apply {
        setText(getAccountDisplayName(email))
        hint = getString(R.string.drawer_display_name_hint)
        textSize = 15f
        setTextColor(textColor)
        setHintTextColor(hintColor)
        backgroundTintList = ColorStateList.valueOf(hintColor)
        isSingleLine = true
        maxLines = 1
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
        filters = arrayOf(android.text.InputFilter.LengthFilter(13), noArabicFilter())
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }
    root.addView(nameInput)

    editProfileAvatarRefresh = { avatarView.setImageBitmap(buildAccountAvatar(email, avatarSz)) }
    avatarFrame.setOnClickListener {
        editingAvatarEmail = email
        pickAvatarLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    val btnRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (12 * dp).toInt() }
    }
    root.addView(btnRow)

    val dialog = AlertDialog.Builder(this).setView(root).create()

    btnRow.addView(TextView(this).apply {
        text = getString(R.string.action_cancel)
        textSize = 14f
        setTextColor(secondaryColor)
        setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
        isClickable = true; isFocusable = true
        setOnClickListener { dialog.dismiss() }
    })
    btnRow.addView(TextView(this).apply {
        text = getString(R.string.action_save)
        textSize = 14f
        setTextColor(accentInt)
        setTypeface(null, android.graphics.Typeface.BOLD)
        setPadding((16 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt())
        isClickable = true; isFocusable = true
        setOnClickListener {
            setAccountDisplayName(email, nameInput.text.toString())
            renderAccountHeader()
            refreshSettingsAccountRow()
            dialog.dismiss()
        }
    })

    dialog.setOnDismissListener {
        editingAvatarEmail = null
        editProfileAvatarRefresh = null
        refreshSettingsAccountRow()
    }
    dialog.show()
    dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
    dialog.window?.attributes?.let { lp ->
        lp.width = (resources.displayMetrics.widthPixels * 0.85f).toInt()
        dialog.window?.attributes = lp
    }
}

private fun MainActivity.deleteAccount(account: AccountEntry) {
    savedAccounts.removeAll { it.email.equals(account.email, ignoreCase = true) }
    saveAccounts()
    if (account.email == currentAccountEmail) {
        val next = savedAccounts.firstOrNull()
        if (next != null) {
            switchToSavedAccount(next)
        } else {
            currentAccountEmail = null
            connectedAccount = null
            closeAccountsList()
            drawerLayout.closeDrawer(GravityCompat.START)
            showLoginScreen()
        }
    } else {
        // If only one account remains and we're in unified inbox, return to that account's inbox
        if (savedAccounts.size <= 1 && selectedFolder == R.id.nav_unified_inbox) {
            selectedFolder = R.id.nav_inbox
            applyFolderFilterAndRefresh()
        }
        renderAccountHeader()
        navigationView.post { rebuildDrawerMenu() }
    }
}

internal fun MainActivity.getAccountColor(email: String): Int {
    val saved = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE).getInt("account_color_$email", Int.MIN_VALUE)
    if (saved != Int.MIN_VALUE) return saved
    val hue = kotlin.math.abs(email.hashCode() % 360).toFloat()
    return Color.HSVToColor(floatArrayOf(hue, 0.65f, 0.85f))
}

internal fun MainActivity.resolveAccountFor(email: DisplayEmail): JMapClient.ConnectedAccount? {
    if (email.accountEmail.isBlank()) return connectedAccount
    val entry = savedAccounts.firstOrNull { it.email.equals(email.accountEmail, ignoreCase = true) }
        ?: return connectedAccount
    return JMapClient.ConnectedAccount(
        email = entry.email,
        password = entry.password,
        sessionUrl = entry.sessionUrl,
        apiUrl = entry.apiUrl,
        accountId = entry.accountId
    )
}

internal fun MainActivity.resolveAccountForId(emailId: String): JMapClient.ConnectedAccount? {
    val email = baseEmails.find { it.id == emailId } ?: return connectedAccount
    return resolveAccountFor(email)
}

private fun MainActivity.setAccountColor(email: String, color: Int) {
    getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE).edit().putInt("account_color_$email", color).apply()
}

internal fun MainActivity.switchToSavedAccount(account: AccountEntry, forceInbox: Boolean = false) {
    connectedAccount = JMapClient.ConnectedAccount(
        email = account.email,
        password = account.password,
        sessionUrl = account.sessionUrl,
        apiUrl = account.apiUrl,
        accountId = account.accountId
    )
    currentAccountEmail = account.email
    loadLabels()
    loadSubfolderOrder()
    loadFolderMeta()
    saveAccounts()
    renderAccountHeader()

    // Show UI immediately, load cache async, then start live sync
    showMailboxScreen(skipRefresh = true)
    status.text = getString(R.string.status_fetch_new, debugTs())
    if (JmapEventSourceService.isEnabled(this)) {
        JmapEventSourceService.stop(this)
        JmapEventSourceService.start(this)
    }

    lifecycleScope.launch {
        val restoredFolder =
                if (forceInbox) R.id.nav_inbox
                else getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
                        .getInt(MainActivity.KEY_LAST_SELECTED_FOLDER, R.id.nav_inbox)
        selectedFolder = restoredFolder
        // Only the visible folder is restored eagerly; the others load on
        // demand from their Room buckets when the user switches to them.
        val cached = cacheBucket(restoredFolder)?.let { bucket ->
            runCatching {
                com.falseenvironment.jmapjolt.cache.EmailCacheStore.load(this@switchToSavedAccount, bucket)
            }.getOrDefault(emptyList())
        } ?: emptyList()
        if (cached.isNotEmpty()) {
            folderCache[restoredFolder] = cached
            // Straight off disk — nothing to write back.
            folderCache.markClean(restoredFolder)
            updateEmailsList(cached)
            updateTopBarState()
            rebuildDrawerMenu()
        }
        startPeriodicSync()
        fetchAllFoldersBackground()
    }
}

internal fun MainActivity.restoreLastAccountSession(): Boolean {
    val target =
            currentAccountEmail?.let { selected ->
                savedAccounts.firstOrNull { it.email == selected }
            }
                    ?: savedAccounts.firstOrNull()
    if (target == null) return false
    switchToSavedAccount(target, forceInbox = true)
    triggerSyncOnAppUpdateIfNeeded()
    return true
}

private fun MainActivity.triggerSyncOnAppUpdateIfNeeded() {
    val prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
    val currentVersion =
            try {
                val pi = packageManager.getPackageInfo(packageName, 0)
                androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(pi)
            } catch (_: PackageManager.NameNotFoundException) {
                -1L
            }
    val lastVersion = prefs.getLong(MainActivity.KEY_LAST_SYNC_APP_VERSION, -1L)

    if (currentVersion > 0 && currentVersion != lastVersion) {
        refreshInboxNow()
        prefs.edit().putLong(MainActivity.KEY_LAST_SYNC_APP_VERSION, currentVersion).apply()
    }
}
