package com.falseenvironment.jmapjolt

// Display-name cleanup for the sender shown in the list, the detail header, the widget
// and notifications.
//
// Relays and some senders append the address to the display name, often quoted and
// with "@" spelled " at " to dodge scrapers: "Acme 'support at acme.example'". The
// address is already shown elsewhere, so only the name is kept.
internal object SenderName {

    private const val OPEN = "['\"‘“(<\\[]"
    private const val CLOSE = "['\"’”)>\\]]"
    private const val INNER = "[^'\"‘’“”()<>\\[\\]]"

    // A trailing quoted or bracketed part that looks like an address: it contains "@"
    // or " at " between two non-space runs ("info at shop.com").
    private val TRAILING_ADDRESS = Regex(
        "\\s*$OPEN$INNER*?(?:@|\\S\\s+at\\s+\\S)$INNER*$CLOSE?\\s*$",
        RegexOption.IGNORE_CASE
    )
    private val WRAPPING_QUOTES = Regex("^['\"‘“](.*)['\"’”]$")

    /** [raw] without an appended address or wrapping quotes; empty when nothing is left. */
    fun clean(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        val withoutAddress = trimmed.replace(TRAILING_ADDRESS, "").trim()
        return WRAPPING_QUOTES.find(withoutAddress)?.groupValues?.get(1)?.trim() ?: withoutAddress
    }
}
