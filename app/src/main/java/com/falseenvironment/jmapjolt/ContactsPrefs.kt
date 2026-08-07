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
    private const val KEY_SHOW = "show"

    enum class Provider { DAVX5, JMAP }

    /** Which backends the contacts list is allowed to show. */
    enum class Show { BOTH, JMAP_ONLY, DAVX5_ONLY }

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

    /** Backends the contacts list shows. Default: both, with the in-panel scope chips. */
    fun show(context: Context): Show =
        runCatching { Show.valueOf(prefs(context).getString(KEY_SHOW, null) ?: "") }
            .getOrDefault(Show.BOTH)

    fun setShow(context: Context, show: Show) {
        prefs(context).edit().putString(KEY_SHOW, show.name).apply()
    }

    /**
     * The single source the list is pinned to, or null when both are shown and the user
     * picks the scope with the chips in the panel.
     */
    fun forcedSource(context: Context): ContactSource? = when (show(context)) {
        Show.BOTH -> null
        Show.JMAP_ONLY -> ContactSource.JMAP
        Show.DAVX5_ONLY -> ContactSource.DAVX5
    }
}
