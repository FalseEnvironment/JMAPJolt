package com.falseenvironment.jmapjolt

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds
import androidx.core.content.ContextCompat

/**
 * Reads and writes contacts through the system [ContactsContract] — the DAVx5 model, mirroring
 * what [CalendarProvider] does for events. Address books synced by DAVx5 (or any sync adapter)
 * appear here automatically; we never speak CardDAV ourselves. Categories map to contact groups,
 * which is where DAVx5 puts a vCard's CATEGORIES.
 */
object ContactsProvider {

    /** Marks a [Contact.id] as backed by a system-provider row: "cp:<rawContactId>". */
    private const val ID_PREFIX = "cp:"

    fun isProviderContactId(id: String): Boolean = id.startsWith(ID_PREFIX)
    private fun rowId(contactId: String): Long? = contactId.removePrefix(ID_PREFIX).toLongOrNull()
    private fun contactId(rowId: Long): String = "$ID_PREFIX$rowId"

    fun hasReadPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    fun hasWritePermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /** An account exposed by the system provider (one per DAVx5 collection / local account). */
    data class ProviderAccount(val name: String, val type: String)

    /** Accounts that own at least one raw contact, so the editor can pick a sync target. */
    fun accounts(context: Context): List<ProviderAccount> {
        if (!hasReadPermission(context)) return emptyList()
        val out = linkedSetOf<ProviderAccount>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts.ACCOUNT_NAME,
                    ContactsContract.RawContacts.ACCOUNT_TYPE),
                "${ContactsContract.RawContacts.DELETED}=0", null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    val type = c.getString(1) ?: continue
                    // Same filter as contacts(): never offer a messenger sync adapter as a target.
                    if (!isAddressBookAccount(type)) continue
                    out += ProviderAccount(name, type)
                }
            }
        }
        return out.toList()
    }

    /** Account new contacts are written to, preferring a DAVx5-synced one over a device-local one. */
    fun defaultWritableAccount(context: Context): ProviderAccount? {
        val all = accounts(context)
        return all.firstOrNull { it.type.contains("davdroid", ignoreCase = true) }
            ?: all.firstOrNull { it.type.isNotBlank() }
            ?: all.firstOrNull()
    }

    /**
     * Account types that hold a real address book: DAVx5 collections and the device-local store.
     * Messenger sync adapters (Telegram, WhatsApp, Signal, …) register their own account type and
     * mirror the whole phone book into it, which is why an unfiltered read shows every chat
     * partner twice and with no phone number.
     */
    private val LOCAL_ACCOUNT_TYPES = setOf(
        "com.android.contacts",
        "com.android.localphone",
        "vnd.sec.contact.phone"
    )

    private fun isAddressBookAccount(type: String?): Boolean =
        type.isNullOrBlank() ||
            type.contains("davdroid", ignoreCase = true) ||
            type.contains("davx5", ignoreCase = true) ||
            type.lowercase() in LOCAL_ACCOUNT_TYPES

    /**
     * Raw contact ids worth reading. Null means "no usable filter" (nothing matched), in which
     * case the caller keeps every row rather than presenting an empty address book.
     */
    private fun addressBookRawIds(context: Context): Set<Long>? {
        val ids = linkedSetOf<Long>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID,
                    ContactsContract.RawContacts.ACCOUNT_TYPE),
                "${ContactsContract.RawContacts.DELETED}=0", null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    if (isAddressBookAccount(c.getString(1))) ids += c.getLong(0)
                }
            }
        }
        return ids.takeIf { it.isNotEmpty() }
    }

    /** Every raw contact in a real address book account, mapped onto [Contact]. */
    fun contacts(context: Context): List<Contact> {
        if (!hasReadPermission(context)) return emptyList()
        val allowed = addressBookRawIds(context)
        val byRawId = linkedMapOf<Long, Contact>()
        val cols = arrayOf(
            ContactsContract.Data.RAW_CONTACT_ID,
            ContactsContract.Data.MIMETYPE,
            ContactsContract.Data.DATA1,
            ContactsContract.Data.DATA2,
            ContactsContract.Data.DATA3,
            ContactsContract.Data.DATA4,
            ContactsContract.Data.DATA5,
            ContactsContract.Data.DATA6,
            ContactsContract.Data.DATA7,
            ContactsContract.Data.DATA8,
            ContactsContract.Data.DATA9,
            ContactsContract.Data.DATA10,
            // DATA15 is the Photo blob column; every other mimetype leaves it null.
            ContactsContract.Data.DATA15
        )
        runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI, cols, null, null,
                ContactsContract.Data.RAW_CONTACT_ID
            )?.use { c ->
                while (c.moveToNext()) {
                    val rawId = c.getLong(0)
                    if (allowed != null && rawId !in allowed) continue
                    val current = byRawId[rawId] ?: Contact(
                        id = contactId(rawId), source = ContactSource.DAVX5
                    )
                    byRawId[rawId] = applyDataRow(current, c)
                }
            }
        }
        val groups = groupTitlesByRawContact(context, byRawId.keys)
        return byRawId.map { (rawId, contact) ->
            val categories = groups[rawId].orEmpty()
            if (categories.isEmpty()) contact else contact.copy(categories = categories)
        }
    }

    private fun applyDataRow(contact: Contact, c: android.database.Cursor): Contact =
        when (c.getString(1)) {
            CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> contact.copy(
                // StructuredName packs the parts into DATA2..DATA6.
                firstName = c.getString(3).orEmpty(),
                lastName = c.getString(4).orEmpty(),
                prefix = c.getString(5).orEmpty(),
                middleName = c.getString(6).orEmpty(),
                suffix = c.getString(7).orEmpty()
            )
            CommonDataKinds.Nickname.CONTENT_ITEM_TYPE ->
                contact.copy(nickname = c.getString(2).orEmpty())
            CommonDataKinds.Email.CONTENT_ITEM_TYPE -> {
                val address = c.getString(2).orEmpty()
                if (address.isBlank()) contact
                else contact.copy(emails = contact.emails +
                    ContactEmail(address, emailContext(c.getInt(3))))
            }
            CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> {
                val number = c.getString(2).orEmpty()
                if (number.isBlank()) contact
                else contact.copy(phones = contact.phones +
                    ContactPhone(number, phoneFeature(c.getInt(3))))
            }
            CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> contact.copy(
                organization = ContactOrganization(
                    companyName = c.getString(2).orEmpty(),
                    department = c.getString(6).orEmpty(),
                    // DATA4 is TITLE and DATA6 JOB_DESCRIPTION — the columns upsert() writes to.
                    jobTitle = c.getString(5).orEmpty(),
                    role = c.getString(7).orEmpty()
                ).takeIf { !it.isEmpty() }
            )
            CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE -> {
                val address = ContactAddress(
                    street = c.getString(5).orEmpty(),
                    locality = c.getString(8).orEmpty(),
                    region = c.getString(9).orEmpty(),
                    postcode = c.getString(10).orEmpty(),
                    country = c.getString(11).orEmpty(),
                    context = postalContext(c.getInt(3))
                )
                if (address.isEmpty()) contact
                else contact.copy(addresses = contact.addresses + address)
            }
            CommonDataKinds.Note.CONTENT_ITEM_TYPE ->
                contact.copy(notes = c.getString(2).orEmpty())
            CommonDataKinds.Photo.CONTENT_ITEM_TYPE ->
                ContactAvatars.toBase64(runCatching { c.getBlob(12) }.getOrNull())
                    ?.let { contact.copy(photoBase64 = it) } ?: contact
            else -> contact
        }

    /** Group titles per raw contact id — the provider's rendering of vCard CATEGORIES. */
    private fun groupTitlesByRawContact(
        context: Context,
        rawIds: Set<Long>
    ): Map<Long, List<String>> {
        if (rawIds.isEmpty()) return emptyMap()
        val titles = mutableMapOf<Long, String>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.Groups.CONTENT_URI,
                arrayOf(ContactsContract.Groups._ID, ContactsContract.Groups.TITLE),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val title = c.getString(1) ?: continue
                    titles[c.getLong(0)] = title
                }
            }
        }
        if (titles.isEmpty()) return emptyMap()
        val out = mutableMapOf<Long, MutableList<String>>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.RAW_CONTACT_ID,
                    CommonDataKinds.GroupMembership.GROUP_ROW_ID),
                "${ContactsContract.Data.MIMETYPE}=?",
                arrayOf(CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE), null
            )?.use { c ->
                while (c.moveToNext()) {
                    val rawId = c.getLong(0)
                    if (rawId !in rawIds) continue
                    val title = titles[c.getLong(1)] ?: continue
                    out.getOrPut(rawId) { mutableListOf() }.add(title)
                }
            }
        }
        return out
    }

    /**
     * Insert or update a raw contact. Returns the provider-backed [Contact.id], or null.
     * Updates replace the contact's data rows wholesale, which keeps the write idempotent
     * without diffing every field.
     */
    fun upsert(context: Context, contact: Contact, account: ProviderAccount?): String? {
        if (!hasWritePermission(context)) return null
        val existing = rowId(contact.id)
        return runCatching {
            val ops = arrayListOf<ContentProviderOperation>()
            val rawIndex = 0
            if (existing != null) {
                ops += ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                    .withSelection("${ContactsContract.Data.RAW_CONTACT_ID}=?",
                        arrayOf(existing.toString()))
                    .build()
            } else {
                ops += ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, account?.name)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, account?.type)
                    .build()
            }

            fun dataInsert(): ContentProviderOperation.Builder {
                val builder = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                return if (existing != null) {
                    builder.withValue(ContactsContract.Data.RAW_CONTACT_ID, existing)
                } else {
                    builder.withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawIndex)
                }
            }

            ops += dataInsert()
                .withValue(ContactsContract.Data.MIMETYPE,
                    CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(CommonDataKinds.StructuredName.GIVEN_NAME, contact.firstName)
                .withValue(CommonDataKinds.StructuredName.FAMILY_NAME, contact.lastName)
                .withValue(CommonDataKinds.StructuredName.PREFIX, contact.prefix)
                .withValue(CommonDataKinds.StructuredName.MIDDLE_NAME, contact.middleName)
                .withValue(CommonDataKinds.StructuredName.SUFFIX, contact.suffix)
                .withValue(CommonDataKinds.StructuredName.DISPLAY_NAME, contact.displayName)
                .build()

            if (contact.nickname.isNotBlank()) {
                ops += dataInsert()
                    .withValue(ContactsContract.Data.MIMETYPE,
                        CommonDataKinds.Nickname.CONTENT_ITEM_TYPE)
                    .withValue(CommonDataKinds.Nickname.NAME, contact.nickname)
                    .build()
            }

            contact.emails.filter { it.address.isNotBlank() }.forEach { email ->
                ops += dataInsert()
                    .withValue(ContactsContract.Data.MIMETYPE,
                        CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                    .withValue(CommonDataKinds.Email.ADDRESS, email.address)
                    .withValue(CommonDataKinds.Email.TYPE, emailType(email.context))
                    .build()
            }

            contact.phones.filter { it.number.isNotBlank() }.forEach { phone ->
                ops += dataInsert()
                    .withValue(ContactsContract.Data.MIMETYPE,
                        CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(CommonDataKinds.Phone.NUMBER, phone.number)
                    .withValue(CommonDataKinds.Phone.TYPE, phoneType(phone.feature))
                    .build()
            }

            contact.organization?.takeIf { !it.isEmpty() }?.let { org ->
                ops += dataInsert()
                    .withValue(ContactsContract.Data.MIMETYPE,
                        CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
                    .withValue(CommonDataKinds.Organization.COMPANY, org.companyName)
                    .withValue(CommonDataKinds.Organization.DEPARTMENT, org.department)
                    .withValue(CommonDataKinds.Organization.TITLE, org.jobTitle)
                    .withValue(CommonDataKinds.Organization.JOB_DESCRIPTION, org.role)
                    .build()
            }

            contact.addresses.filter { !it.isEmpty() }.forEach { address ->
                ops += dataInsert()
                    .withValue(ContactsContract.Data.MIMETYPE,
                        CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE)
                    .withValue(CommonDataKinds.StructuredPostal.STREET, address.street)
                    .withValue(CommonDataKinds.StructuredPostal.CITY, address.locality)
                    .withValue(CommonDataKinds.StructuredPostal.REGION, address.region)
                    .withValue(CommonDataKinds.StructuredPostal.POSTCODE, address.postcode)
                    .withValue(CommonDataKinds.StructuredPostal.COUNTRY, address.country)
                    .withValue(CommonDataKinds.StructuredPostal.TYPE, postalType(address.context))
                    .build()
            }

            ContactAvatars.toBytes(contact.photoBase64)?.let { bytes ->
                ops += dataInsert()
                    .withValue(ContactsContract.Data.MIMETYPE,
                        CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
                    .withValue(CommonDataKinds.Photo.PHOTO, bytes)
                    .build()
            }

            if (contact.notes.isNotBlank()) {
                ops += dataInsert()
                    .withValue(ContactsContract.Data.MIMETYPE,
                        CommonDataKinds.Note.CONTENT_ITEM_TYPE)
                    .withValue(CommonDataKinds.Note.NOTE, contact.notes)
                    .build()
            }

            val results = context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            val rawId = existing
                ?: results.firstOrNull()?.uri?.lastPathSegment?.toLongOrNull()
                ?: return null
            applyCategories(context, rawId, account, contact.categories)
            contactId(rawId)
        }.getOrNull()
    }

    fun delete(context: Context, contact: Contact): Boolean {
        if (!hasWritePermission(context)) return false
        val id = rowId(contact.id) ?: return false
        return runCatching {
            context.contentResolver.delete(
                ContactsContract.RawContacts.CONTENT_URI,
                "${ContactsContract.RawContacts._ID}=?",
                arrayOf(id.toString())
            ) > 0
        }.getOrDefault(false)
    }

    /** Rewrites the contact's group memberships so they match [categories] exactly. */
    private fun applyCategories(
        context: Context,
        rawId: Long,
        account: ProviderAccount?,
        categories: List<String>
    ) {
        runCatching {
            context.contentResolver.delete(
                ContactsContract.Data.CONTENT_URI,
                "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                arrayOf(rawId.toString(), CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE)
            )
            categories.filter { it.isNotBlank() }.forEach { title ->
                val groupId = findOrCreateGroup(context, title, account) ?: return@forEach
                context.contentResolver.insert(
                    ContactsContract.Data.CONTENT_URI,
                    ContentValues().apply {
                        put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                        put(ContactsContract.Data.MIMETYPE,
                            CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE)
                        put(CommonDataKinds.GroupMembership.GROUP_ROW_ID, groupId)
                    })
            }
        }
    }

    private fun findOrCreateGroup(
        context: Context,
        title: String,
        account: ProviderAccount?
    ): Long? = runCatching {
        context.contentResolver.query(
            ContactsContract.Groups.CONTENT_URI,
            arrayOf(ContactsContract.Groups._ID),
            "${ContactsContract.Groups.TITLE}=? AND ${ContactsContract.Groups.DELETED}=0",
            arrayOf(title), null
        )?.use { c -> if (c.moveToFirst()) return@runCatching c.getLong(0) }

        val uri = context.contentResolver.insert(
            ContactsContract.Groups.CONTENT_URI,
            ContentValues().apply {
                put(ContactsContract.Groups.TITLE, title)
                put(ContactsContract.Groups.ACCOUNT_NAME, account?.name)
                put(ContactsContract.Groups.ACCOUNT_TYPE, account?.type)
                put(ContactsContract.Groups.GROUP_VISIBLE, 1)
            })
        uri?.lastPathSegment?.toLongOrNull()
    }.getOrNull()

    // ---- context <-> provider type mapping ----------------------------------------------------

    private fun emailType(context: ContactContext): Int = when (context) {
        ContactContext.WORK -> CommonDataKinds.Email.TYPE_WORK
        ContactContext.PRIVATE -> CommonDataKinds.Email.TYPE_HOME
        ContactContext.OTHER -> CommonDataKinds.Email.TYPE_OTHER
    }

    private fun emailContext(type: Int): ContactContext = when (type) {
        CommonDataKinds.Email.TYPE_WORK -> ContactContext.WORK
        CommonDataKinds.Email.TYPE_HOME -> ContactContext.PRIVATE
        else -> ContactContext.OTHER
    }

    /** Phone features map onto the provider TYPEs DAVx5 turns into vCard TEL;TYPE parameters. */
    private fun phoneType(feature: ContactPhoneFeature): Int = when (feature) {
        ContactPhoneFeature.MOBILE -> CommonDataKinds.Phone.TYPE_MOBILE
        ContactPhoneFeature.TELEPHONE -> CommonDataKinds.Phone.TYPE_MAIN
        ContactPhoneFeature.FAX -> CommonDataKinds.Phone.TYPE_FAX_WORK
    }

    private fun phoneFeature(type: Int): ContactPhoneFeature = when (type) {
        CommonDataKinds.Phone.TYPE_MOBILE,
        CommonDataKinds.Phone.TYPE_WORK_MOBILE -> ContactPhoneFeature.MOBILE
        CommonDataKinds.Phone.TYPE_FAX_WORK,
        CommonDataKinds.Phone.TYPE_FAX_HOME,
        CommonDataKinds.Phone.TYPE_OTHER_FAX -> ContactPhoneFeature.FAX
        else -> ContactPhoneFeature.TELEPHONE
    }

    private fun postalType(context: ContactContext): Int = when (context) {
        ContactContext.WORK -> CommonDataKinds.StructuredPostal.TYPE_WORK
        ContactContext.PRIVATE -> CommonDataKinds.StructuredPostal.TYPE_HOME
        ContactContext.OTHER -> CommonDataKinds.StructuredPostal.TYPE_OTHER
    }

    private fun postalContext(type: Int): ContactContext = when (type) {
        CommonDataKinds.StructuredPostal.TYPE_WORK -> ContactContext.WORK
        CommonDataKinds.StructuredPostal.TYPE_HOME -> ContactContext.PRIVATE
        else -> ContactContext.OTHER
    }
}
