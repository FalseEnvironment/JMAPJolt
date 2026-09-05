package com.falseenvironment.jmapjolt

import java.net.URI

/**
 * Helpers that keep logs useful without leaking secrets.
 *
 * Push endpoints and JMAP eventSource URLs are capability URLs: anyone holding
 * the full URL can act on the account, so only the host ever reaches logcat.
 * Account addresses are personal data, so only their domain is logged.
 */
object LogRedact {

    private const val UNKNOWN = "<unknown>"

    /** Host of [url], or a placeholder when it cannot be parsed. Never the path or query. */
    fun host(url: String?): String {
        if (url.isNullOrBlank()) return UNKNOWN
        return try {
            URI(url).host ?: UNKNOWN
        } catch (_: Throwable) {
            UNKNOWN
        }
    }

    /** Domain of [email] masked as `***@example.com`, or a placeholder. */
    fun email(email: String?): String {
        val domain = email?.substringAfter('@', "")?.takeIf { it.isNotBlank() } ?: return UNKNOWN
        return "***@$domain"
    }
}
