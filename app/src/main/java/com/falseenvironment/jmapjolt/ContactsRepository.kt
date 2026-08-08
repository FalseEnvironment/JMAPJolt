package com.falseenvironment.jmapjolt

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single entry point for the address book, merging the two backends the app supports: the JMAP
 * server ([ContactsJmapClient]) and whatever DAVx5 has synced into the system provider
 * ([ContactsProvider]). Each [Contact] carries its own [ContactSource], so saves and deletes route
 * back to the backend the contact came from.
 */
class ContactsRepository(private val context: Context) {

    private val jmap = ContactsJmapClient()

    /** Cached per instance: resolving it costs a session round-trip. */
    private var cachedAccountId: String? = null

    /** Cached per instance too: every save would otherwise re-query the address book list. */
    private var cachedAddressBookId: String? = null

    /** True when the connected JMAP server advertises the contacts capability. */
    suspend fun isJmapAvailable(): Boolean = jmapAccountId() != null

    private suspend fun jmapAccountId(): String? {
        cachedAccountId?.let { return it }
        val account = CalendarAccount.current(context) ?: return null
        return jmap.contactsAccountId(account)?.also { cachedAccountId = it }
    }

    /** All contacts from every available backend, sorted by display name. */
    suspend fun loadAll(): List<Contact> = withContext(Dispatchers.IO) {
        val remote = runCatching { loadJmap() }.getOrDefault(emptyList())
        val local = runCatching { ContactsProvider.contacts(context) }.getOrDefault(emptyList())
        (remote + local).sortedBy { it.displayName.lowercase() }
            .also { ContactAvatars.index(it) }
    }

    /**
     * Loads the address book once per process in the background so the email list can show contact
     * photos (and compose can offer the picker) before the contacts tab has ever been opened.
     */
    suspend fun warmCache() {
        if (ContactsCache.contacts != null) return
        val loaded = runCatching { loadAll() }.getOrNull() ?: return
        ContactsCache.contacts = loaded
    }

    private suspend fun loadJmap(): List<Contact> {
        val account = CalendarAccount.current(context) ?: return emptyList()
        val accountId = jmapAccountId() ?: return emptyList()
        return jmap.fetchContacts(account, accountId)
    }

    /**
     * Persists [contact] to the backend named by its [Contact.source]. Returns the stored contact
     * with its backend id filled in, or null when the backend refused or is unavailable.
     */
    suspend fun save(contact: Contact): Contact? = withContext(Dispatchers.IO) {
        when (contact.source) {
            ContactSource.JMAP -> {
                val account = CalendarAccount.current(context) ?: return@withContext null
                val accountId = jmapAccountId() ?: return@withContext null
                val bookId = cachedAddressBookId
                    ?: jmap.defaultAddressBookId(account, accountId)?.also { cachedAddressBookId = it }
                val id = jmap.pushContact(account, accountId, bookId, contact)
                    ?: return@withContext null
                contact.copy(jmapId = id)
            }
            ContactSource.DAVX5 -> {
                val account = ContactsProvider.defaultWritableAccount(context)
                val id = ContactsProvider.upsert(context, contact, account)
                    ?: return@withContext null
                contact.copy(id = id)
            }
        }
    }

    suspend fun delete(contact: Contact): Boolean = withContext(Dispatchers.IO) {
        when (contact.source) {
            ContactSource.JMAP -> {
                val account = CalendarAccount.current(context) ?: return@withContext false
                val accountId = jmapAccountId() ?: return@withContext false
                val jmapId = contact.jmapId ?: return@withContext false
                jmap.destroyContact(account, accountId, jmapId)
            }
            ContactSource.DAVX5 -> ContactsProvider.delete(context, contact)
        }
    }
}
