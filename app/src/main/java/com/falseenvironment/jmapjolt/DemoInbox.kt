package com.falseenvironment.jmapjolt

import android.content.Context

// Fictional mail, calendar week and address book for README screenshots, available in
// debug builds only.
//
// While enabled, the mailbox, calendar and contacts show this data instead of the
// account's and the server sync is paused, so nothing real ends up in a capture.
// Nothing here is written to the offline cache or sent to the server. Every name,
// company and domain is invented (example.com / *.example).
internal object DemoInbox {

    const val KEY_ENABLED = "demo_inbox"

    // Address shown in the sidebar and Settings instead of the real one. The account's
    // display name and avatar stay as they are.
    const val ACCOUNT_EMAIL = "jmap@jolt.com"

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    private const val DEMO_HOUR = 13

    /**
     * "Now" for the calendar: Wednesday 13:00 of the current week while the demo is on, so
     * the today highlight and the current-time line land mid-week in every capture.
     */
    fun now(context: Context): Long {
        if (!isEnabled(context)) return System.currentTimeMillis()
        return CalendarPrefs.calendar().apply {
            set(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.WEDNESDAY)
            set(java.util.Calendar.HOUR_OF_DAY, DEMO_HOUR)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    fun isEnabled(context: Context): Boolean =
        BuildConfig.DEBUG && context
            .getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private data class Seed(
        val from: String,
        val fromEmail: String,
        val subject: String,
        val preview: String,
        val ageMs: Long,
        val seen: Boolean = true,
        val starred: Boolean = false,
        // Name of an existing account label to show on the row, matched case-insensitively.
        val labelName: String? = null,
    )

    private val inbox = listOf(
        Seed("Northwind Bank", "alerts@northwind.example", "Your monthly statement is ready",
            "Your statement for this month is now available in online banking.", 4 * MINUTE, seen = false),
        Seed("Jane Doe", "jane.doe@example.com", "Weekend hiking trip",
            "Hi! Are we still on for Saturday? I found a nice trail near the lake.", 38 * MINUTE,
            seen = false, starred = true),
        Seed("Acme Cloud", "no-reply@acme.example", "Invoice #2041 paid",
            "Thanks for your payment. Your subscription renews next month.", 2 * HOUR),
        Seed("Parcel Express", "tracking@parcel.example", "Your package is out for delivery",
            "Your order will arrive today between 10:00 and 14:00.", 5 * HOUR, seen = false),
        Seed("Open Source Weekly", "news@osweekly.example", "Issue 128: faster builds, new releases",
            "This week: incremental compilation tips, three new releases and a community spotlight.", 9 * HOUR),
        Seed("John Smith", "john.smith@example.com", "Project notes",
            "Attached the notes from today's call. Let me know if I missed anything.", 1 * DAY,
            starred = true, labelName = "Important"),
        Seed("City Library", "hello@library.example", "A book you reserved is available",
            "Your reserved title is ready for pickup at the main branch until Friday.", 2 * DAY),
        Seed("Example Travel", "trips@travel.example", "Booking confirmed",
            "Your booking is confirmed. Check-in opens 24 hours before departure.", 3 * DAY),
        Seed("Fitness Club", "team@fitclub.example", "New classes this month",
            "Yoga, cycling and a new strength program start next week.", 4 * DAY),
        Seed("Alex Rivera", "alex.rivera@example.com", "Photos from the party",
            "Here are the photos I promised, the sunset ones came out great.", 6 * DAY),
    )

    private val sent = listOf(
        Seed("Jane Doe", "jane.doe@example.com", "Re: Weekend hiking trip",
            "Yes, Saturday works! I'll bring snacks.", 20 * MINUTE),
        Seed("John Smith", "john.smith@example.com", "Re: Project notes",
            "Thanks, looks complete to me.", 1 * DAY),
    )

    /** [email] as shown on screen: the fictional address while the demo is on. */
    fun shownEmail(context: Context, email: String): String =
        if (isEnabled(context)) ACCOUNT_EMAIL else email

    fun isDemoId(id: String): Boolean = id.startsWith(ID_PREFIX)

    private const val ID_PREFIX = "demo-"

    /**
     * Rows for [folderId]; empty for folders the demo does not populate. [accountEmail] is
     * the real signed-in account, so rows resolve its [labels]; the address shown on screen
     * is swapped separately (see [shownEmail]).
     */
    fun emails(
        folderId: Int,
        accountEmail: String,
        labels: List<EmailLabel>,
        now: Long = System.currentTimeMillis(),
    ): List<DisplayEmail> {
        val seeds = when (folderId) {
            R.id.nav_inbox, R.id.nav_unified_inbox -> inbox
            R.id.nav_favourite -> inbox.filter { it.starred }
            R.id.nav_sent -> sent
            else -> emptyList()
        }
        return seeds.mapIndexed { i, s ->
            DisplayEmail(
                id = "$ID_PREFIX$folderId-$i",
                subject = s.subject,
                from = s.from,
                fromEmail = s.fromEmail,
                preview = s.preview,
                fullBody = "<p>${s.preview}</p>",
                seen = s.seen,
                isFavorite = s.starred,
                receivedAt = now - s.ageMs,
                accountEmail = accountEmail,
                labels = s.labelName
                    ?.let { name -> labels.firstOrNull { it.name.equals(name, ignoreCase = true) } }
                    ?.let { listOf(it.keyword) }
                    .orEmpty(),
                threadId = "demo-thread-$folderId-$i",
            )
        }
    }

    // ---- calendar ---------------------------------------------------------------------

    private data class EventSeed(
        val dayOfWeek: Int,
        val hour: Int,
        val minute: Int,
        val durationMinutes: Int,
        val title: String,
        val location: String = "",
    )

    // A repeating fictional week: any visible range shows the same pattern on its weekdays.
    private val week = listOf(
        EventSeed(java.util.Calendar.MONDAY, 9, 0, 60, "Team standup"),
        EventSeed(java.util.Calendar.MONDAY, 13, 0, 60, "Lunch with Jane", "Corner Café"),
        EventSeed(java.util.Calendar.TUESDAY, 10, 0, 90, "Project review", "Room 2"),
        EventSeed(java.util.Calendar.TUESDAY, 18, 0, 60, "Gym"),
        EventSeed(java.util.Calendar.WEDNESDAY, 9, 0, 60, "Team standup"),
        EventSeed(java.util.Calendar.WEDNESDAY, 15, 0, 60, "Dentist"),
        EventSeed(java.util.Calendar.THURSDAY, 11, 0, 60, "Call with John"),
        EventSeed(java.util.Calendar.THURSDAY, 16, 30, 90, "Design workshop", "Studio"),
        EventSeed(java.util.Calendar.FRIDAY, 9, 0, 60, "Team standup"),
        EventSeed(java.util.Calendar.FRIDAY, 14, 0, 60, "Sprint demo"),
        EventSeed(java.util.Calendar.SATURDAY, 9, 0, 240, "Hiking trip", "Lake trail"),
        EventSeed(java.util.Calendar.SUNDAY, 19, 30, 120, "Family dinner"),
    )

    /** Fictional occurrences between [from] and [to], in the calendar's time zone. */
    fun occurrences(from: Long, to: Long): List<EventOccurrence> {
        val day = CalendarPrefs.calendar().apply {
            timeInMillis = from
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        val out = mutableListOf<EventOccurrence>()
        while (day.timeInMillis < to) {
            val dow = day.get(java.util.Calendar.DAY_OF_WEEK)
            for (seed in week.filter { it.dayOfWeek == dow }) {
                val start = (day.clone() as java.util.Calendar).apply {
                    set(java.util.Calendar.HOUR_OF_DAY, seed.hour)
                    set(java.util.Calendar.MINUTE, seed.minute)
                }.timeInMillis
                val event = CalendarEvent(
                    id = "$ID_PREFIX${seed.title}-$start",
                    calendarId = CalendarRepository.LOCAL_CALENDAR_ID,
                    title = seed.title,
                    location = seed.location,
                    start = start,
                    durationMinutes = seed.durationMinutes,
                )
                if (event.end > from && start < to) out += EventOccurrence(event, start, event.end)
            }
            day.add(java.util.Calendar.DAY_OF_MONTH, 1)
        }
        return out.sortedBy { it.start }
    }

    // ---- contacts ---------------------------------------------------------------------

    private const val CONTACT_DOMAIN = "jolt.com"

    /** Fictional address book; every address is on [CONTACT_DOMAIN]. */
    fun contacts(): List<Contact> = listOf(
        demoContact("Alex", "Rivera", "alex.rivera", "+1 555 0101", "Photographer"),
        demoContact("Chris", "Taylor", "chris.taylor", "+1 555 0102", "Designer"),
        demoContact("Emma", "Brown", "emma.brown", "+1 555 0103"),
        demoContact("Jane", "Doe", "jane.doe", "+1 555 0104", "Product manager"),
        demoContact("John", "Smith", "john.smith", "+1 555 0105", "Developer"),
        demoContact("Laura", "Wilson", "laura.wilson", "+1 555 0106"),
        demoContact("Mark", "Johnson", "mark.johnson", "+1 555 0107", "Accountant"),
        demoContact("Nina", "Garcia", "nina.garcia", "+1 555 0108"),
        demoContact("Oliver", "Martin", "oliver.martin", "+1 555 0109", "Teacher"),
        demoContact("Sofia", "Lee", "sofia.lee", "+1 555 0110"),
    ).sortedBy { it.displayName.lowercase() }

    private fun demoContact(
        first: String, last: String, localPart: String, phone: String, jobTitle: String = "",
    ) = Contact(
        id = "$ID_PREFIX$localPart",
        firstName = first,
        lastName = last,
        emails = listOf(ContactEmail("$localPart@$CONTACT_DOMAIN")),
        phones = listOf(ContactPhone(phone)),
        organization = jobTitle.takeIf { it.isNotBlank() }
            ?.let { ContactOrganization(companyName = "Jolt", jobTitle = it) },
    )
}
