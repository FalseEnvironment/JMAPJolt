package com.falseenvironment.jmapjolt

import android.util.Log
import android.util.Patterns
import android.view.View
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.INSTANCE_DEFAULT
import org.unifiedpush.android.connector.UnifiedPush

internal fun MainActivity.showLoginScreen() {
    onboardingContainer.visibility = View.GONE
    loginContainer.visibility = View.VISIBLE
    val loginBg = when (currentTheme) {
        "light"  -> android.graphics.Color.parseColor("#F6F6F8")
        "oled"   -> android.graphics.Color.BLACK
        "violet" -> android.graphics.Color.parseColor("#160E24")
        else     -> android.graphics.Color.parseColor("#212126")
    }
    loginContainer.setBackgroundColor(loginBg)
    loginBackBtn.visibility = View.VISIBLE
    loginBackBtn.bringToFront()
    loginBackBtn.setOnClickListener { showOnboarding() }
    mailboxContainer.visibility = View.GONE
    settingsContainer.visibility = View.GONE
    status.visibility = View.VISIBLE
    emailDetailContainer.visibility = View.GONE
    fabCompose.visibility = View.GONE
    customTopBar.visibility = View.GONE
    drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
    emailInput.text?.clear()
    passwordInput.text?.clear()
    serverUrlInput.text?.clear()
    updateFormState()
    animateLoginEntrance()
}

internal fun MainActivity.updateFormState() {
    val email = emailInput.text.toString().trim()
    val isEmailValid = Patterns.EMAIL_ADDRESS.matcher(email).matches() && email.contains("@")

    val hasPassword = passwordInput.text.toString().isNotBlank()
    val hasServerUrl = serverUrlInput.text.toString().isNotBlank()
    loginButton.isEnabled = isEmailValid && hasPassword && hasServerUrl
    loginButton.alpha = if (loginButton.isEnabled) 1f else 0.5f

    status.text = when {
        email.isBlank() -> getString(R.string.status_idle)
        isEmailValid -> getString(R.string.status_initial)
        else -> getString(R.string.status_invalid_email)
    }
}

internal fun MainActivity.connectAndOpenMailbox() {
    val email = emailInput.text.toString().trim()
    val password = passwordInput.text.toString()
    val server = serverUrlInput.text.toString().trim()
    hideKeyboard()

    loginButton.isEnabled = false
    loginButton.alpha = 0.5f
    status.text = getString(R.string.status_connecting)
    loadingOverlay.visibility = View.VISIBLE

    lifecycleScope.launch {
        val result =
                JMapClient(this@connectAndOpenMailbox)
                        .connect(email = email, password = password, serverInput = server)

        if (result.success && result.connectedAccount != null) {
            connectedAccount = result.connectedAccount
            currentAccountEmail = email
            loadLabels()
            persistConnectedAccount(result.connectedAccount, server)
            renderAccountHeader()
            drawerAccountName.text = email
            status.text =
                    getString(
                            R.string.status_connected_with_endpoint,
                            result.resolvedSessionUrl ?: "-"
                    )
            pendingMailboxShow = true
            refreshInboxNow {
                fetchAllFoldersBackground()
            }
            if (unifiedPushSwitch.isChecked) {
                registerUnifiedPushAuto("")
            }
            if (JmapEventSourceService.isEnabled(this@connectAndOpenMailbox)) {
                JmapEventSourceService.start(this@connectAndOpenMailbox)
            }
        } else {
            loadingOverlay.visibility = View.GONE
            val attempted = result.attemptedEndpoints.joinToString(" | ")
            status.text =
                    getString(
                            R.string.status_connection_failed_verbose,
                            result.errorMessage ?: getString(R.string.status_connection_failed),
                            attempted
                    )
            updateFormState()
        }
    }
}

internal fun MainActivity.autoDetectUnifiedPush() {
    val distributors = UnifiedPush.getDistributors(this, arrayListOf())
    val preferred = distributors.firstOrNull {
        it.contains("ntfy", ignoreCase = true) || it.contains("sunup", ignoreCase = true)
    } ?: distributors.firstOrNull()

    if (preferred != null) {
        try {
            UnifiedPush.saveDistributor(this, preferred)
            UnifiedPush.registerApp(this, INSTANCE_DEFAULT, arrayListOf(), packageName)
            unifiedPushSwitch.isChecked = true
            saveUnifiedPushEnabled(true)
            Log.d("MainActivity", "UnifiedPush auto-registered: $preferred")
        } catch (e: Throwable) {
            Log.e("MainActivity", "UnifiedPush auto-registration failed", e)
            saveUnifiedPushEnabled(false)
        }
    } else {
        saveUnifiedPushEnabled(false)
    }
}
