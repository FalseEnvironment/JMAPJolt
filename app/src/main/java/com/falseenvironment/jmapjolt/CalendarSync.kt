package com.falseenvironment.jmapjolt

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reconciles the local [CalendarStore] with the server over [CalendarJmapClient].
 * Push-then-pull: local creates/updates/deletes go up first, then a date-window pull
 * refreshes the cache. Degrades to local-only when the server has no calendar capability.
 */
object CalendarSync {
    private const val PREFS = "calendar_sync"
    private const val KEY_CAL_ACCOUNT = "cal_account_id"
    private const val KEY_DEFAULT_CAL = "default_calendar_id"
    private const val WINDOW_MS = 190L * 86_400_000L // ~6 months each side

    data class Result(val supported: Boolean, val pulled: Int, val error: String? = null)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True once a successful discovery found calendar support. */
    fun isSupported(context: Context): Boolean =
        prefs(context).getString(KEY_CAL_ACCOUNT, null) != null

    fun defaultCalendarId(context: Context): String? =
        prefs(context).getString(KEY_DEFAULT_CAL, null)

    suspend fun sync(
        context: Context,
        account: JMapClient.ConnectedAccount,
        client: CalendarJmapClient = CalendarJmapClient()
    ): Result = withContext(Dispatchers.IO) {
        runCatching {
            val calAccountId = client.calendarAccountId(account)
                ?: return@withContext Result(supported = false, pulled = 0)

            val calendars = client.fetchCalendars(account, calAccountId)
            val defaultCal = defaultCalendarId(context)
                ?.takeIf { id -> calendars.any { it.id == id } }
                ?: calendars.firstOrNull()?.id
                ?: return@withContext Result(supported = false, pulled = 0,
                    error = "No writable calendar on server")

            prefs(context).edit()
                .putString(KEY_CAL_ACCOUNT, calAccountId)
                .putString(KEY_DEFAULT_CAL, defaultCal)
                .apply()

            // 1. Push local changes.
            for (ev in CalendarStore.all(context)) {
                if (ev.deleted) {
                    val jid = ev.jmapId
                    if (jid == null || client.destroyEvent(account, calAccountId, jid)) {
                        CalendarStore.purge(context, ev.id)
                    }
                } else {
                    val serverId = client.pushEvent(account, calAccountId, defaultCal, ev)
                    if (serverId != null && ev.jmapId == null) {
                        CalendarStore.upsert(context, ev.copy(jmapId = serverId))
                    }
                }
            }

            // 2. Pull a window around now and merge.
            val now = System.currentTimeMillis()
            val remote = client.fetchEvents(account, calAccountId, now - WINDOW_MS, now + WINDOW_MS)
            for (r in remote) {
                val local = CalendarStore.find(context, r.id)
                if (local == null || local.jmapId != null) {
                    CalendarStore.upsert(context, r.copy(
                        reminderMinutes = r.reminderMinutes ?: local?.reminderMinutes
                    ))
                }
            }
            Result(supported = true, pulled = remote.size)
        }.getOrElse { Result(supported = isSupported(context), pulled = 0, error = it.message) }
    }
}
