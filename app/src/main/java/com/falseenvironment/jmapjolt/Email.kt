package com.falseenvironment.jmapjolt

data class AccountEntry(
    val email: String,
    val password: String,
    val serverUrl: String,
    val sessionUrl: String,
    val apiUrl: String,
    val accountId: String
) {
    override fun toString() =
        "AccountEntry(email=$email, serverUrl=$serverUrl, accountId=$accountId)"
}

data class EmailAttachmentInfo(
    val blobId: String,
    val name: String,
    val mimeType: String,
    val size: Long
)

data class DisplayEmail(
    val id: String,
    val subject: String,
    val from: String,
    val fromEmail: String,
    val preview: String,
    val fullBody: String = "",
    @JvmField var seen: Boolean = false,
    @JvmField var isFavorite: Boolean = false,
    val receivedAt: Long = 0L,
    val toEmail: String = "",
    val attachments: List<EmailAttachmentInfo> = emptyList(),
    val accountEmail: String = "",
    @JvmField var labels: List<String> = emptyList()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DisplayEmail) return false
        return id == other.id
    }
    override fun hashCode(): Int = id.hashCode()
}
