package com.falseenvironment.jmapjolt

import android.content.Intent
import android.net.Uri
import android.util.Patterns
import android.view.View
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.unifiedpush.android.connector.INSTANCE_DEFAULT
import org.unifiedpush.android.connector.UnifiedPush

internal fun MainActivity.showLoginScreen() {
    // Leaving the account invalidates the root history: folders/tabs from the old
    // session must not be restorable after logging in as someone else.
    rootDestinationHistory.clear()
    onboardingContainer.visibility = View.GONE
    loginContainer.visibility = View.VISIBLE
    loginContainer.setBackgroundColor(tokens.background)
    loginBackBtn.visibility = View.VISIBLE
    loginBackBtn.bringToFront()
    loginBackBtn.setOnClickListener { showOnboarding() }
    mailboxContainer.visibility = View.GONE
    settingsContainer.visibility = View.GONE
    connectionUi.problem = null
    connectionUi.lastProblem = null
    connectionUi.lastNoticeAt = 0L
    connectionUi.manualRefreshPending = false
    connectionUi.log.clear()
    connectionBanner.visibility = View.GONE
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
    // OAuth needs no password: the server's own page collects it together with the
    // TOTP code, which is the whole point of the 2FA flow.
    val canStartOAuth = isEmailValid && hasServerUrl
    oauthLoginButton.isEnabled = canStartOAuth
    oauthLoginButton.alpha = if (canStartOAuth) 1f else 0.5f

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
            finishLogin(result, server)
        } else {
            loadingOverlay.visibility = View.GONE
            // Restore the button first: updateFormState() also rewrites the status line,
            // so doing it after the error would replace it with a neutral idle message.
            updateFormState()
            // Full diagnostics stay in the debug readout (hidden unless Debug mode);
            // the pill below is the short, user-facing message.
            status.text =
                    if (result.authFailed) {
                        getString(R.string.error_auth_app_password)
                    } else {
                        getString(
                                R.string.status_connection_failed_verbose,
                                debugTs(),
                                result.errorMessage
                                        ?: getString(R.string.status_connection_failed_generic),
                                result.attemptedEndpoints.joinToString(" | ")
                        )
                    }
            // The IME would cover a bottom-anchored pill: drop it before showing the
            // failure and keep the fields' focus from bringing it back.
            hideKeyboard()
            showThemedSnackbar(
                    if (result.authFailed) {
                        getString(R.string.error_auth_app_password)
                    } else {
                        getString(
                                R.string.login_error_connection,
                                result.errorMessage
                                        ?: getString(R.string.status_connection_failed_generic)
                        )
                    }
            )
        }
    }
}

/** Shared post-connect setup for the password and OAuth login paths. */
internal fun MainActivity.finishLogin(result: JMapClient.ConnectResult, server: String) {
    val account = result.connectedAccount ?: return
    connectedAccount = account
    currentAccountEmail = account.email
    loadLabels()
    persistConnectedAccount(account, server)
    renderAccountHeader()
    drawerAccountName.text = account.email
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
    refreshInboxNow()
    fetchAllFoldersBackground()
    if (unifiedPushSwitch.isChecked) {
        registerUnifiedPushAuto("")
    }
    if (JmapEventSourceService.isEnabled(this)) {
        JmapEventSourceService.start(this)
    }
}

/**
 * Starts the browser-based OAuth + PKCE sign-in. The TOTP code is entered only on the
 * server's own page; the app never sees the password.
 */
internal fun MainActivity.beginOAuthLogin() {
    val email = emailInput.text.toString().trim()
    val server = serverUrlInput.text.toString().trim()
    hideKeyboard()

    oauthLoginButton.isEnabled = false
    loginButton.isEnabled = false
    status.text = getString(R.string.status_oauth_opening, debugTs())
    loadingOverlay.visibility = View.VISIBLE

    lifecycleScope.launch {
        val url = runCatching {
            withContext(Dispatchers.IO) {
                OAuthClient.beginAuthorization(this@beginOAuthLogin, email, server)
            }
        }.getOrNull()
        if (url == null) {
            loadingOverlay.visibility = View.GONE
            updateFormState()
            showThemedSnackbar(getString(R.string.oauth_error_start))
            return@launch
        }
        val opened = runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.isSuccess
        loadingOverlay.visibility = View.GONE
        if (!opened) {
            updateFormState()
            showThemedSnackbar(getString(R.string.oauth_error_no_browser))
        } else {
            // The browser is now in front. Re-arm both actions so cancelling the
            // sign-in leaves a usable login screen instead of disabled buttons.
            updateFormState()
            status.text = getString(R.string.status_oauth_waiting, debugTs())
        }
    }
}

/**
 * Handles the `com.falseenvironment.jmapjolt:/oauth2redirect` return trip. Returns true
 * when the intent was an OAuth redirect, so onCreate can skip restoring the old session.
 */
internal fun MainActivity.handleOAuthRedirect(intent: Intent?): Boolean {
    val uri = intent?.data ?: return false
    if (!uri.scheme.equals(OAuthClient.REDIRECT_SCHEME, ignoreCase = true)) return false
    if (uri.path != OAuthClient.REDIRECT_PATH) return false
    // Consume it: a rotation or a second delivery must not replay the one-time code.
    intent.data = null

    // A cold start (the browser outlived our process) leaves every screen hidden.
    // Show the login screen first so a failed exchange still lands somewhere usable,
    // prefilled with the account the sign-in was started for.
    if (loginContainer.visibility != View.VISIBLE && mailboxContainer.visibility != View.VISIBLE) {
        showLoginScreen()
        OAuthPendingStore.load(this)?.let { pending ->
            emailInput.setText(pending.email)
            serverUrlInput.setText(pending.serverOrigin)
            updateFormState()
        }
    }

    loadingOverlay.visibility = View.VISIBLE
    status.text = getString(R.string.status_oauth_finishing, debugTs())

    lifecycleScope.launch {
        val completed = runCatching {
            OAuthClient.completeAuthorization(this@handleOAuthRedirect, uri)
        }.getOrNull()
        if (completed == null) {
            loadingOverlay.visibility = View.GONE
            updateFormState()
            showThemedSnackbar(getString(R.string.oauth_error_exchange))
            return@launch
        }

        val result = runCatching {
            JMapClient(this@handleOAuthRedirect).connectOAuth(
                email = completed.email,
                serverInput = completed.serverOrigin,
                accessToken = completed.tokens.accessToken
            )
        }.getOrNull()

        if (result == null || !result.success || result.connectedAccount == null) {
            if (result?.authFailed == true) {
                OAuthTokenStore.clear(this@handleOAuthRedirect, completed.email)
            }
            loadingOverlay.visibility = View.GONE
            updateFormState()
            showThemedSnackbar(
                if (result?.authFailed == true || result == null) {
                    getString(R.string.oauth_error_exchange)
                } else {
                    result.errorMessage ?: getString(R.string.oauth_error_exchange)
                }
            )
            return@launch
        }
        finishLogin(result, completed.serverOrigin)
    }
    return true
}

/**
 * A dead OAuth grant (revoked, password changed, refresh token timed out) or a 401
 * means only a new sign-in helps: clear the tokens and route back to the login
 * screen with the account prefilled. Returns true when the failure was handled.
 */
internal suspend fun MainActivity.handleOAuthReauth(error: Throwable): Boolean {
    val account = connectedAccount ?: return false
    if (account.authType != AuthType.OAUTH) return false
    val grantDead = AuthError.causes(error).any { it is OAuthReauthRequired }
    // A 401 can mean the grant died without the token having looked expired (revoked,
    // password changed, server key rotation). Either way only a new sign-in helps.
    if (!grantDead && !AuthError.isUnauthorized(error)) return false

    OAuthTokenStore.clear(this, account.email)
    syncJob?.cancel()
    connectedAccount = null
    showLoginScreen()
    emailInput.setText(account.email)
    OAuthClient.serverOrigin(account.sessionUrl)?.let { serverUrlInput.setText(it) }
    updateFormState()
    status.text = getString(R.string.oauth_session_expired)
    showThemedSnackbar(getString(R.string.oauth_session_expired))
    return true
}
