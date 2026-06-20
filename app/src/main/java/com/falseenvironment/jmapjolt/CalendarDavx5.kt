package com.falseenvironment.jmapjolt

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Hands CalDAV account setup to DAVx5 (FOSS, at.bitfire.davdroid), exactly as Etar does in
 * its MainListPreferences: open DAVx5's LoginActivity, and if it is not installed fall back to
 * the app store then the F-Droid web page. DAVx5 then syncs collections into the system
 * [CalendarProvider], where they show up in this app automatically.
 */
object CalendarDavx5 {
    private const val PKG = "at.bitfire.davdroid"
    private const val LOGIN_ACTIVITY = "at.bitfire.davdroid.ui.setup.LoginActivity"
    private const val FDROID_URL = "https://f-droid.org/packages/at.bitfire.davdroid/"

    fun launch(context: Context) {
        val login = Intent(Intent.ACTION_MAIN).apply {
            setClassName(PKG, LOGIN_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (tryStart(context, login)) return

        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PKG"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (tryStart(context, market)) return

        val web = Intent(Intent.ACTION_VIEW, Uri.parse(FDROID_URL))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        tryStart(context, web)
    }

    private fun tryStart(context: Context, intent: Intent): Boolean =
        try {
            context.startActivity(intent); true
        } catch (e: ActivityNotFoundException) {
            false
        }
}
