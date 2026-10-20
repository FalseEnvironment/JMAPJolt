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
    loginContainer.setBackgroundColor(tokens.background)
    loginBackBtn.visibility = View.VISIBLE
    loginBackBtn.bringToFront()
    loginBackBtn.setOnClickListener { showOnboarding() }
    mailboxContainer.visibility = View.GONE
    settingsContainer.visibility = View.GONE
    // The status bar is the debug timestamp/sync-state readout, not general login
    // feedback — it must follow the "Debug mode" setting like everywhere else,
    // not force itself on and leak into a fresh install with the setting off.
    val debugModeEnabled = getSharedPreferences(MainActivity.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        .getBoolean("debug_mode", false)
    status.visibility = if (debugModeEnabled) View.VISIBLE else View.GONE
    emailDetailContainer.visibility = View.GONE
    fabCompose.visibility = View.GONE
    customTopBar.visibility = View.GONE
    drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
    emailInput.text?.clear()
    passwordInput.text?.clear()
    serverUrlInput.text?.clear()
    updateFormState()
    refreshBottomNav()
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
    status.text = getString(R.string.status_connecting, debugTs())
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
                            result.resolvedSessionUrl ?: "-",
                            debugTs()
                    )
            pendingMailboxShow = true
            // First-run default (runs once): prefer UnifiedPush when a distributor app
            // (ntfy, Sunup, …) is installed, otherwise JMAP eventsource. Once the
            // decision is recorded, the Settings switches are never overridden.
            applyDefaultNotificationMode()
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
                    if (result.authFailed) {
                        // Credentials were refused: the endpoint list is noise here.
                        getString(R.string.error_auth_app_password)
                    } else {
                        getString(
                                R.string.status_connection_failed_verbose,
                                debugTs(),
                                result.errorMessage ?: getString(R.string.status_connection_failed),
                                attempted
                        )
                    }
            updateFormState()
        }
    }
}
