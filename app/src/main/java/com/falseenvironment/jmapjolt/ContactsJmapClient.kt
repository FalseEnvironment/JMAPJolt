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
import java.util.concurrent.TimeUnit

/**
 * Raw JMAP transport for contacts (urn:ietf:params:jmap:contacts), which the typed rs.ltt.jmap
 * library does not cover. Built as the sibling of [CalendarJmapClient]: hand-built method calls
 * over the same authenticated endpoint used for mail, mapping JSContact (RFC 9553) Card objects
 * to [Contact].
 */
class ContactsJmapClient {

    // Stalwart rejects a charset parameter on the content type with notRequest; use bare json.
    private val json = "application/json".toMediaType()
    // Basic-auth header is attached manually; the no-redirect variant of the shared
    // stack keeps credentials from being replayed to another host on a 30x response.
    private val http = AppHttp.noRedirects

    // Session discovery may hit a 307 to the real session endpoint; only that call follows
    // redirects (OkHttp drops the Authorization header on cross-host hops).
    private val httpFollow = AppHttp.client

    companion object {
        const val CAP_CONTACTS = "urn:ietf:params:jmap:contacts"
        const val CAP_CORE = "urn:ietf:params:jmap:core"
        private const val PHOTO_MEDIA_TYPE = "image/jpeg"
    }

    /** API endpoint advertised by the JMAP session; preferred over the stored mail apiUrl. */
    private var sessionApiUrl: String? = null

    data class RemoteAddressBook(val id: String, val name: String, val isDefault: Boolean)

    /** Resolves the contacts account id from the JMAP session; null if the server has none. */
    suspend fun contactsAccountId(account: JMapClient.ConnectedAccount): String? =
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
                    primary?.optString(CAP_CONTACTS)?.takeIf { it.isNotBlank() }
                        ?: firstAccountWithContacts(session)
                }
            }.getOrNull()
        }

    private fun firstAccountWithContacts(session: JSONObject): String? {
        val accounts = session.optJSONObject("accounts") ?: return null
        for (key in accounts.keys()) {
            val caps = accounts.getJSONObject(key).optJSONObject("accountCapabilities")
            if (caps?.has(CAP_CONTACTS) == true) return key
        }
        return null
    }

    private fun post(account: JMapClient.ConnectedAccount, methodCalls: JSONArray): JSONObject {
        val payload = JSONObject().apply {
            put("using", JSONArray(listOf(CAP_CORE, CAP_CONTACTS)))
            put("methodCalls", methodCalls)
        }
        // org.json escapes forward slashes ("ContactCard\/get"); Stalwart's parser rejects that as
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

    suspend fun fetchAddressBooks(
        account: JMapClient.ConnectedAccount,
        contactsAccountId: String
    ): List<RemoteAddressBook> = withContext(Dispatchers.IO) {
        val calls = JSONArray().put(
            JSONArray(listOf("AddressBook/get", JSONObject().put("accountId", contactsAccountId), "a0"))
        )
        val list = firstResponse(post(account, calls))?.optJSONArray("list")
            ?: return@withContext emptyList()
        (0 until list.length()).map {
            val book = list.getJSONObject(it)
            RemoteAddressBook(
                id = book.getString("id"),
                name = book.optString("name", "Contacts"),
                isDefault = book.optBoolean("isDefault", false)
            )
        }
    }

    /** Address book new cards are filed under: the server default, else the first one. */
    suspend fun defaultAddressBookId(
        account: JMapClient.ConnectedAccount,
        contactsAccountId: String
    ): String? {
        val books = fetchAddressBooks(account, contactsAccountId)
        return books.firstOrNull { it.isDefault }?.id ?: books.firstOrNull()?.id
    }

    /** Every card in the account, newest server order preserved. */
    suspend fun fetchContacts(
        account: JMapClient.ConnectedAccount,
        contactsAccountId: String
    ): List<Contact> = withContext(Dispatchers.IO) {
        val queryArgs = JSONObject().put("accountId", contactsAccountId)
        val getArgs = JSONObject()
            .put("accountId", contactsAccountId)
            .put("#ids", JSONObject()
                .put("resultOf", "q0").put("name", "ContactCard/query").put("path", "/ids"))
        val calls = JSONArray()
            .put(JSONArray(listOf("ContactCard/query", queryArgs, "q0")))
            .put(JSONArray(listOf("ContactCard/get", getArgs, "g0")))
        val responses = post(account, calls).optJSONArray("methodResponses")
            ?: return@withContext emptyList()
        var contacts = emptyList<Contact>()
        for (i in 0 until responses.length()) {
            val entry = responses.getJSONArray(i)
            if (entry.getString(0) == "ContactCard/get") {
                val list = entry.getJSONObject(1).optJSONArray("list") ?: JSONArray()
                contacts = (0 until list.length()).mapNotNull { fromJsContact(list.getJSONObject(it)) }
            }
        }
        contacts
    }

    /** Create or update a card server-side. Returns the server id, or null on failure. */
    suspend fun pushContact(
        account: JMapClient.ConnectedAccount,
        contactsAccountId: String,
        addressBookId: String?,
        contact: Contact
    ): String? = withContext(Dispatchers.IO) {
        val card = toJsContact(contact, addressBookId)
        val args = JSONObject().put("accountId", contactsAccountId)
        if (contact.jmapId == null) {
            args.put("create", JSONObject().put(contact.id, card))
        } else {
            args.put("update", JSONObject().put(contact.jmapId, card))
        }
        val calls = JSONArray().put(JSONArray(listOf("ContactCard/set", args, "s0")))
        val resp = firstResponse(post(account, calls)) ?: return@withContext null
        if (contact.jmapId == null) {
            resp.optJSONObject("created")?.optJSONObject(contact.id)?.optString("id")
                ?.takeIf { it.isNotBlank() }
        } else {
            if (resp.optJSONObject("updated")?.has(contact.jmapId) == true) contact.jmapId else null
        }
    }

    suspend fun destroyContact(
        account: JMapClient.ConnectedAccount,
        contactsAccountId: String,
        jmapId: String
    ): Boolean = withContext(Dispatchers.IO) {
        val args = JSONObject().put("accountId", contactsAccountId)
            .put("destroy", JSONArray(listOf(jmapId)))
        val calls = JSONArray().put(JSONArray(listOf("ContactCard/set", args, "s0")))
        val resp = firstResponse(post(account, calls)) ?: return@withContext false
        resp.optJSONArray("destroyed")?.let { d ->
            (0 until d.length()).any { d.getString(it) == jmapId }
        } ?: false
    }

    // ---- JSContact mapping -------------------------------------------------------------------

    private fun toJsContact(contact: Contact, addressBookId: String?): JSONObject {
        val card = JSONObject().apply {
            put("@type", "Card")
            put("version", "1.0")
            put("uid", contact.id)
        }
        if (addressBookId != null) {
            card.put("addressBookIds", JSONObject().put(addressBookId, true))
        }

        val components = JSONArray()
        fun component(kind: String, value: String) {
            if (value.isBlank()) return
            components.put(JSONObject()
                .put("@type", "NameComponent").put("kind", kind).put("value", value))
        }
        // JSContact splits the name into kinded components: "title" is the honorific prefix
        // (Dr., Mr.), "credential" the suffix (PhD), "given2" the middle name.
        component("title", contact.prefix)
        component("given", contact.firstName)
        component("given2", contact.middleName)
        component("surname", contact.lastName)
        // "generation" is the JSContact kind for Jr./Sr.; "credential" is for PhD-style titles, so
        // the suffix field writes generation and reads either back.
        component("generation", contact.suffix)
        if (components.length() > 0) {
            card.put("name", JSONObject()
                .put("@type", "Name")
                .put("components", components)
                .put("full", contact.displayName))
        }

        if (contact.nickname.isNotBlank()) {
            card.put("nicknames", JSONObject().put("n1", JSONObject()
                .put("@type", "Nickname").put("name", contact.nickname)))
        }

        contact.emails.filter { it.address.isNotBlank() }.forEachIndexed { i, email ->
            card.mapEntry("emails", "e${i + 1}", JSONObject()
                .put("@type", "EmailAddress")
                .put("address", email.address)
                .withContexts(email.context))
        }

        contact.phones.filter { it.number.isNotBlank() }.forEachIndexed { i, phone ->
            card.mapEntry("phones", "p${i + 1}", JSONObject()
                .put("@type", "Phone")
                .put("number", phone.number)
                .put("features", JSONObject().put(phone.feature.token, true)))
        }

        contact.organization?.takeIf { !it.isEmpty() }?.let { org ->
            if (org.companyName.isNotBlank() || org.department.isNotBlank()) {
                val entry = JSONObject().put("@type", "Organization")
                if (org.companyName.isNotBlank()) entry.put("name", org.companyName)
                if (org.department.isNotBlank()) {
                    entry.put("units", JSONArray().put(JSONObject()
                        .put("@type", "OrgUnit").put("name", org.department)))
                }
                card.mapEntry("organizations", "o1", entry)
            }
            // JSContact keeps job title and role as separate Title objects distinguished by kind.
            if (org.jobTitle.isNotBlank()) {
                card.mapEntry("titles", "t1", JSONObject()
                    .put("@type", "Title").put("kind", "title").put("name", org.jobTitle))
            }
            if (org.role.isNotBlank()) {
                card.mapEntry("titles", "t2", JSONObject()
                    .put("@type", "Title").put("kind", "role").put("name", org.role))
            }
        }

        contact.addresses.filter { !it.isEmpty() }.forEachIndexed { i, address ->
            val comps = JSONArray()
            fun addressComponent(kind: String, value: String) {
                if (value.isBlank()) return
                comps.put(JSONObject()
                    .put("@type", "AddressComponent").put("kind", kind).put("value", value))
            }
            addressComponent("name", address.street)
            addressComponent("locality", address.locality)
            addressComponent("region", address.region)
            addressComponent("postcode", address.postcode)
            addressComponent("country", address.country)
            card.mapEntry("addresses", "adr${i + 1}", JSONObject()
                .put("@type", "Address")
                .put("components", comps)
                .withContexts(address.context))
        }

        if (contact.categories.isNotEmpty()) {
            // JSContact calls categories "keywords": a set rendered as a map to true.
            val keywords = JSONObject()
            contact.categories.filter { it.isNotBlank() }.forEach { keywords.put(it, true) }
            if (keywords.length() > 0) card.put("keywords", keywords)
        }

        if (contact.notes.isNotBlank()) {
            card.mapEntry("notes", "note1", JSONObject()
                .put("@type", "Note").put("note", contact.notes))
        }

        // JSContact carries the avatar as a Media entry of kind "photo"; a data: URI keeps the
        // bytes inside the card so no separate blob upload is needed.
        contact.photoBase64?.takeIf { it.isNotBlank() }?.let { photo ->
            card.mapEntry("media", "photo1", JSONObject()
                .put("@type", "Media")
                .put("kind", "photo")
                .put("mediaType", PHOTO_MEDIA_TYPE)
                .put("uri", "data:$PHOTO_MEDIA_TYPE;base64,$photo"))
        }
        return card
    }

    private fun fromJsContact(card: JSONObject): Contact? {
        val jmapId = card.optString("id").takeIf { it.isNotBlank() }
        val uid = card.optString("uid").takeIf { it.isNotBlank() } ?: jmapId ?: return null

        val components = card.optJSONObject("name")?.optJSONArray("components")
        fun component(kind: String): String {
            val arr = components ?: return ""
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                if (c.optString("kind") == kind) return c.optString("value")
            }
            return ""
        }

        val emails = card.optJSONObject("emails").entries().mapNotNull { entry ->
            val address = entry.optString("address").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ContactEmail(address, entry.firstContext())
        }
        val phones = card.optJSONObject("phones").entries().mapNotNull { entry ->
            val number = entry.optString("number").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ContactPhone(number, entry.firstPhoneFeature())
        }

        val org = card.optJSONObject("organizations").entries().firstOrNull()
        val titles = card.optJSONObject("titles").entries()
        val organization = ContactOrganization(
            companyName = org?.optString("name").orEmpty(),
            department = org?.optJSONArray("units")?.optJSONObject(0)?.optString("name").orEmpty(),
            jobTitle = titles.firstOrNull { it.optString("kind", "title") == "title" }
                ?.optString("name").orEmpty(),
            role = titles.firstOrNull { it.optString("kind") == "role" }?.optString("name").orEmpty()
        ).takeIf { !it.isEmpty() }

        val addresses = card.optJSONObject("addresses").entries().mapNotNull { entry ->
            val comps = entry.optJSONArray("components")
            fun addressComponent(kind: String): String {
                val arr = comps ?: return ""
                for (i in 0 until arr.length()) {
                    val c = arr.optJSONObject(i) ?: continue
                    if (c.optString("kind") == kind) return c.optString("value")
                }
                return ""
            }
            ContactAddress(
                street = addressComponent("name"),
                locality = addressComponent("locality"),
                region = addressComponent("region"),
                postcode = addressComponent("postcode"),
                country = addressComponent("country"),
                context = entry.firstContext()
            ).takeIf { !it.isEmpty() }
        }

        val categories = card.optJSONObject("keywords")?.let { keywords ->
            keywords.keys().asSequence().filter { keywords.optBoolean(it) }.toList()
        }.orEmpty()

        // Older drafts encoded notes as a plain string; the RFC uses a map of Note objects.
        val notes = card.optJSONObject("notes").entries().firstOrNull()?.optString("note")
            ?: card.optString("notes")

        return Contact(
            id = uid,
            jmapId = jmapId,
            prefix = component("title"),
            firstName = component("given"),
            middleName = component("given2"),
            lastName = component("surname"),
            suffix = component("generation").ifBlank { component("credential") },
            nickname = card.optJSONObject("nicknames").entries().firstOrNull()
                ?.optString("name").orEmpty(),
            emails = emails,
            phones = phones,
            organization = organization,
            addresses = addresses,
            categories = categories,
            notes = notes.orEmpty(),
            // "kind" is optional on some servers, so fall back to any image media carrying
            // inline data: bytes. Blob-backed uris are skipped (nothing to decode inline).
            photoBase64 = card.optJSONObject("media").entries()
                .firstOrNull {
                    it.optString("kind") == "photo" ||
                        it.optString("mediaType").startsWith("image/") ||
                        it.optString("uri").startsWith("data:image")
                }
                ?.optString("uri")
                ?.substringAfter("base64,", "")
                ?.takeIf { it.isNotBlank() },
            source = ContactSource.JMAP
        )
    }

    // ---- JSON helpers ------------------------------------------------------------------------

    /** Adds [value] under [key] inside the map-valued property [property], creating it if absent. */
    private fun JSONObject.mapEntry(property: String, key: String, value: JSONObject) {
        val map = optJSONObject(property) ?: JSONObject().also { put(property, it) }
        map.put(key, value)
    }

    /** JSContact contexts are a set-as-map; "other" carries no context at all. */
    private fun JSONObject.withContexts(context: ContactContext): JSONObject {
        if (context.token.isNotEmpty()) {
            put("contexts", JSONObject().put(context.token, true))
        }
        return this
    }

    private fun JSONObject?.entries(): List<JSONObject> {
        val map = this ?: return emptyList()
        return map.keys().asSequence().mapNotNull { map.optJSONObject(it) }.toList()
    }

    /** First truthy key of a Phone's `features` map; cards without one are treated as mobile. */
    private fun JSONObject.firstPhoneFeature(): ContactPhoneFeature {
        val features = optJSONObject("features") ?: return ContactPhoneFeature.MOBILE
        val token = features.keys().asSequence().firstOrNull { features.optBoolean(it) }
        return ContactPhoneFeature.fromToken(token)
    }

    private fun JSONObject.firstContext(): ContactContext {
        val contexts = optJSONObject("contexts") ?: return ContactContext.OTHER
        val token = contexts.keys().asSequence().firstOrNull { contexts.optBoolean(it) }
        return ContactContext.fromToken(token)
    }
}
