package com.falseenvironment.jmapjolt

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.graphics.toColorInt
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable

internal fun MainActivity.showOnboarding(page: Int = 0) {
    onboardingContainer.visibility = View.VISIBLE
    loginContainer.visibility = View.GONE
    loginBackBtn.visibility = View.GONE
    mailboxContainer.visibility = View.GONE
    settingsContainer.visibility = View.GONE
    emailDetailContainer.visibility = View.GONE
    fabCompose.visibility = View.GONE
    customTopBar.visibility = View.GONE
    drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
    if (page != onboardingPager.currentItem) onboardingPager.setCurrentItem(page, false)
}

internal fun MainActivity.setupOnboardingPager() {
    onboardingPermRefresh = null  // reset on each setup
    val dp = resources.displayMetrics.density
    val accentInt = currentAccentColor.toColorInt()
    val bgColor = when (currentTheme) {
        "light" -> Color.parseColor("#F5F5F5")
        "oled" -> Color.BLACK
        else -> Color.parseColor("#1F1F1F")
    }
    val textColor = if (currentTheme == "light") Color.parseColor("#212121") else Color.WHITE
    val subColor = if (currentTheme == "light") Color.parseColor("#757575") else Color.parseColor("#BDBDBD")

    val pageViews = listOf(
        buildOnboardingWelcomePage(dp, bgColor, textColor, subColor),
        buildOnboardingFeaturesPage(dp, bgColor, textColor, subColor, accentInt),
        buildOnboardingGetStartedPage(dp, bgColor, textColor, subColor)
    )

    onboardingDots.removeAllViews()
    val dotSize = (8 * dp).toInt()
    val dotMargin = (5 * dp).toInt()
    val dotViews = pageViews.indices.map { i ->
        View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).also {
                it.setMargins(dotMargin, 0, dotMargin, 0)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (i == 0) accentInt else Color.parseColor("#555555"))
            }
            onboardingDots.addView(this)
        }
    }

    fun updateDots(pos: Int) {
        val accent = currentAccentColor.toColorInt()
        dotViews.forEachIndexed { i, dot ->
            (dot.background as GradientDrawable).setColor(
                if (i == pos) accent else Color.parseColor("#555555")
            )
        }
    }

    onboardingPager.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = pageViews.size
        override fun getItemViewType(position: Int) = position
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            pageViews[viewType].layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            return object : RecyclerView.ViewHolder(pageViews[viewType]) {}
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
    }

    onboardingPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) { updateDots(position) }
    })

    fun allPermissionsGranted(): Boolean {
        val notif = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        else true
        val battery = (getSystemService(android.content.Context.POWER_SERVICE)
                as android.os.PowerManager).isIgnoringBatteryOptimizations(packageName)
        return notif && battery
    }

    onboardingNextFab.setOnClickListener {
        val cur = onboardingPager.currentItem
        if (cur < pageViews.size - 1) {
            onboardingPager.setCurrentItem(cur + 1, true)
        } else {
            if (allPermissionsGranted()) {
                triggerOnboardingExplosion()
            } else {
                showPermissionSkipWarning { triggerOnboardingExplosion() }
            }
        }
    }
}

internal fun MainActivity.buildOnboardingWelcomePage(dp: Float, bgColor: Int, textColor: Int, subColor: Int): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(bgColor)
        setPadding((32 * dp).toInt(), 0, (32 * dp).toInt(), (80 * dp).toInt())

        addView(ImageView(this@buildOnboardingWelcomePage).apply {
            layoutParams = LinearLayout.LayoutParams((160 * dp).toInt(), (160 * dp).toInt()).also {
                it.bottomMargin = (28 * dp).toInt()
            }
            setImageResource(R.mipmap.ic_launcher_foreground)
            scaleType = ImageView.ScaleType.FIT_CENTER
        })
        addView(TextView(this@buildOnboardingWelcomePage).apply {
            text = getString(R.string.onboarding_welcome_title)
            setTextColor(textColor)
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (12 * dp).toInt() }
        })
        addView(TextView(this@buildOnboardingWelcomePage).apply {
            text = getString(R.string.onboarding_welcome_subtitle)
            setTextColor(subColor)
            textSize = 16f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })
    }

internal fun MainActivity.buildOnboardingFeaturesPage(dp: Float, bgColor: Int, textColor: Int, subColor: Int, accentInt: Int): LinearLayout {
    val page = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(bgColor)
        setPadding((28 * dp).toInt(), (24 * dp).toInt(), (28 * dp).toInt(), 0)
    }
    val scroll = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT
        )
    }
    val inner = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, (80 * dp).toInt())
    }
    inner.addView(TextView(this).apply {
        text = getString(R.string.onboarding_features_title)
        setTextColor(textColor)
        textSize = 26f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = (28 * dp).toInt() }
    })
    data class FRow(val icon: Int, val title: Int, val desc: Int)
    listOf(
        FRow(R.drawable.ic_lucide_inbox, R.string.feature_jmap_title, R.string.feature_jmap_desc),
        FRow(R.drawable.ic_notifications_bell, R.string.feature_push_title, R.string.feature_push_desc),
        FRow(R.drawable.ic_lucide_lock, R.string.feature_security_title, R.string.feature_security_desc),
        FRow(R.drawable.ic_lucide_star, R.string.feature_accounts_title, R.string.feature_accounts_desc),
        FRow(R.drawable.ic_lucide_settings, R.string.feature_custom_title, R.string.feature_custom_desc)
    ).forEach { f ->
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (16 * dp).toInt() }
        }
        row.addView(ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt()).also {
                it.marginEnd = (16 * dp).toInt()
            }
            setImageResource(f.icon)
            imageTintList = ColorStateList.valueOf(accentInt)
            scaleType = ImageView.ScaleType.FIT_CENTER
        })
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply {
            text = getString(f.title)
            setTextColor(textColor)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        })
        texts.addView(TextView(this).apply {
            text = getString(f.desc)
            setTextColor(subColor)
            textSize = 13f
        })
        row.addView(texts)
        inner.addView(row)
    }
    scroll.addView(inner)
    page.addView(scroll)
    return page
}

internal fun MainActivity.buildOnboardingGetStartedPage(
    dp: Float, bgColor: Int, textColor: Int, subColor: Int
): LinearLayout {
    val accentInt = currentAccentColor.toColorInt()
    val isLight = currentTheme == "light"
    val rowBg = if (isLight) "#F0F0F0".toColorInt() else "#252525".toColorInt()

    val page = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(bgColor)
        setPadding((24 * dp).toInt(), (40 * dp).toInt(), (24 * dp).toInt(), (100 * dp).toInt())
    }

    // Title
    page.addView(TextView(this).apply {
        text = "Permissions"
        setTextColor(textColor)
        textSize = 26f
        typeface = Typeface.DEFAULT_BOLD
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = (8 * dp).toInt() }
    })
    page.addView(TextView(this).apply {
        text = "Grant these permissions for the best experience."
        setTextColor(subColor)
        textSize = 14f
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = (32 * dp).toInt() }
    })

    fun permRow(
        iconRes: Int,
        title: String,
        subtitle: String,
        grantedCheck: () -> Boolean,
        onAllow: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 14 * dp
                setColor(rowBg)
            }
            val hPad = (16 * dp).toInt(); val vPad = (14 * dp).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (12 * dp).toInt() }
        }
        // Icon
        row.addView(ImageView(this).apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(accentInt)
            val sz = (28 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz).also { it.marginEnd = (14 * dp).toInt() }
            scaleType = ImageView.ScaleType.FIT_CENTER
        })
        // Text column
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(this).apply {
            text = title; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(textColor)
        })
        textCol.addView(TextView(this).apply {
            text = subtitle; textSize = 12f; setTextColor(subColor)
        })
        row.addView(textCol)

        // Allow / Granted button
        val btn = TextView(this).apply {
            textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            setPadding((14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginStart = (8 * dp).toInt() }
        }

        fun refreshBtn() {
            if (grantedCheck()) {
                btn.text = "✓ Done"
                btn.setTextColor("#757575".toColorInt())
                btn.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 20 * dp
                    setColor(if (isLight) "#E0E0E0".toColorInt() else "#333333".toColorInt())
                }
                btn.setOnClickListener(null)
            } else {
                btn.text = "Allow"
                btn.setTextColor(android.graphics.Color.WHITE)
                btn.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 20 * dp
                    setColor(accentInt)
                }
                btn.setOnClickListener { onAllow(); btn.postDelayed({ refreshBtn() }, 400) }
            }
        }
        refreshBtn()
        row.addView(btn)
        // Register for global refresh (onResume + permission result callback)
        val prev = onboardingPermRefresh
        onboardingPermRefresh = { prev?.invoke(); refreshBtn() }
        return row
    }

    fun sectionLabel(text: String, color: Int) = TextView(this).apply {
        this.text = text
        textSize = 11f
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.08f
        setTextColor(color)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = (8 * dp).toInt() }
    }

    // Required section
    page.addView(sectionLabel("REQUIRED", if (isLight) "#D32F2F".toColorInt() else "#EF5350".toColorInt()))

    page.addView(permRow(
        iconRes = R.drawable.ic_notifications_bell,
        title = "Notifications",
        subtitle = "Get notified of new emails",
        grantedCheck = {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
                androidx.core.content.ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            else true
        },
        onAllow = {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                val prefs = getSharedPreferences(MainActivity.PREFS_NAME, android.content.Context.MODE_PRIVATE)
                val alreadyAsked = prefs.getBoolean("notif_perm_asked", false)
                val canShowDialog = androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
                    this, android.Manifest.permission.POST_NOTIFICATIONS
                )
                if (alreadyAsked && !canShowDialog) {
                    // Permanently denied — the system dialog won't appear, open app settings
                    startActivity(android.content.Intent(
                        android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS
                    ).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
                } else {
                    prefs.edit().putBoolean("notif_perm_asked", true).apply()
                    requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    ))

    page.addView(permRow(
        iconRes = R.drawable.ic_lucide_battery,
        title = "Background activity",
        subtitle = "Sync emails without restrictions",
        grantedCheck = {
            val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        },
        onAllow = {
            startActivity(android.content.Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:$packageName")
            ))
        }
    ))

    // Optional section
    page.addView(sectionLabel("OPTIONAL", subColor).also {
        it.layoutParams = (it.layoutParams as LinearLayout.LayoutParams).also { lp ->
            lp.topMargin = (12 * dp).toInt()
        }
    })

    page.addView(permRow(
        iconRes = R.drawable.ic_lucide_paperclip,
        title = "Storage",
        subtitle = "Attach photos, videos and files to emails",
        grantedCheck = {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
                androidx.core.content.ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.READ_MEDIA_IMAGES
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            else
                androidx.core.content.ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.READ_EXTERNAL_STORAGE
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        },
        onAllow = {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
                requestStoragePermLauncher.launch(arrayOf(
                    android.Manifest.permission.READ_MEDIA_IMAGES,
                    android.Manifest.permission.READ_MEDIA_VIDEO
                ))
            else
                requestStoragePermLauncher.launch(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE))
        }
    ))

    return page
}

internal fun MainActivity.showPermissionSkipWarning(onSkip: () -> Unit) {
    val dp = resources.displayMetrics.density
    val bgColor = getDialogBackgroundColor()
    val textColor = if (currentTheme == "light") "#212121".toColorInt() else android.graphics.Color.WHITE
    val subColor = if (currentTheme == "light") "#757575".toColorInt() else "#BDBDBD".toColorInt()
    val accentInt = currentAccentColor.toColorInt()

    val view = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 20 * dp
            setColor(bgColor)
        }
        setPadding((24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt(), (16 * dp).toInt())
        elevation = 8 * dp
    }
    view.addView(TextView(this).apply {
        text = "Permissions required"
        textSize = 17f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(textColor)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = (10 * dp).toInt() }
    })
    view.addView(TextView(this).apply {
        text = "Required permissions are needed for the app to work correctly. You may miss notifications or experience sync issues."
        textSize = 14f
        setTextColor(subColor)
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

    var dialog: android.app.AlertDialog? = null

    btnRow.addView(TextView(this).apply {
        text = "Cancel"
        textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(subColor)
        setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
        setOnClickListener { dialog?.dismiss() }
    })
    btnRow.addView(TextView(this).apply {
        text = "Skip anyway"
        textSize = 14f; typeface = Typeface.DEFAULT_BOLD
        setTextColor("#F44336".toColorInt())
        setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
        setOnClickListener { dialog?.dismiss(); onSkip() }
    })
    view.addView(btnRow)

    dialog = android.app.AlertDialog.Builder(this)
        .setView(view)
        .create()
    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    dialog.show()
}

internal fun MainActivity.triggerOnboardingExplosion() {
    completeOnboardingToLogin()
}
