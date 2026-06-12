package com.falseenvironment.jmapjolt

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
    var seen: Boolean = false,
    var isFavorite: Boolean = false,
    val receivedAt: Long = 0L,
    val toEmail: String = "",
    val attachments: List<EmailAttachmentInfo> = emptyList(),
    val accountEmail: String = "",
    // Custom JMAP keywords representing user labels (system "$..." keywords excluded).
    var labels: List<String> = emptyList()
)
