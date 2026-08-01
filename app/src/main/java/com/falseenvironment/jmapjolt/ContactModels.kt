package com.falseenvironment.jmapjolt

import java.util.UUID

/**
 * Address book entry, shaped after JSContact (RFC 9553) so it round-trips through JMAP for
 * Contacts without a lossy intermediate format, and still maps cleanly onto the system
 * [android.provider.ContactsContract] rows DAVx5 syncs over CardDAV.
 */
data class Contact(
    val id: String = UUID.randomUUID().toString(),
    /** Server-side ContactCard id; null until the card has been pushed. */
    val jmapId: String? = null,
    val prefix: String = "",
    val firstName: String = "",
    val middleName: String = "",
    val lastName: String = "",
    val suffix: String = "",
    val nickname: String = "",
    val emails: List<ContactEmail> = emptyList(),
    val phones: List<ContactPhone> = emptyList(),
    val organization: ContactOrganization? = null,
    val addresses: List<ContactAddress> = emptyList(),
    val categories: List<String> = emptyList(),
    val notes: String = "",
    /**
     * Avatar bytes as base64 (no data-URI prefix). JSContact carries it as a `media` photo with a
     * data: URI, the system provider as a Photo data row, so one encoded form serves both.
     */
    val photoBase64: String? = null,
    /** Where this contact lives, so the list can group and the editor can route saves. */
    val source: ContactSource = ContactSource.JMAP
) {
    /** Name as shown in lists, falling back through nickname, organization, then first email. */
    val displayName: String
        get() = listOf(prefix, firstName, middleName, lastName, suffix)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { nickname }
            .ifBlank { organization?.companyName.orEmpty() }
            .ifBlank { emails.firstOrNull()?.address.orEmpty() }
            .ifBlank { "(no name)" }

    /** Initials for the avatar bubble; single letter when only one name part is known. */
    val initials: String
        get() = listOf(firstName, lastName)
            .filter { it.isNotBlank() }
            .mapNotNull { it.firstOrNull()?.uppercaseChar() }
            .joinToString("")
            .ifBlank { displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?" }
}

/** Backend a contact is stored in. Mirrors [ContactsPrefs.Provider] for a saved contact. */
enum class ContactSource { JMAP, DAVX5 }

/**
 * JSContact contexts. "work" and "private" are the spec's own tokens; the enum keeps the UI
 * honest about which values the server will accept.
 */
enum class ContactContext(val token: String, val label: String) {
    WORK("work", "Work"),
    PRIVATE("private", "Private"),
    OTHER("", "Other");

    companion object {
        fun fromToken(token: String?): ContactContext =
            entries.firstOrNull { it.token.isNotEmpty() && it.token == token } ?: OTHER
    }
}

data class ContactEmail(
    val address: String,
    val context: ContactContext = ContactContext.PRIVATE
)

/**
 * JSContact phone `features` (RFC 9553 §2.3.3). A phone is classified by what the number can do
 * rather than by a private/work context, which is also how vCard's TEL TYPE parameter reads.
 */
enum class ContactPhoneFeature(val token: String, val label: String) {
    MOBILE("mobile", "Mobile"),
    TELEPHONE("voice", "Telephone"),
    FAX("fax", "Fax");

    companion object {
        fun fromToken(token: String?): ContactPhoneFeature =
            entries.firstOrNull { it.token == token } ?: MOBILE
    }
}

data class ContactPhone(
    val number: String,
    val feature: ContactPhoneFeature = ContactPhoneFeature.MOBILE
)

data class ContactOrganization(
    val companyName: String = "",
    val department: String = "",
    /** JSContact `titles` entry of kind "title". */
    val jobTitle: String = "",
    /** JSContact `titles` entry of kind "role". */
    val role: String = ""
) {
    fun isEmpty(): Boolean =
        companyName.isBlank() && department.isBlank() && jobTitle.isBlank() && role.isBlank()
}

data class ContactAddress(
    val street: String = "",
    val locality: String = "",
    val region: String = "",
    val postcode: String = "",
    val country: String = "",
    val context: ContactContext = ContactContext.PRIVATE
) {
    fun isEmpty(): Boolean =
        street.isBlank() && locality.isBlank() && region.isBlank() &&
            postcode.isBlank() && country.isBlank()

    /** Single-line rendering for the contact list and detail header. */
    fun format(): String =
        listOf(street, postcode, locality, region, country)
            .filter { it.isNotBlank() }
            .joinToString(", ")
}

/** Built-in categories offered by the editor; the user may type any other value. */
object ContactCategories {
    val DEFAULTS = listOf("Family", "Friends", "Colleagues")
}
