package com.falseenvironment.jmapjolt

/**
 * vCard 3.0 (RFC 6350-compatible subset) reader/writer for the address book, mirroring what
 * [CalendarIcs] does for events. Only the properties [Contact] actually models are emitted, so a
 * round-trip through this object is lossless for app-created cards and lossy-but-safe for cards
 * exported from other clients.
 */
object ContactsVcf {

    private const val CRLF = "\r\n"

    /** Imported PHOTO payloads above this are dropped so a crafted card cannot OOM the app. */
    private const val MAX_PHOTO_BASE64_CHARS = 5 * 1024 * 1024

    // ---------------------------------------------------------------- export

    fun toVcf(contacts: List<Contact>): String =
        contacts.joinToString("") { card(it) }

    private fun card(contact: Contact): String = buildString {
        append("BEGIN:VCARD").append(CRLF)
        append("VERSION:3.0").append(CRLF)
        append(fold("N:${esc(contact.lastName)};${esc(contact.firstName)};" +
            "${esc(contact.middleName)};${esc(contact.prefix)};${esc(contact.suffix)}"))
        append(fold("FN:${esc(contact.displayName)}"))
        if (contact.nickname.isNotBlank()) append(fold("NICKNAME:${esc(contact.nickname)}"))
        contact.emails.forEach { email ->
            append(fold("EMAIL${typeParam(email.context.token)}:${esc(email.address)}"))
        }
        contact.phones.forEach { phone ->
            append(fold("TEL;TYPE=${phone.feature.token}:${esc(phone.number)}"))
        }
        contact.organization?.takeIf { !it.isEmpty() }?.let { org ->
            append(fold("ORG:${esc(org.companyName)};${esc(org.department)}"))
            if (org.jobTitle.isNotBlank()) append(fold("TITLE:${esc(org.jobTitle)}"))
            if (org.role.isNotBlank()) append(fold("ROLE:${esc(org.role)}"))
        }
        contact.addresses.filterNot { it.isEmpty() }.forEach { addr ->
            val type = typeParam(addr.context.token)
            append(fold("ADR$type:;;${esc(addr.street)};${esc(addr.locality)};" +
                "${esc(addr.region)};${esc(addr.postcode)};${esc(addr.country)}"))
        }
        if (contact.categories.isNotEmpty()) {
            append(fold("CATEGORIES:${contact.categories.joinToString(",") { esc(it) }}"))
        }
        if (contact.notes.isNotBlank()) append(fold("NOTE:${esc(contact.notes)}"))
        contact.photoBase64?.takeIf { it.isNotBlank() }?.let {
            append(fold("PHOTO;ENCODING=b;TYPE=JPEG:$it"))
        }
        append("END:VCARD").append(CRLF)
    }

    private fun typeParam(token: String): String = if (token.isBlank()) "" else ";TYPE=$token"

    private fun esc(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace(",", "\\,")
        .replace(";", "\\;")

    /** vCard lines are folded at 75 octets; continuation lines start with a single space. */
    private fun fold(line: String): String {
        if (line.length <= 75) return line + CRLF
        val out = StringBuilder()
        var index = 0
        while (index < line.length) {
            val end = minOf(index + if (index == 0) 75 else 74, line.length)
            if (index > 0) out.append(' ')
            out.append(line, index, end).append(CRLF)
            index = end
        }
        return out.toString()
    }

    // ---------------------------------------------------------------- import

    /** Parses every VCARD in [text]. Unknown properties are ignored, never fatal. */
    fun parse(text: String, source: ContactSource): List<Contact> {
        val out = mutableListOf<Contact>()
        var current: Contact? = null
        for (line in unfold(text)) {
            val upper = line.uppercase()
            when {
                upper.startsWith("BEGIN:VCARD") -> current = Contact(source = source)
                upper.startsWith("END:VCARD") -> {
                    current?.takeIf { it.hasContent() }?.let { out += it }
                    current = null
                }
                current != null -> current = apply(current, line)
            }
        }
        return out
    }

    private fun Contact.hasContent(): Boolean =
        firstName.isNotBlank() || lastName.isNotBlank() || nickname.isNotBlank() ||
            emails.isNotEmpty() || phones.isNotEmpty() || organization != null

    private fun apply(contact: Contact, line: String): Contact {
        val colon = line.indexOf(':')
        if (colon <= 0) return contact
        val head = line.substring(0, colon)
        val value = line.substring(colon + 1)
        val name = head.substringBefore(';').uppercase()
        val params = head.split(';').drop(1)
        val decoded = decode(value, params)
        return when (name) {
            "N" -> {
                val parts = splitValue(decoded, 5)
                contact.copy(
                    lastName = parts[0], firstName = parts[1], middleName = parts[2],
                    prefix = parts[3], suffix = parts[4])
            }
            "FN" -> if (contact.firstName.isBlank() && contact.lastName.isBlank())
                contact.copy(firstName = decoded) else contact
            "NICKNAME" -> contact.copy(nickname = decoded.substringBefore(','))
            "EMAIL" -> if (decoded.isBlank()) contact else contact.copy(
                emails = contact.emails + ContactEmail(decoded, contextOf(params)))
            "TEL" -> if (decoded.isBlank()) contact else contact.copy(
                phones = contact.phones + ContactPhone(decoded, featureOf(params)))
            "ORG" -> {
                val parts = splitValue(decoded, 2)
                contact.copy(organization = (contact.organization ?: ContactOrganization())
                    .copy(companyName = parts[0], department = parts[1]))
            }
            "TITLE" -> contact.copy(organization =
                (contact.organization ?: ContactOrganization()).copy(jobTitle = decoded))
            "ROLE" -> contact.copy(organization =
                (contact.organization ?: ContactOrganization()).copy(role = decoded))
            "ADR" -> {
                val parts = splitValue(decoded, 7)
                val address = ContactAddress(
                    street = parts[2], locality = parts[3], region = parts[4],
                    postcode = parts[5], country = parts[6], context = contextOf(params))
                if (address.isEmpty()) contact else contact.copy(addresses = contact.addresses + address)
            }
            "CATEGORIES" -> contact.copy(
                categories = decoded.split(',').map { it.trim() }.filter { it.isNotBlank() })
            "NOTE" -> contact.copy(notes = decoded)
            "PHOTO" -> photoOf(value, params)?.let { contact.copy(photoBase64 = it) } ?: contact
            else -> contact
        }
    }

    /** Only inline base64 photos are kept; URI-referenced ones would need a network fetch. */
    private fun photoOf(raw: String, params: List<String>): String? {
        val isBase64 = params.any {
            it.uppercase().let { p -> p == "ENCODING=B" || p == "ENCODING=BASE64" }
        }
        val cleaned = raw.filterNot { it.isWhitespace() }
        if (cleaned.length > MAX_PHOTO_BASE64_CHARS) return null
        return when {
            isBase64 && cleaned.isNotBlank() -> cleaned
            cleaned.startsWith("data:", ignoreCase = true) ->
                cleaned.substringAfter("base64,", "").takeIf { it.isNotBlank() }
            else -> null
        }
    }

    private fun contextOf(params: List<String>): ContactContext {
        val types = typeTokens(params)
        return when {
            types.contains("work") -> ContactContext.WORK
            types.contains("home") || types.contains("private") -> ContactContext.PRIVATE
            else -> ContactContext.OTHER
        }
    }

    private fun featureOf(params: List<String>): ContactPhoneFeature {
        val types = typeTokens(params)
        return when {
            types.contains("fax") -> ContactPhoneFeature.FAX
            types.contains("cell") || types.contains("mobile") -> ContactPhoneFeature.MOBILE
            types.contains("voice") || types.contains("home") || types.contains("work") ->
                ContactPhoneFeature.TELEPHONE
            else -> ContactPhoneFeature.MOBILE
        }
    }

    private fun typeTokens(params: List<String>): Set<String> = params
        .filter { it.startsWith("TYPE=", ignoreCase = true) }
        .flatMap { it.substringAfter('=').split(',') }
        .map { it.trim().lowercase() }
        .toSet()

    private fun decode(value: String, params: List<String>): String {
        val quoted = params.any { it.equals("ENCODING=QUOTED-PRINTABLE", ignoreCase = true) }
        val raw = if (quoted) decodeQuotedPrintable(value) else value
        return unescape(raw)
    }

    private fun decodeQuotedPrintable(value: String): String = runCatching {
        val bytes = mutableListOf<Byte>()
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '=' && i + 2 < value.length) {
                bytes += value.substring(i + 1, i + 3).toInt(16).toByte()
                i += 3
            } else {
                bytes += ch.code.toByte()
                i++
            }
        }
        String(bytes.toByteArray(), Charsets.UTF_8)
    }.getOrDefault(value)

    private fun unescape(value: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '\\' && i + 1 < value.length) {
                when (val next = value[i + 1]) {
                    'n', 'N' -> out.append('\n')
                    else -> out.append(next)
                }
                i += 2
            } else {
                out.append(ch)
                i++
            }
        }
        return out.toString()
    }

    /** Splits on unescaped semicolons, padded to [size] entries. */
    private fun splitValue(value: String, size: Int): List<String> {
        val parts = mutableListOf<String>()
        val buf = StringBuilder()
        var escaped = false
        for (ch in value) {
            when {
                escaped -> { buf.append(ch); escaped = false }
                ch == '\\' -> { buf.append(ch); escaped = true }
                ch == ';' -> { parts += unescape(buf.toString()); buf.clear() }
                else -> buf.append(ch)
            }
        }
        parts += unescape(buf.toString())
        return List(size) { parts.getOrElse(it) { "" }.trim() }
    }

    /** Joins folded continuation lines back into single logical lines. */
    private fun unfold(text: String): List<String> {
        val out = mutableListOf<String>()
        text.split("\n").forEach { rawLine ->
            val line = rawLine.trimEnd('\r')
            if ((line.startsWith(" ") || line.startsWith("\t")) && out.isNotEmpty()) {
                out[out.lastIndex] = out.last() + line.drop(1)
            } else if (line.isNotBlank()) {
                out += line
            }
        }
        return out
    }

    /** Sanity check used by the importer to reject files that are not vCards at all. */
    fun looksLikeVcf(text: String): Boolean = text.contains("BEGIN:VCARD", ignoreCase = true)
}
