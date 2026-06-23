package com.falseenvironment.jmapjolt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Raw JMAP transport for calendars (urn:ietf:params:jmap:calendars), which the typed
 * rs.ltt.jmap library does not cover. Requests are issued as hand-built method calls over
 * the same authenticated endpoint used for mail. Maps JSCalendar (RFC 8984) to [CalendarEvent].
 */
class CalendarJmapClient {

    // Stalwart rejects a charset parameter on the content type with notRequest; use bare json.
    private val json = "application/json".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // Basic-auth header is attached manually; disable redirects so credentials are never
        // replayed to a host other than the trusted JMAP endpoint on a 30x response.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    // Session discovery hits /.well-known/jmap, which Stalwart answers with a 307 redirect to the
    // real session endpoint. This client follows redirects (OkHttp drops the Authorization header
    // on cross-host hops) so autodiscovery resolves; method calls still use the no-redirect client.
    private val httpFollow = http.newBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    companion object {
        const val CAP_CALENDARS = "urn:ietf:params:jmap:calendars"
        const val CAP_CORE = "urn:ietf:params:jmap:core"
    }

    /** API endpoint advertised by the JMAP session; preferred over the stored mail apiUrl. */
    private var sessionApiUrl: String? = null

    data class RemoteCalendar(val id: String, val name: String, val color: String?)

    /** Resolves the calendar account id from the JMAP session; null if server has no calendars. */
    suspend fun calendarAccountId(account: JMapClient.ConnectedAccount): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url(account.sessionUrl)
                    .header("Authorization", Credentials.basic(account.email, account.password))
                    .get()
                    .build()
                httpFollow.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: return@use null
                    val session = JSONObject(body)
                    sessionApiUrl = session.optString("apiUrl").takeIf { it.isNotBlank() }
                    val primary = session.optJSONObject("primaryAccounts")
                    primary?.optString(CAP_CALENDARS)?.takeIf { it.isNotBlank() }
                        ?: firstAccountWithCalendars(session)
                }
            }.getOrNull()
        }

    private fun firstAccountWithCalendars(session: JSONObject): String? {
        val accounts = session.optJSONObject("accounts") ?: return null
        for (key in accounts.keys()) {
            val caps = accounts.getJSONObject(key).optJSONObject("accountCapabilities")
            if (caps?.has(CAP_CALENDARS) == true) return key
        }
        return null
    }

    private fun post(account: JMapClient.ConnectedAccount, methodCalls: JSONArray): JSONObject {
        val payload = JSONObject().apply {
            put("using", JSONArray(listOf(CAP_CORE, CAP_CALENDARS)))
            put("methodCalls", methodCalls)
        }
        // org.json escapes forward slashes ("Calendar\/get"); Stalwart's parser rejects that as
        // notRequest. Unescape to plain "/" (which never needs escaping in JSON).
        val body = payload.toString().replace("\\/", "/")
        val req = Request.Builder()
            .url(sessionApiUrl ?: account.apiUrl)
            .header("Authorization", Credentials.basic(account.email, account.password))
            .post(body.toRequestBody(json))
            .build()
        http.newCall(req).execute().use { resp ->
            val respBody = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("JMAP ${resp.code}: $respBody")
            return JSONObject(respBody)
        }
    }

    private fun firstResponse(root: JSONObject): JSONObject? =
        root.optJSONArray("methodResponses")?.optJSONArray(0)?.optJSONObject(1)

    suspend fun fetchCalendars(
        account: JMapClient.ConnectedAccount,
        calAccountId: String
    ): List<RemoteCalendar> = withContext(Dispatchers.IO) {
        val calls = JSONArray().put(
            JSONArray(listOf("Calendar/get", JSONObject().put("accountId", calAccountId), "c0"))
        )
        val list = firstResponse(post(account, calls))?.optJSONArray("list") ?: return@withContext emptyList()
        (0 until list.length()).map {
            val c = list.getJSONObject(it)
            RemoteCalendar(
                id = c.getString("id"),
                name = c.optString("name", "Calendar"),
                color = c.optString("color").takeIf { s -> s.isNotBlank() }
            )
        }
    }

    /** Query + fetch events overlapping [from, to). */
    suspend fun fetchEvents(
        account: JMapClient.ConnectedAccount,
        calAccountId: String,
        from: Long,
        to: Long
    ): List<CalendarEvent> = withContext(Dispatchers.IO) {
        // Stalwart interprets CalendarEvent/query after/before as LocalDateTime (no trailing Z),
        // matching the Bulwark webmail client; sending a UTC "Z" value yields no results.
        val filter = JSONObject()
            .put("after", localDateTime(from))
            .put("before", localDateTime(to))
        val queryArgs = JSONObject().put("accountId", calAccountId).put("filter", filter)
        val getArgs = JSONObject()
            .put("accountId", calAccountId)
            .put("#ids", JSONObject()
                .put("resultOf", "q0").put("name", "CalendarEvent/query").put("path", "/ids"))
        val calls = JSONArray()
            .put(JSONArray(listOf("CalendarEvent/query", queryArgs, "q0")))
            .put(JSONArray(listOf("CalendarEvent/get", getArgs, "g0")))
        val responses = post(account, calls).optJSONArray("methodResponses") ?: return@withContext emptyList()
        var events = emptyList<CalendarEvent>()
        for (i in 0 until responses.length()) {
            val entry = responses.getJSONArray(i)
            if (entry.getString(0) == "CalendarEvent/get") {
                val list = entry.getJSONObject(1).optJSONArray("list") ?: JSONArray()
                events = (0 until list.length()).mapNotNull { fromJsCalendar(list.getJSONObject(it), calAccountId) }
            }
        }
        events
    }

    /** Create or update an event server-side. Returns the server id, or null on failure. */
    suspend fun pushEvent(
        account: JMapClient.ConnectedAccount,
        calAccountId: String,
        defaultCalendarId: String,
        event: CalendarEvent
    ): String? = withContext(Dispatchers.IO) {
        val jsEvent = toJsCalendar(event, defaultCalendarId)
        val args = JSONObject().put("accountId", calAccountId)
        val tmpId = event.id
        if (event.jmapId == null) {
            args.put("create", JSONObject().put(tmpId, jsEvent))
        } else {
            args.put("update", JSONObject().put(event.jmapId, jsEvent))
        }
        val calls = JSONArray().put(JSONArray(listOf("CalendarEvent/set", args, "s0")))
        val resp = firstResponse(post(account, calls)) ?: return@withContext null
        if (event.jmapId == null) {
            resp.optJSONObject("created")?.optJSONObject(tmpId)?.optString("id")
                ?.takeIf { it.isNotBlank() }
        } else {
            if (resp.optJSONObject("updated")?.has(event.jmapId) == true) event.jmapId else null
        }
    }

    suspend fun destroyEvent(
        account: JMapClient.ConnectedAccount,
        calAccountId: String,
        jmapId: String
    ): Boolean = withContext(Dispatchers.IO) {
        val args = JSONObject().put("accountId", calAccountId)
            .put("destroy", JSONArray(listOf(jmapId)))
        val calls = JSONArray().put(JSONArray(listOf("CalendarEvent/set", args, "s0")))
        val resp = firstResponse(post(account, calls)) ?: return@withContext false
        resp.optJSONArray("destroyed")?.let { d ->
            (0 until d.length()).any { d.getString(it) == jmapId }
        } ?: false
    }

    // ---- JSCalendar mapping ----------------------------------------------------------------

    private fun toJsCalendar(event: CalendarEvent, defaultCalendarId: String): JSONObject {
        val tz = TimeZone.getDefault().id
        val obj = JSONObject().apply {
            put("@type", "Event")
            put("uid", event.id)
            put("title", event.title)
            if (event.description.isNotBlank()) put("description", event.description)
            put("start", localDateTime(event.start))
            put("duration", isoDuration(event.durationMinutes))
            put("calendarIds", JSONObject().put(event.jmapId?.let { defaultCalendarId } ?: defaultCalendarId, true))
            if (event.allDay) put("showWithoutTime", true) else put("timeZone", tz)
        }
        if (event.location.isNotBlank()) {
            obj.put("locations", JSONObject().put("l1",
                JSONObject().put("@type", "Location").put("name", event.location)))
        }
        event.recurrence?.let { obj.put("recurrenceRules", JSONArray().put(toJsRecurrence(it))) }
        event.reminderMinutes?.let { mins ->
            val alert = JSONObject()
                .put("@type", "Alert")
                .put("trigger", JSONObject()
                    .put("@type", "OffsetTrigger")
                    .put("offset", "-${isoDuration(mins)}")
                    .put("relativeTo", "start"))
            obj.put("alerts", JSONObject().put("a1", alert))
        }
        return obj
    }

    private fun toJsRecurrence(rule: RecurrenceRule): JSONObject = JSONObject().apply {
        put("@type", "RecurrenceRule")
        put("frequency", rule.freq.name.lowercase())
        if (rule.interval > 1) put("interval", rule.interval)
        rule.count?.let { put("count", it) }
        rule.until?.let { put("until", localDateTime(it)) }
        if (rule.byDay.isNotEmpty()) {
            val arr = JSONArray()
            rule.byDay.forEach { dow ->
                arr.put(JSONObject().put("@type", "NDay").put("day", dayName(dow)))
            }
            put("byDay", arr)
        }
    }

    private fun fromJsCalendar(obj: JSONObject, @Suppress("UNUSED_PARAMETER") calAccountId: String): CalendarEvent? {
        val id = obj.optString("uid").takeIf { it.isNotBlank() } ?: obj.optString("id")
        if (id.isBlank()) return null
        val allDay = obj.optBoolean("showWithoutTime", false)
        val tz = if (allDay) TimeZone.getDefault() else
            TimeZone.getTimeZone(obj.optString("timeZone", TimeZone.getDefault().id))
        val start = parseLocalDateTime(obj.optString("start"), tz) ?: return null
        val durationMin = parseIsoDuration(obj.optString("duration", "PT1H"))
        val location = obj.optJSONObject("locations")?.let { locs ->
            locs.keys().asSequence().firstOrNull()?.let { locs.getJSONObject(it).optString("name") }
        }.orEmpty()
        val recurrence = obj.optJSONArray("recurrenceRules")?.optJSONObject(0)
            ?.let { fromJsRecurrence(it, tz) }
        val reminder = obj.optJSONObject("alerts")?.let { alerts ->
            alerts.keys().asSequence().mapNotNull { k ->
                val trig = alerts.getJSONObject(k).optJSONObject("trigger")
                trig?.optString("offset")?.let { off ->
                    parseIsoDuration(off.removePrefix("-").removePrefix("+"))
                }
            }.firstOrNull()
        }
        return CalendarEvent(
            id = id,
            calendarId = "remote",
            title = obj.optString("title", "(no title)"),
            description = obj.optString("description"),
            location = location,
            start = start,
            durationMinutes = durationMin,
            allDay = allDay,
            recurrence = recurrence,
            reminderMinutes = reminder,
            jmapId = obj.optString("id").takeIf { it.isNotBlank() },
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun fromJsRecurrence(obj: JSONObject, tz: TimeZone): RecurrenceRule? {
        val freq = runCatching {
            RecurrenceFreq.valueOf(obj.optString("frequency").uppercase())
        }.getOrNull() ?: return null
        val byDay = obj.optJSONArray("byDay")?.let { arr ->
            (0 until arr.length()).mapNotNull { dayNumber(arr.getJSONObject(it).optString("day")) }
        } ?: emptyList()
        return RecurrenceRule(
            freq = freq,
            interval = obj.optInt("interval", 1).coerceAtLeast(1),
            count = if (obj.has("count")) obj.getInt("count") else null,
            until = obj.optString("until").takeIf { it.isNotBlank() }?.let { parseLocalDateTime(it, tz) },
            byDay = byDay
        )
    }

    // ---- date/duration helpers -------------------------------------------------------------

    private fun localDateTime(epoch: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = epoch }
        return "%04d-%02d-%02dT%02d:%02d:%02d".format(
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND))
    }

    private fun utcDateTime(epoch: Long): String {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = epoch }
        return "%04d-%02d-%02dT%02d:%02d:%02dZ".format(
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND))
    }

    private fun parseLocalDateTime(s: String, tz: TimeZone): Long? = runCatching {
        val t = s.trim()
        val c = Calendar.getInstance(if (t.endsWith("Z")) TimeZone.getTimeZone("UTC") else tz)
        c.clear()
        val date = t.substringBefore('T')
        val time = t.substringAfter('T', "00:00:00").removeSuffix("Z")
        val (y, mo, d) = date.split("-").map { it.toInt() }
        val tp = time.split(":")
        c.set(y, mo - 1, d, tp.getOrElse(0) { "0" }.toInt(),
            tp.getOrElse(1) { "0" }.toInt(), tp.getOrElse(2) { "0" }.substringBefore('.').toIntOrNull() ?: 0)
        c.timeInMillis
    }.getOrNull()

    private fun isoDuration(minutes: Int): String {
        if (minutes <= 0) return "PT0S"
        val days = minutes / 1440
        val hours = (minutes % 1440) / 60
        val mins = minutes % 60
        val sb = StringBuilder("P")
        if (days > 0) sb.append("${days}D")
        if (hours > 0 || mins > 0) {
            sb.append("T")
            if (hours > 0) sb.append("${hours}H")
            if (mins > 0) sb.append("${mins}M")
        }
        return if (sb.length == 1) "PT0S" else sb.toString()
    }

    private fun parseIsoDuration(iso: String): Int {
        val m = Regex("P(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?")
            .matchEntire(iso.trim()) ?: return 60
        val (d, h, mi, _) = m.destructured
        return (d.toIntOrNull() ?: 0) * 1440 + (h.toIntOrNull() ?: 0) * 60 + (mi.toIntOrNull() ?: 0)
    }

    private fun dayName(dow: Int): String = when (dow) {
        Calendar.SUNDAY -> "su"; Calendar.MONDAY -> "mo"; Calendar.TUESDAY -> "tu"
        Calendar.WEDNESDAY -> "we"; Calendar.THURSDAY -> "th"; Calendar.FRIDAY -> "fr"
        else -> "sa"
    }

    private fun dayNumber(token: String): Int? = when (token.lowercase()) {
        "su" -> Calendar.SUNDAY; "mo" -> Calendar.MONDAY; "tu" -> Calendar.TUESDAY
        "we" -> Calendar.WEDNESDAY; "th" -> Calendar.THURSDAY; "fr" -> Calendar.FRIDAY
        "sa" -> Calendar.SATURDAY; else -> null
    }

    @Suppress("unused")
    private fun newId(): String = UUID.randomUUID().toString()
}
