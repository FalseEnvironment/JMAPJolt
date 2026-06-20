package com.falseenvironment.jmapjolt

import android.content.Context

/**
 * Stores which calendar backend the user selected. DAVx5 reads/writes the system
 * [CalendarProvider]; JMAP uses [CalendarSync] + the local [CalendarStore].
 */
object CalendarPrefs {
    private const val PREFS = "calendar_prefs"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_ENABLED = "enabled"

    enum class Provider { DAVX5, JMAP }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Whether the calendar feature is enabled (shown in the drawer). Default on. */
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun provider(context: Context): Provider =
        runCatching { Provider.valueOf(prefs(context).getString(KEY_PROVIDER, null) ?: "") }
            .getOrDefault(Provider.JMAP)

    fun setProvider(context: Context, provider: Provider) {
        prefs(context).edit().putString(KEY_PROVIDER, provider.name).apply()
    }
}
