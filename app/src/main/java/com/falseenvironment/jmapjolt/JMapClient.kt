package com.falseenvironment.jmapjolt

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import rs.ltt.jmap.client.JmapClient
import rs.ltt.jmap.common.entity.capability.MailAccountCapability
import rs.ltt.jmap.common.util.Mapper
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import rs.ltt.jmap.common.entity.Email
import rs.ltt.jmap.common.entity.EmailAddress
import rs.ltt.jmap.common.entity.EmailBodyPart
import rs.ltt.jmap.common.entity.EmailSubmission
import rs.ltt.jmap.common.method.call.email.SetEmailMethodCall
import rs.ltt.jmap.common.method.call.submission.SetEmailSubmissionMethodCall
import rs.ltt.jmap.common.method.call.identity.GetIdentityMethodCall
import rs.ltt.jmap.common.method.response.identity.GetIdentityMethodResponse
import rs.ltt.jmap.common.method.response.email.SetEmailMethodResponse

class JMapClient(@Suppress("UNUSED_PARAMETER") context: Context) {

    data class ConnectedAccount(
        val email: String,
        val password: String,
        val sessionUrl: String,
        val apiUrl: String,
        val accountId: String
    )

    data class EmailSummary(
        val id: String,
        val subject: String,
        val from: String,
        val fromEmail: String,
        val preview: String,
        val seen: Boolean,
        val isStarred: Boolean = false,
        val fullBody: String = "",
        val receivedAt: Long = 0L,
        val toEmail: String = "",
        val attachments: List<EmailAttachmentInfo> = emptyList(),
        val keywords: Set<String> = emptySet()
    )

    data class ConnectResult(
        val success: Boolean,
        val resolvedSessionUrl: String? = null,
        val connectedAccount: ConnectedAccount? = null,
        val errorMessage: String? = null,
        val attemptedEndpoints: List<String> = emptyList()
    )

    suspend fun connect(email: String, password: String, serverInput: String): ConnectResult {
        return withContext(Dispatchers.IO) {
            val normalizedCandidates = buildSessionCandidates(serverInput)
            if (normalizedCandidates.isEmpty()) {
                return@withContext ConnectResult(
                    success = false,
                    errorMessage = "Invalid server URL"
                )
            }

            var lastError: String? = null

            for (sessionUrl in normalizedCandidates) {
                val httpUrl = sessionUrl.toHttpUrlOrNull() ?: continue
                try {
                    JmapClient(email, password, httpUrl).use { client ->
                        client.setSessionCache(sessionCache)
                        val session = client.getSession().get(12, TimeUnit.SECONDS)
                        val apiUrl = session.getApiUrl().toString()
                        val accountId = session.getPrimaryAccount(MailAccountCapability::class.java)
                            ?: throw IllegalStateException("No mail account found on JMAP session")

                        return@withContext ConnectResult(
                            success = true,
                            resolvedSessionUrl = sessionUrl,
                            connectedAccount = ConnectedAccount(
                                email = email,
                                password = password,
                                sessionUrl = sessionUrl,
                                apiUrl = apiUrl,
                                accountId = accountId
                            ),
                            attemptedEndpoints = normalizedCandidates
                        )
                    }
                } catch (error: Throwable) {
                    lastError = error.message
                }
            }

            ConnectResult(
                success = false,
                errorMessage = lastError ?: "Unable to connect to JMAP session endpoint",
                attemptedEndpoints = normalizedCandidates
            )
        }
    }

    suspend fun fetchEmails(
        connectedAccount: ConnectedAccount,
        mailboxId: String? = null
    ): List<EmailSummary> = withContext(Dispatchers.IO) {
        val client = newClient(connectedAccount)
        client.use { jmapClient ->
            val session = jmapClient.getSession().get(12, TimeUnit.SECONDS)
            val accountId = session.getPrimaryAccount(MailAccountCapability::class.java)
                ?: throw IllegalStateException("No mail account found")

            if (!mailboxId.isNullOrBlank()) {
                return@withContext fetchEmailsForMailbox(jmapClient, accountId, mailboxId)
            }

            val inboxIds = queryMailboxIds(jmapClient, accountId, JSONObject().put("role", "inbox"))
            if (inboxIds.isEmpty()) {
                throw IllegalStateException("Unable to resolve inbox mailbox id")
            }

            for (candidate in inboxIds) {
                val result = fetchEmailsForMailbox(jmapClient, accountId, candidate)
                if (result.isNotEmpty()) return@withContext result
            }

            emptyList()
        }
    }

    suspend fun fetchEmailsById(
        connectedAccount: ConnectedAccount,
        ids: List<String>
    ): List<EmailSummary> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        val client = newClient(connectedAccount)
        client.use { jmapClient ->
            val session = jmapClient.getSession().get(12, TimeUnit.SECONDS)
            val accountId = session.getPrimaryAccount(MailAccountCapability::class.java)
                ?: return@withContext emptyList()
            val getCall = rs.ltt.jmap.common.method.call.email.GetEmailMethodCall.builder()
                .accountId(accountId)
                .ids(ids.toTypedArray())
                .properties(arrayOf("id", "threadId", "subject", "from", "to", "preview", "keywords", "receivedAt", "htmlBody", "textBody", "bodyValues", "attachments"))
                .fetchHTMLBodyValues(true)
                .fetchTextBodyValues(true)
                .build()
            val getResponse = jmapClient.call(getCall).get()
                .getMain(rs.ltt.jmap.common.method.response.email.GetEmailMethodResponse::class.java)
            getResponse.list.map { email ->
                val fromEmail = email.from?.firstOrNull()?.email ?: ""
                val fromName = email.from?.firstOrNull()?.name ?: ""
                val isSeen = email.keywords?.containsKey("\$seen") == true
                val isStarred = email.keywords?.containsKey("\$flagged") == true
                val body = email.htmlBody?.firstOrNull()?.let { part ->
                    email.bodyValues?.get(part.partId)?.value
                } ?: email.textBody?.firstOrNull()?.let { part ->
                    email.bodyValues?.get(part.partId)?.value
                } ?: email.preview ?: ""
                val atts = email.attachments?.mapNotNull { part ->
                    val blobId = part.blobId ?: return@mapNotNull null
                    EmailAttachmentInfo(blobId = blobId, name = part.name ?: "attachment",
                        mimeType = part.type ?: "application/octet-stream", size = part.size ?: 0L)
                } ?: emptyList()
                EmailSummary(id = email.id, subject = email.subject ?: "(No Subject)",
                    from = if (fromName.isNotBlank()) fromName else fromEmail,
                    fromEmail = fromEmail, preview = email.preview ?: "",
                    seen = isSeen, isStarred = isStarred, fullBody = body,
                    receivedAt = email.receivedAt?.toEpochMilli() ?: 0L,
                    toEmail = email.to?.firstOrNull()?.email ?: "", attachments = atts,
                    keywords = customKeywords(email.keywords))
            }
        }
    }

    suspend fun fetchStarredEmails(
        connectedAccount: ConnectedAccount
    ): List<EmailSummary> = withContext(Dispatchers.IO) {
        val client = newClient(connectedAccount)
        client.use { jmapClient ->
            val session = jmapClient.getSession().get(12, TimeUnit.SECONDS)
            val accountId = session.getPrimaryAccount(MailAccountCapability::class.java)
                ?: return@withContext emptyList()

            val excludeIds = (
                queryMailboxIds(jmapClient, accountId, JSONObject().put("role", "trash")) +
                queryMailboxIds(jmapClient, accountId, JSONObject().put("role", "junk"))
            ).distinct().toTypedArray()

            val filter = rs.ltt.jmap.common.entity.filter.EmailFilterCondition.builder()
                .hasKeyword("\$flagged")
                .also { if (excludeIds.isNotEmpty()) it.inMailboxOtherThan(excludeIds) }
                .build()

            val queryCall = rs.ltt.jmap.common.method.call.email.QueryEmailMethodCall.builder()
                .accountId(accountId)
                .filter(filter)
                .sort(arrayOf(rs.ltt.jmap.common.entity.Comparator("receivedAt", false)))
                .limit(50L)
                .build()

            val queryResponse = jmapClient.call(queryCall).get().getMain(rs.ltt.jmap.common.method.response.email.QueryEmailMethodResponse::class.java)
            val ids = queryResponse.ids

            if (ids.isNullOrEmpty()) return@withContext emptyList()

            val getCall = rs.ltt.jmap.common.method.call.email.GetEmailMethodCall.builder()
                .accountId(accountId)
                .ids(ids)
                .properties(arrayOf("id", "subject", "from", "to", "preview", "keywords", "receivedAt", "attachments"))
                .build()

            val getResponse = jmapClient.call(getCall).get().getMain(rs.ltt.jmap.common.method.response.email.GetEmailMethodResponse::class.java)

            return@withContext getResponse.list.map { email ->
                val fromEmail = email.from?.firstOrNull()?.email ?: ""
                val fromName = email.from?.firstOrNull()?.name ?: ""
                val isSeen = email.keywords?.containsKey("\$seen") == true
                val isStarred = email.keywords?.containsKey("\$flagged") == true

                val atts = email.attachments?.mapNotNull { part ->
                    val blobId = part.blobId ?: return@mapNotNull null
                    EmailAttachmentInfo(
                        blobId = blobId,
                        name = part.name ?: "attachment",
                        mimeType = part.type ?: "application/octet-stream",
                        size = part.size ?: 0L
                    )
                } ?: emptyList()

                EmailSummary(
                    id = email.id,
                    subject = email.subject ?: "(No Subject)",
                    from = if (fromName.isNotBlank()) fromName else fromEmail,
                    fromEmail = fromEmail,
                    preview = email.preview ?: "",
                    seen = isSeen,
                    isStarred = isStarred,
                    receivedAt = email.receivedAt?.toEpochMilli() ?: 0L,
                    attachments = atts,
                    keywords = customKeywords(email.keywords)
                )
            }
        }
    }

    private fun fetchEmailsForMailbox(
        jmapClient: JmapClient,
        accountId: String,
        mailboxId: String?
    ): List<EmailSummary> {
        val filter = if (!mailboxId.isNullOrBlank()) {
            rs.ltt.jmap.common.entity.filter.EmailFilterCondition.builder().inMailbox(mailboxId).build()
        } else null

        val queryCall = rs.ltt.jmap.common.method.call.email.QueryEmailMethodCall.builder()
            .accountId(accountId)
            .filter(filter)
            .sort(arrayOf(rs.ltt.jmap.common.entity.Comparator("receivedAt", false)))
            .limit(50L)
            .build()

        val queryResponse = jmapClient.call(queryCall).get().getMain(rs.ltt.jmap.common.method.response.email.QueryEmailMethodResponse::class.java)
        val ids = queryResponse.ids

        if (ids.isNullOrEmpty()) return emptyList()

        val getCall = rs.ltt.jmap.common.method.call.email.GetEmailMethodCall.builder()
            .accountId(accountId)
            .ids(ids)
            .properties(arrayOf("id", "subject", "from", "to", "preview", "keywords", "receivedAt", "attachments"))
            .build()

        val getResponse = jmapClient.call(getCall).get().getMain(rs.ltt.jmap.common.method.response.email.GetEmailMethodResponse::class.java)

        return getResponse.list.map { email ->
            val fromEmail = email.from?.firstOrNull()?.email ?: ""
            val fromName = email.from?.firstOrNull()?.name ?: ""
            val toEmail = email.to?.firstOrNull()?.email ?: ""
            val isSeen = email.keywords?.containsKey("\$seen") == true
            val isStarred = email.keywords?.containsKey("\$flagged") == true

            val atts = email.attachments?.mapNotNull { part ->
                val blobId = part.blobId ?: return@mapNotNull null
                EmailAttachmentInfo(
                    blobId = blobId,
                    name = part.name ?: "attachment",
                    mimeType = part.type ?: "application/octet-stream",
                    size = part.size ?: 0L
                )
            } ?: emptyList()

            EmailSummary(
                id = email.id,
                subject = email.subject ?: "(No Subject)",
                from = if (fromName.isNotBlank()) fromName else fromEmail,
                fromEmail = fromEmail,
                preview = email.preview ?: "",
                seen = isSeen,
                isStarred = isStarred,
                receivedAt = email.receivedAt?.toEpochMilli() ?: 0L,
                toEmail = toEmail,
                attachments = atts,
                keywords = customKeywords(email.keywords)
            )
        }
    }

    /** Non-system keywords ("$seen", "$flagged", ... excluded) = user labels. */
    private fun customKeywords(keywords: Map<String, Boolean>?): Set<String> =
        keywords?.keys?.filter { !it.startsWith("$") }?.toSet() ?: emptySet()

    data class MailboxInfo(val id: String, val name: String, val role: String?)

    data class Attachment(
        val name: String,
        val mimeType: String,
        val size: Long,
        val data: ByteArray
    )

    suspend fun fetchMailboxes(connectedAccount: ConnectedAccount): List<MailboxInfo> = withContext(Dispatchers.IO) {
        val client = newClient(connectedAccount)
        client.use { jmapClient ->
            val session = jmapClient.getSession().get(12, TimeUnit.SECONDS)
            val accountId = session.getPrimaryAccount(MailAccountCapability::class.java)
                ?: return@withContext emptyList()

            val call = rs.ltt.jmap.common.method.call.mailbox.GetMailboxMethodCall.builder()
                .accountId(accountId)
                .build()

            try {
                val response = jmapClient.call(call).get().getMain(rs.ltt.jmap.common.method.response.mailbox.GetMailboxMethodResponse::class.java)
                response.list?.map { MailboxInfo(it.id, it.name, it.role?.name) } ?: emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "fetchMailboxes failed", e)
                emptyList()
            }
        }
    }

    private fun queryMailboxIds(
        jmapClient: JmapClient,
        accountId: String,
        filter: JSONObject?
    ): List<String> {
        val builder = rs.ltt.jmap.common.method.call.mailbox.QueryMailboxMethodCall.builder().accountId(accountId)

        if (filter != null && filter.has("role")) {
            val roleStr = filter.optString("role", "")
            if (roleStr.isNotBlank()) {
                val role = try {
                    rs.ltt.jmap.common.entity.Role.valueOf(roleStr.uppercase())
                } catch (e: IllegalArgumentException) {
                    null
                }
                if (role != null) {
                    builder.filter(rs.ltt.jmap.common.entity.filter.MailboxFilterCondition.builder().role(role).build())
                }
            }
        }

        val response = jmapClient.call(builder.build()).get().getMain(rs.ltt.jmap.common.method.response.mailbox.QueryMailboxMethodResponse::class.java)
        return response.ids?.toList() ?: emptyList()
    }

    suspend fun setSeen(
        connectedAccount: ConnectedAccount,
        emailId: String,
        seen: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        val client = newClient(connectedAccount)
        client.use { jmapClient ->
            val session = jmapClient.getSession().get(12, TimeUnit.SECONDS)
            val accountId = session.getPrimaryAccount(MailAccountCapability::class.java)
                ?: return@withContext false

            val patch: Map<String, Any> = if (seen) {
                mapOf("keywords/\$seen" to true)
            } else {
                val getCall = rs.ltt.jmap.common.method.call.email.GetEmailMethodCall.builder()
                    .accountId(accountId)
                    .ids(arrayOf(emailId))
                    .properties(arrayOf("id", "keywords"))
                    .build()
                val getResponse = jmapClient.call(getCall).get()
                    .getMain(rs.ltt.jmap.common.method.response.email.GetEmailMethodResponse::class.java)
                val current = getResponse.list.firstOrNull()?.keywords ?: emptyMap()
                val newKeywords = current.filterKeys { it != "\$seen" }
                mapOf("keywords" to newKeywords)
            }

            val update = mapOf(emailId to patch)
            val setCall = rs.ltt.jmap.common.method.call.email.SetEmailMethodCall.builder()
                .accountId(accountId)
                .update(update as Map<String, Map<String, Any>>)
                .build()

            try {
                jmapClient.call(setCall).get()
                true
            } catch (e: Exception) {
                Log.e(TAG, "setSeen failed", e)
                false
            }
        }
    }

    suspend fun setFavorite(
        connectedAccount: ConnectedAccount,
        emailId: String,
        favorite: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        val client = newClient(connectedAccount)
        client.use { jmapClient ->
            val session = jmapClient.getSession().get(12, TimeUnit.SECONDS)
            val accountId = session.getPrimaryAccount(MailAccountCapability::class.java)
                ?: return@withContext false

            val patch: Map<String, Any> = if (favorite) {
                mapOf("keywords/\$flagged" to true)
            } else {
                val getCall = rs.ltt.jmap.common.method.call.email.GetEmailMethodCall.builder()
                    .accountId(accountId)
                    .ids(arrayOf(emailId))
                    .properties(arrayOf("id", "keywords"))
                    .build()
                val getResponse = jmapClient.call(getCall).get()
                    .getMain(rs.ltt.jmap.common.method.response.email.GetEmailMethodResponse::class.java)
                val current = getResponse.list.firstOrNull()?.keywords ?: emptyMap()
                val newKeywords = current.filterKeys { it != "\$flagged" }
                mapOf("keywords" to newKeywords)
            }
            val update = mapOf(emailId to patch)

            val setCall = rs.ltt.jmap.common.method.call.email.SetEmailMethodCall.builder()
                .accountId(accountId)
                .update(update as Map<String, Map<String, Any>>)
                .build()

            try {
                jmapClient.call(setCall).get()
                true
            } catch (e: Exception) {
                Log.e(TAG, "setFavorite failed", e)
                false
            }
        }
    }

    /** Adds or removes an arbitrary (label) keyword on an email. */
    suspend fun setKeyword(
        connectedAccount: ConnectedAccount,
        emailId: String,
        keyword: String,
        value: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        val client = newClient(connectedAccount)
        client.use { jmapClient ->
            val session = jmapClient.getSession().get(12, TimeUnit.SECONDS)
            val accountId = session.getPrimaryAccount(MailAccountCapability::class.java)
                ?: return@withContext false

            val patch: Map<String, Any> = if (value) {
                mapOf("keywords/$keyword" to true)
            } else {
                val getCall = rs.ltt.jmap.common.method.call.email.GetEmailMethodCall.builder()
                    .accountId(accountId)
                    .ids(arrayOf(emailId))
                    .properties(arrayOf("id", "keywords"))
                    .build()
                val getResponse = jmapClient.call(getCall).get()
                    .getMain(rs.ltt.jmap.common.method.response.email.GetEmailMethodResponse::class.java)
                val current = getResponse.list.firstOrNull()?.keywords ?: emptyMap()
                val newKeywords = current.filterKeys { it != keyword }
                mapOf("keywords" to newKeywords)
            }
            val update = mapOf(emailId to patch)

            val setCall = rs.ltt.jmap.common.method.call.email.SetEmailMethodCall.builder()
                .accountId(accountId)
                .update(update as Map<String, Map<String, Any>>)
                .build()

            try {
                jmapClient.call(setCall).get()
                true
            } catch (e: Exception) {
                Log.e(TAG, "setKeyword failed", e)
                false
            }
        }
    }

    /** Emails carrying a given (label) keyword, excluding trash and junk. */
    suspend fun fetchEmailsByKeyword(
        connectedAccount: ConnectedAccount,
        keyword: String
    ): List<EmailSummary> = withContext(Dispatchers.IO) {
        val client = newClient(connectedAccount)
        client.use { jmapClient ->
            val session = jmapClient.getSession().get(12, TimeUnit.SECONDS)
            val accountId = session.getPrimaryAccount(MailAccountCapability::class.java)
                ?: return@withContext emptyList()

            val excludeIds = (
                queryMailboxIds(jmapClient, accountId, JSONObject().put("role", "trash")) +
                queryMailboxIds(jmapClient, accountId, JSONObject().put("role", "junk"))
            ).distinct().toTypedArray()

            val filter = rs.ltt.jmap.common.entity.filter.EmailFilterCondition.builder()
                .hasKeyword(keyword)
                .also { if (excludeIds.isNotEmpty()) it.inMailboxOtherThan(excludeIds) }
                .build()

            val queryCall = rs.ltt.jmap.common.method.call.email.QueryEmailMethodCall.builder()
                .accountId(accountId)
                .filter(filter)
                .sort(arrayOf(rs.ltt.jmap.common.entity.Comparator("receivedAt", false)))
                .limit(50L)
                .build()

            val queryResponse = jmapClient.call(queryCall).get().getMain(rs.ltt.jmap.common.method.response.email.QueryEmailMethodResponse::class.java)
            val ids = queryResponse.ids
            if (ids.isNullOrEmpty()) return@withContext emptyList()

            val getCall = rs.ltt.jmap.common.method.call.email.GetEmailMethodCall.builder()
                .accountId(accountId)
                .ids(ids)
                .properties(arrayOf("id", "subject", "from", "to", "preview", "keywords", "receivedAt", "attachments"))
                .build()

            val getResponse = jmapClient.call(getCall).get().getMain(rs.ltt.jmap.common.method.response.email.GetEmailMethodResponse::class.java)

            return@withContext getResponse.list.map { email ->
                val fromEmail = email.from?.firstOrNull()?.email ?: ""
                val fromName = email.from?.firstOrNull()?.name ?: ""
                val atts = email.attachments?.mapNotNull { part ->
                    val blobId = part.blobId ?: return@mapNotNull null
                    EmailAttachmentInfo(
                        blobId = blobId,
                        name = part.name ?: "attachment",
                        mimeType = part.type ?: "application/octet-stream",
                        size = part.size ?: 0L
                    )
                } ?: emptyList()
                EmailSummary(
                    id = email.id,
                    subject = email.subject ?: "(No Subject)",
                    from = if (fromName.isNotBlank()) fromName else fromEmail,
                    fromEmail = fromEmail,
                    preview = email.preview ?: "",
                    seen = email.keywords?.containsKey("\$seen") == true,
                    isStarred = email.keywords?.containsKey("\$flagged") == true,
                    receivedAt = email.receivedAt?.toEpochMilli() ?: 0L,
                    toEmail = email.to?.firstOrNull()?.email ?: "",
                    attachments = atts,
                    keywords = customKeywords(email.keywords)
                )
            }
        }
    }

    suspend fun setMailbox(
        connectedAccount: ConnectedAccount,
        emailId: String,
        mailboxId: String
    ): Boolean = withContext(Dispatchers.IO) {
        val client = newClient(connectedAccount)
        client.use { jmapClient ->
            val session = jmapClient.getSession().get(12, TimeUnit.SECONDS)
            val accountId = session.getPrimaryAccount(MailAccountCapability::class.java)
                ?: return@withContext false

            val mailboxIds = mapOf(mailboxId to true)
            val patch = mapOf("mailboxIds" to mailboxIds)
            val update = mapOf(emailId to patch)

            val setCall = rs.ltt.jmap.common.method.call.email.SetEmailMethodCall.builder()
                .accountId(accountId)
                .update(update as Map<String, Map<String, Any>>)
                .build()

            try {
                jmapClient.call(setCall).get()
                true
            } catch (e: Exception) {
                Log.e(TAG, "setMailbox failed", e)
                false
            }
        }
    }

    /**
     * Creates a mailbox with the given name and (optional) role, returning its server id.
     * Used to provision a folder a server doesn't create by default (e.g. Stalwart ships
     * without an Archive folder, but recognises the "archive" special-use role).
     */
    suspend fun createMailbox(
        connectedAccount: ConnectedAccount,
        name: String,
        role: String?
    ): String? = withContext(Dispatchers.IO) {
        val client = newClient(connectedAccount)
        client.use { jmapClient ->
            val session = jmapClient.getSession().get(12, TimeUnit.SECONDS)
            val accountId = session.getPrimaryAccount(MailAccountCapability::class.java)
                ?: return@withContext null

            val roleEnum = role?.let {
                try { rs.ltt.jmap.common.entity.Role.valueOf(it.uppercase()) }
                catch (_: IllegalArgumentException) { null }
            }
            val mailboxBuilder = rs.ltt.jmap.common.entity.Mailbox.builder().name(name)
            if (roleEnum != null) mailboxBuilder.role(roleEnum)

            val setCall = rs.ltt.jmap.common.method.call.mailbox.SetMailboxMethodCall.builder()
                .accountId(accountId)
                .create(mapOf("c1" to mailboxBuilder.build()))
                .build()

            try {
                val response = jmapClient.call(setCall).get()
                    .getMain(rs.ltt.jmap.common.method.response.mailbox.SetMailboxMethodResponse::class.java)
                val createdId = response.created?.get("c1")?.id
                if (createdId == null) {
                    val err = response.notCreated?.get("c1")
                    Log.e(TAG, "createMailbox '$name' not created: type=${err?.type} desc=${err?.description}")
                }
                createdId
            } catch (e: Exception) {
                Log.e(TAG, "createMailbox '$name' failed", e)
                null
            }
        }
    }

    suspend fun resolveMailboxIdByRole(
        connectedAccount: ConnectedAccount,
        role: String
    ): String? = withContext(Dispatchers.IO) {
        val client = newClient(connectedAccount)
        client.use { jmapClient ->
            val session = jmapClient.getSession().get(12, TimeUnit.SECONDS)
            val accountId = session.getPrimaryAccount(MailAccountCapability::class.java)
                ?: return@withContext null
            queryMailboxIds(
                jmapClient = jmapClient,
                accountId = accountId,
                filter = JSONObject().put("role", role.lowercase())
            ).firstOrNull()
        }
    }

    private fun buildSessionCandidates(rawInput: String): List<String> {
        val input = rawInput.trim().removeSuffix("/")
        if (input.isBlank()) return emptyList()

        // Credentials travel as Basic auth: never allow cleartext, upgrade http:// input.
        val withScheme = when {
            input.startsWith("https://", ignoreCase = true) -> input
            input.startsWith("http://", ignoreCase = true) -> "https://" + input.substringAfter("://")
            else -> "https://$input"
        }

        val base = withScheme.removeSuffix("/jmap").removeSuffix("/jmap/session").removeSuffix("/")
        return listOf(
            "$base/",
            "$base/.well-known/jmap",
            "$base/jmap",
            "$base/jmap/session"
        ).distinct()
    }

    private fun uploadBlob(
        connectedAccount: ConnectedAccount,
        attachment: Attachment,
        uploadUrlTemplate: String
    ): String? {
        return try {
            val uploadUrl = uploadUrlTemplate.replace("{accountId}", connectedAccount.accountId)
            if (!isTrustedServerUrl(uploadUrl, connectedAccount.sessionUrl)) {
                Log.w(TAG, "uploadBlob: refusing upload URL outside session origin")
                return null
            }
            val http = sharedHttp.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()
            val body = attachment.data.toRequestBody(attachment.mimeType.toMediaType())
            val request = Request.Builder()
                .url(uploadUrl)
                .header("Authorization", Credentials.basic(connectedAccount.email, connectedAccount.password))
                .post(body)
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val json = org.json.JSONObject(response.body?.string() ?: return null)
                json.optString("blobId").ifBlank { null }
            }
        } catch (e: Exception) {
            Log.e(TAG, "uploadBlob failed for ${attachment.name}", e)
            null
        }
    }

    suspend fun sendEmail(
        connectedAccount: ConnectedAccount,
        toEmailAddress: String,
        subjectText: String,
        bodyText: String,
        bodyContentType: String = "text/plain",
        attachments: List<Attachment> = emptyList(),
        ccEmailAddress: String = "",
        bccEmailAddress: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        val client = newClient(connectedAccount)
        client.use { jmapClient ->
            try {
                val session = jmapClient.getSession().get(12, TimeUnit.SECONDS)
                val accountId = session.getPrimaryAccount(MailAccountCapability::class.java)
                    ?: return@withContext false

                // 1. Get Identity
                val identityCall = GetIdentityMethodCall.builder().accountId(accountId).build()
                val identityResponse = jmapClient.call(identityCall).get().getMain(GetIdentityMethodResponse::class.java)
                val identity = identityResponse.list.firstOrNull() ?: return@withContext false
                val identityId = identity.id

                // 2. Resolve Sent or Drafts mailbox
                val sentMailboxId = resolveMailboxIdByRole(connectedAccount, "sent")
                val mailboxIds = if (sentMailboxId != null) mapOf(sentMailboxId to true) else emptyMap()

                // 3. Upload attachment blobs
                val uploadUrlTemplate = session.getUploadUrl(accountId)?.toString() ?: ""
                val attachmentParts = if (attachments.isNotEmpty() && uploadUrlTemplate.isNotBlank()) {
                    attachments.mapNotNull { att ->
                        val blobId = uploadBlob(connectedAccount, att, uploadUrlTemplate) ?: return@mapNotNull null
                        EmailBodyPart.builder()
                            .blobId(blobId)
                            .type(att.mimeType)
                            .name(att.name)
                            .build()
                    }
                } else emptyList()

                // 4. Create Email object
                val toAddresses = toEmailAddress.split(",")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .map { EmailAddress.builder().email(it).build() }
                if (toAddresses.isEmpty()) return@withContext false
                val ccAddresses = ccEmailAddress.split(",")
                    .map { it.trim() }.filter { it.isNotBlank() }
                    .map { EmailAddress.builder().email(it).build() }
                val bccAddresses = bccEmailAddress.split(",")
                    .map { it.trim() }.filter { it.isNotBlank() }
                    .map { EmailAddress.builder().email(it).build() }
                val fromAddress = EmailAddress.builder().email(identity.email).name(identity.name).build()

                val bodyPartId = "body1"
                val textBodyPart = EmailBodyPart.builder()
                    .partId(bodyPartId)
                    .type(bodyContentType)
                    .build()

                val bodyValues = mapOf(bodyPartId to rs.ltt.jmap.common.entity.EmailBodyValue.builder().value(bodyText).build())

                val emailBuilder = Email.builder()
                    .from(fromAddress)
                    .subject(subjectText)
                    .also { b -> toAddresses.forEach { b.to(it) } }
                    .also { b -> ccAddresses.forEach { b.cc(it) } }
                    .also { b -> bccAddresses.forEach { b.bcc(it) } }
                    .mailboxIds(mailboxIds)
                    .bodyValues(bodyValues)
                if (bodyContentType.equals("text/html", ignoreCase = true)) {
                    emailBuilder.htmlBody(textBodyPart)
                } else {
                    emailBuilder.textBody(textBodyPart)
                }
                val email = emailBuilder
                    .also { b -> if (attachmentParts.isNotEmpty()) b.attachments(attachmentParts) }
                    .build()

                // 4. Create Draft and Submission
                val creationId = "draft1"
                val setEmailCall = SetEmailMethodCall.builder()
                    .accountId(accountId)
                    .create(mapOf(creationId to email))
                    .build()

                val emailResponse = jmapClient.call(setEmailCall).get().getMain(SetEmailMethodResponse::class.java)
                val createdEmailId = emailResponse.created?.get(creationId)?.id

                if (createdEmailId == null) {
                    val setError = emailResponse.notCreated?.get(creationId)
                    Log.e(TAG, "Failed to create email draft: type=${setError?.type} desc=${setError?.description} mailboxIds=$mailboxIds")
                    return@withContext false
                }

                val submission = EmailSubmission.builder()
                    .emailId(createdEmailId)
                    .identityId(identityId)
                    .build()

                val submitCall = SetEmailSubmissionMethodCall.builder()
                    .accountId(accountId)
                    .create(mapOf("sub1" to submission))
                    .build()

                jmapClient.call(submitCall).get()
                true
            } catch (e: Exception) {
                Log.e(TAG, "sendEmail failed", e)
                false
            }
        }
    }

    suspend fun saveDraft(
        connectedAccount: ConnectedAccount,
        toEmailAddress: String,
        subjectText: String,
        bodyText: String,
        bodyContentType: String = "text/html",
        attachments: List<Attachment> = emptyList(),
        ccEmailAddress: String = "",
        bccEmailAddress: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        val client = newClient(connectedAccount)
        client.use { jmapClient ->
            try {
                val session = jmapClient.getSession().get(12, TimeUnit.SECONDS)
                val accountId = session.getPrimaryAccount(MailAccountCapability::class.java)
                    ?: return@withContext false

                val identityCall = GetIdentityMethodCall.builder().accountId(accountId).build()
                val identityResponse = jmapClient.call(identityCall).get().getMain(GetIdentityMethodResponse::class.java)
                val identity = identityResponse.list.firstOrNull() ?: return@withContext false

                val draftsMailboxId = resolveMailboxIdByRole(connectedAccount, "drafts")
                    ?: return@withContext false
                val mailboxIds = mapOf(draftsMailboxId to true)

                val uploadUrlTemplate = session.getUploadUrl(accountId)?.toString() ?: ""
                val attachmentParts = if (attachments.isNotEmpty() && uploadUrlTemplate.isNotBlank()) {
                    attachments.mapNotNull { att ->
                        val blobId = uploadBlob(connectedAccount, att, uploadUrlTemplate) ?: return@mapNotNull null
                        EmailBodyPart.builder()
                            .blobId(blobId)
                            .type(att.mimeType)
                            .name(att.name)
                            .build()
                    }
                } else emptyList()

                val fromAddress = EmailAddress.builder().email(identity.email).name(identity.name).build()
                val bodyPartId = "body1"
                val textBodyPart = EmailBodyPart.builder()
                    .partId(bodyPartId)
                    .type(bodyContentType)
                    .build()
                val bodyValues = mapOf(bodyPartId to rs.ltt.jmap.common.entity.EmailBodyValue.builder().value(bodyText).build())

                val emailBuilder = Email.builder()
                    .from(fromAddress)
                    .subject(subjectText)
                    .mailboxIds(mailboxIds)
                    .keyword("\$draft", true)
                    .keyword("\$seen", true)
                    .receivedAt(java.time.Instant.now())
                    .bodyValues(bodyValues)
                toEmailAddress.split(",")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .forEach { emailBuilder.to(EmailAddress.builder().email(it).build()) }
                ccEmailAddress.split(",")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .forEach { emailBuilder.cc(EmailAddress.builder().email(it).build()) }
                bccEmailAddress.split(",")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .forEach { emailBuilder.bcc(EmailAddress.builder().email(it).build()) }
                if (bodyContentType.equals("text/html", ignoreCase = true)) {
                    emailBuilder.htmlBody(textBodyPart)
                } else {
                    emailBuilder.textBody(textBodyPart)
                }
                if (attachmentParts.isNotEmpty()) emailBuilder.attachments(attachmentParts)
                val email = emailBuilder.build()

                val creationId = "draft1"
                val setEmailCall = SetEmailMethodCall.builder()
                    .accountId(accountId)
                    .create(mapOf(creationId to email))
                    .build()

                val emailResponse = jmapClient.call(setEmailCall).get().getMain(SetEmailMethodResponse::class.java)
                val createdEmailId = emailResponse.created?.get(creationId)?.id
                if (createdEmailId == null) {
                    val setError = emailResponse.notCreated?.get(creationId)
                    Log.e(TAG, "Failed to save draft: type=${setError?.type} desc=${setError?.description}")
                    return@withContext false
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "saveDraft failed", e)
                false
            }
        }
    }

    /** Permanently destroys an email (used to replace an edited draft). */
    suspend fun destroyEmail(
        connectedAccount: ConnectedAccount,
        emailId: String
    ): Boolean = withContext(Dispatchers.IO) {
        val client = newClient(connectedAccount)
        client.use { jmapClient ->
            try {
                val session = jmapClient.getSession().get(12, TimeUnit.SECONDS)
                val accountId = session.getPrimaryAccount(MailAccountCapability::class.java)
                    ?: return@withContext false
                val call = SetEmailMethodCall.builder()
                    .accountId(accountId)
                    .destroy(arrayOf(emailId))
                    .build()
                jmapClient.call(call).get()
                true
            } catch (e: Exception) {
                Log.e(TAG, "destroyEmail failed", e)
                false
            }
        }
    }

    suspend fun registerPushSubscription(
        context: android.content.Context,
        connectedAccount: ConnectedAccount,
        pushEndpointUrl: String,
        deviceClientId: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val keys = WebPushKeys.getOrCreate(context)
            val client = newClient(connectedAccount)
            client.use { jmapClient ->
                val pushKeys = rs.ltt.jmap.common.entity.Keys.builder()
                    .p256dh(keys.p256dh)
                    .auth(keys.auth)
                    .build()

                val pushSubscription = rs.ltt.jmap.common.entity.PushSubscription.builder()
                    .deviceClientId(deviceClientId)
                    .url(pushEndpointUrl)
                    .keys(pushKeys)
                    .build()

                val methodCall = rs.ltt.jmap.common.method.call.core.SetPushSubscriptionMethodCall
                    .builder()
                    .accountId(connectedAccount.accountId)
                    .create(mapOf("ps0" to pushSubscription))
                    .build()

                val response = jmapClient.call(methodCall).get(30, TimeUnit.SECONDS)
                    .getMain(rs.ltt.jmap.common.method.response.core.SetPushSubscriptionMethodResponse::class.java)

                val list = response.list
                if (!list.isNullOrEmpty()) {
                    Log.d(TAG, "PushSubscription registered: id=${list[0]?.id}")
                    true
                } else {
                    Log.w(TAG, "PushSubscription: empty list in response")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "registerPushSubscription failed: ${e.message}")
            false
        }
    }

    /**
     * Resolves an authenticated download URL + headers for a blob without downloading it,
     * so MediaMetadataRetriever can stream ranged reads (e.g. video thumbnails) instead of
     * pulling the whole file. Returns null if the URL can't be resolved or isn't trusted.
     */
    suspend fun blobDownloadRequest(
        connectedAccount: ConnectedAccount,
        blobId: String,
        filename: String,
        mimeType: String
    ): Pair<String, Map<String, String>>? = withContext(Dispatchers.IO) {
        try {
            val client = newClient(connectedAccount)
            client.use { jmapClient ->
                val session = jmapClient.getSession().get(12, TimeUnit.SECONDS)
                val accountId = session.getPrimaryAccount(MailAccountCapability::class.java)
                    ?: return@withContext null
                val downloadUrl = session.getDownloadUrl(accountId, blobId, filename, mimeType)
                    ?: return@withContext null
                val urlStr = downloadUrl.toString()
                if (!isTrustedServerUrl(urlStr, connectedAccount.sessionUrl)) {
                    Log.w(TAG, "blobDownloadRequest: refusing URL outside session origin")
                    return@withContext null
                }
                val auth = Credentials.basic(connectedAccount.email, connectedAccount.password)
                urlStr to mapOf("Authorization" to auth)
            }
        } catch (e: Exception) {
            Log.e(TAG, "blobDownloadRequest failed for $filename", e)
            null
        }
    }

    suspend fun downloadBlob(
        connectedAccount: ConnectedAccount,
        blobId: String,
        filename: String,
        mimeType: String
    ): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val client = newClient(connectedAccount)
            client.use { jmapClient ->
                val session = jmapClient.getSession().get(12, TimeUnit.SECONDS)
                val accountId = session.getPrimaryAccount(MailAccountCapability::class.java)
                    ?: return@withContext null
                val downloadUrl = session.getDownloadUrl(accountId, blobId, filename, mimeType)
                    ?: return@withContext null
                if (!isTrustedServerUrl(downloadUrl.toString(), connectedAccount.sessionUrl)) {
                    Log.w(TAG, "downloadBlob: refusing download URL outside session origin")
                    return@withContext null
                }
                val http = sharedHttp.newBuilder().callTimeout(60, TimeUnit.SECONDS).build()
                val req = Request.Builder()
                    .url(downloadUrl)
                    .header("Authorization", Credentials.basic(connectedAccount.email, connectedAccount.password))
                    .get()
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    resp.body?.bytes()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "downloadBlob failed for $filename", e)
            null
        }
    }

    /**
     * Short-lived JmapClient sharing a process-wide session cache: SessionClient.get()
     * consults the cache before hitting the network, so repeated operations skip the
     * per-call session round trip. The library invalidates the cached session itself
     * when a response carries a new sessionState.
     */
    private fun newClient(account: ConnectedAccount): JmapClient {
        val url = account.sessionUrl.toHttpUrlOrNull()
            ?: throw IllegalStateException("Invalid session URL")
        return JmapClient(account.email, account.password, url)
            .apply { setSessionCache(sessionCache) }
    }

    companion object {
        private const val TAG = "JMapClient"

        private val sessionCache = rs.ltt.jmap.client.session.InMemorySessionCache()

        // One shared client: reuses connection pool and dispatcher threads across
        // uploads/downloads instead of allocating a fresh stack per call.
        private val sharedHttp = OkHttpClient()

        /**
         * Server-provided URLs (upload/download/eventSource templates) receive Basic auth
         * credentials, so they must be HTTPS and on the same host as the session URL.
         */
        internal fun isTrustedServerUrl(url: String, sessionUrl: String): Boolean {
            val u = url.toHttpUrlOrNull() ?: return false
            val s = sessionUrl.toHttpUrlOrNull() ?: return false
            return u.scheme == "https" && u.host.equals(s.host, ignoreCase = true)
        }
    }
}
