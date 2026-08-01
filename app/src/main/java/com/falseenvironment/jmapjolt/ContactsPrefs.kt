package com.falseenvironment.jmapjolt

import android.content.Context

/**
 * Stores which address book backend the user selected, mirroring [CalendarPrefs]. DAVx5 reads and
 * writes the system [ContactsProvider]; JMAP talks to the server directly via [ContactsJmapClient].
 */
object ContactsPrefs {
    private const val PREFS = "contacts_prefs"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_ENABLED = "enabled"

    enum class Provider { DAVX5, JMAP }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Whether Contacts is shown in the drawer. Default on. */
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** Backend new contacts default to; the editor can still override it per contact. */
    fun provider(context: Context): Provider =
        runCatching { Provider.valueOf(prefs(context).getString(KEY_PROVIDER, null) ?: "") }
            .getOrDefault(Provider.JMAP)

    fun setProvider(context: Context, provider: Provider) {
        prefs(context).edit().putString(KEY_PROVIDER, provider.name).apply()
    }
}
