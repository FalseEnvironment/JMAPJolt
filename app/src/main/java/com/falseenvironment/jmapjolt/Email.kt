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
    @JvmField var labels: List<String> = emptyList(),
    val threadId: String = "",
    // Transient chat-thread display state, set by buildThreadedView().
    @JvmField var threadCount: Int = 1,
    @JvmField var isThreadHeadRow: Boolean = false,
    @JvmField var isThreadChildRow: Boolean = false,
    // Synthetic "+N more" row that reveals the next page of hidden thread members.
    @JvmField var isThreadMoreRow: Boolean = false,
    @JvmField var threadHiddenCount: Int = 0,
    @JvmField var threadKey: String = ""
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DisplayEmail) return false
        return id == other.id
    }
    override fun hashCode(): Int = id.hashCode()
}
