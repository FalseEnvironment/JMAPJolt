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
    status.visibility = View.GONE
    drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
    if (page != onboardingPager.currentItem) onboardingPager.setCurrentItem(page, false)
}

internal fun MainActivity.setupOnboardingPager() {
    onboardingPermRefresh = null  // reset on each setup
    val dp = resources.displayMetrics.density
    val accentInt = currentAccentColor.toColorInt()
    val bgColor = when (currentTheme) {
        "light"  -> Color.parseColor("#F6F6F8")
        "oled"   -> Color.BLACK
        "violet" -> Color.parseColor("#160E24")
        else     -> Color.parseColor("#212126")
    }
    val textColor = when (currentTheme) {
        "light" -> Color.parseColor("#1A1A1A")
        else    -> Color.parseColor("#EBEBF0")
    }
    val subColor = when (currentTheme) {
        "light" -> Color.parseColor("#636366")
        else    -> Color.parseColor("#8E8E93")
    }

    val pageViews = listOf(
        buildOnboardingWelcomePage(dp, bgColor, textColor, subColor),
        buildOnboardingFeaturesPage(dp, bgColor, textColor, subColor, accentInt),
        buildOnboardingGetStartedPage(dp, bgColor, textColor, subColor)
    )

    onboardingDots.removeAllViews()
    val dotSize = (8 * dp).toInt()
    val dotPillWidth = (24 * dp).toInt()
    val dotMargin = (5 * dp).toInt()
    val dotViews = pageViews.indices.map { i ->
        View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                if (i == 0) dotPillWidth else dotSize, dotSize
            ).also { it.setMargins(dotMargin, 0, dotMargin, 0) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dotSize / 2f
                setColor(if (i == 0) accentInt else Color.parseColor("#555555"))
            }
            onboardingDots.addView(this)
        }
    }

    var activeDot = 0
    fun updateDots(pos: Int) {
        if (pos == activeDot) return
        val accent = currentAccentColor.toColorInt()
        val inactive = Color.parseColor("#555555")
        dotViews.forEachIndexed { i, dot ->
            val from = dot.layoutParams.width
            val to = if (i == pos) dotPillWidth else dotSize
            if (from != to) {
                // Swoosh: width morphs dot <-> pill, color crossfades in sync
                android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 280
                    interpolator = android.view.animation.DecelerateInterpolator(2f)
                    addUpdateListener { a ->
                        val f = a.animatedValue as Float
                        dot.layoutParams = (dot.layoutParams as LinearLayout.LayoutParams).also {
                            it.width = (from + (to - from) * f).toInt()
                        }
                        (dot.background as GradientDrawable).setColor(
                            androidx.core.graphics.ColorUtils.blendARGB(
                                if (i == pos) inactive else accent,
                                if (i == pos) accent else inactive,
                                f
                            )
                        )
                    }
                    start()
                }
                // Little vertical squash-and-pop on the dot becoming active
                if (i == pos) {
                    dot.scaleY = 0.6f
                    dot.animate().scaleY(1f)
                        .setDuration(320)
                        .setInterpolator(android.view.animation.OvershootInterpolator(2.5f))
                        .start()
                }
            }
        }
        activeDot = pos
    }

    onboardingBottomBar.setBackgroundColor(bgColor)

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
        override fun onPageSelected(position: Int) {
            updateDots(position)
            onboardingBottomBar.setBackgroundColor(bgColor)
        }
    })

    // Staggered parallax that tracks the finger: each child of the page lags
    // progressively behind the swipe and fades, so content "builds up" as the
    // page settles instead of popping in after the scroll ends.
    onboardingPager.setPageTransformer { page, position ->
        val content: ViewGroup =
            ((page as? LinearLayout)?.getChildAt(0) as? ScrollView)
                ?.getChildAt(0) as? LinearLayout ?: page as ViewGroup
        val w = page.width.toFloat()
        val clamped = position.coerceIn(-1f, 1f)
        for (i in 0 until content.childCount) {
            val child = content.getChildAt(i)
            val factor = 0.30f + i * 0.12f
            child.translationX = clamped * w * factor
            child.alpha = 1f - kotlin.math.abs(clamped) * 1.15f
        }
    }

    // One-time entrance for the first page on initial display.
    onboardingPager.post { animateOnboardingPageEntrance(pageViews[0], dp) }

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

/** Staggered fade+slide entrance for the children of an onboarding page. */
internal fun animateOnboardingPageEntrance(page: LinearLayout, dp: Float) {
    // Page 2 wraps its rows in a ScrollView > LinearLayout; animate the inner children.
    val content: ViewGroup =
        (page.getChildAt(0) as? ScrollView)?.getChildAt(0) as? LinearLayout ?: page
    for (i in 0 until content.childCount) {
        val child = content.getChildAt(i)
        child.animate().cancel()
        child.alpha = 0f
        child.translationY = 24 * dp
        child.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(80L + i * 60L)
            .setDuration(320)
            .setInterpolator(android.view.animation.DecelerateInterpolator(2f))
            .start()
    }
}

internal fun MainActivity.buildOnboardingWelcomePage(dp: Float, bgColor: Int, textColor: Int, subColor: Int): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(bgColor)
        setPadding((32 * dp).toInt(), 0, (32 * dp).toInt(), 0)

        addView(OnboardingHeroView(this@buildOnboardingWelcomePage, currentAccentColor.toColorInt(), subColor).apply {
            layoutParams = LinearLayout.LayoutParams((260 * dp).toInt(), (260 * dp).toInt()).also {
                it.bottomMargin = (12 * dp).toInt()
            }
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
        isFillViewport = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT
        )
    }
    val inner = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, 0)
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
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(bgColor)
        setPadding((24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt())
    }

    // Title
    page.addView(TextView(this).apply {
        text = "Permissions"
        setTextColor(textColor)
        textSize = 26f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = (8 * dp).toInt() }
    })
    page.addView(TextView(this).apply {
        text = "Grant these permissions for the best experience."
        setTextColor(subColor)
        textSize = 14f
        gravity = Gravity.CENTER
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
    // Zoom-and-fade the whole onboarding away, then reveal the login screen
    // (whose own entrance animation staggers the fields in).
    onboardingContainer.animate()
        .alpha(0f)
        .scaleX(1.12f)
        .scaleY(1.12f)
        .setDuration(280)
        .setInterpolator(android.view.animation.AccelerateInterpolator(1.4f))
        .withEndAction {
            onboardingContainer.scaleX = 1f
            onboardingContainer.scaleY = 1f
            onboardingContainer.alpha = 1f
            completeOnboardingToLogin()
        }
        .start()
}

/**
 * Staggered entrance for the login screen: the logo pops in with a soft
 * overshoot, then title and input fields slide up one after the other.
 */
internal fun MainActivity.animateLoginEntrance() {
    val dp = resources.displayMetrics.density
    for (i in 0 until loginContainer.childCount) {
        val child = loginContainer.getChildAt(i)
        child.animate().cancel()
        // Blend each view toward the alpha it already has (e.g. the disabled
        // login button sits at 0.5), instead of forcing everything to 1.
        val targetAlpha = child.alpha.takeIf { it > 0f } ?: 1f
        child.alpha = 0f
        child.translationY = 28 * dp
        if (i == 0) {
            // logo: scale-in with overshoot
            child.scaleX = 0.6f
            child.scaleY = 0.6f
            child.animate()
                .alpha(targetAlpha).translationY(0f).scaleX(1f).scaleY(1f)
                .setStartDelay(60)
                .setDuration(420)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.3f))
                .start()
        } else {
            child.animate()
                .alpha(targetAlpha).translationY(0f)
                .setStartDelay(100L + i * 55L)
                .setDuration(330)
                .setInterpolator(android.view.animation.DecelerateInterpolator(2f))
                .start()
        }
    }
}
