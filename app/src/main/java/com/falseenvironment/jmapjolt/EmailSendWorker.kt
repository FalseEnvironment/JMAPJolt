package com.falseenvironment.jmapjolt

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * One addressable attachment in an outgoing message. [uri] is a picker URI whose bytes
 * are staged to the app cache before the background send; [existingBlobId] is reused
 * as-is when a draft being edited already carries the attachment on the server.
 */
internal data class OutgoingAttachment(
    val name: String,
    val mimeType: String,
    val size: Long,
    val uri: android.net.Uri? = null,
    val existingBlobId: String? = null
)

/**
 * Hands an email to WorkManager so upload and submission never block the compose UI.
 * The message body and attachment copies live in a staging directory: WorkManager's
 * input Data caps out at 10 KB, while a rich-text body or a video can be much larger.
 */
internal object EmailSender {

    data class Request(
        val accountEmail: String,
        val to: String,
        val cc: String,
        val bcc: String,
        val subject: String,
        val bodyHtml: String,
        val attachments: List<OutgoingAttachment>,
        val oldDraftId: String? = null
    )

    sealed interface Event {
        data class Sent(val to: String, val subject: String) : Event
        data class Failed(val message: String) : Event
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 16)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    fun send(context: Context, request: Request) {
        val app = context.applicationContext
        scope.launch {
            val staged = try {
                stageAttachments(app, request.attachments)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.e(TAG, "Could not stage outgoing attachments", t)
                val message = t.message?.takeIf { it.isNotBlank() } ?: "Could not prepare the email"
                notifyResult(app, false, request.to, message)
                _events.tryEmit(Event.Failed(message))
                return@launch
            }
            val dir = staged.directory
            try {
                val requestJson = JSONObject()
                    .put("accountEmail", request.accountEmail)
                    .put("to", request.to)
                    .put("cc", request.cc)
                    .put("bcc", request.bcc)
                    .put("subject", request.subject)
                    .put("body", request.bodyHtml)
                    .put("oldDraftId", request.oldDraftId ?: "")
                    .put("stagingDir", dir.absolutePath)

                val attachmentsJson = JSONArray()
                staged.attachments.forEach { attachment ->
                    val item = JSONObject()
                        .put("name", attachment.name)
                        .put("mimeType", attachment.mimeType)
                        .put("size", attachment.size)
                    attachment.existingBlobId?.let { item.put("existingBlobId", it) }
                    attachment.file?.let { item.put("path", it.absolutePath) }
                    attachmentsJson.put(item)
                }
                requestJson.put("attachments", attachmentsJson)
                File(dir, REQUEST_FILE).writeText(requestJson.toString())

                val work = OneTimeWorkRequestBuilder<EmailSendWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .setInputData(
                        Data.Builder()
                            .putString(EmailSendWorker.KEY_STAGING_DIR, dir.absolutePath)
                            .build()
                    )
                    .build()
                WorkManager.getInstance(app).enqueue(work)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.e(TAG, "Could not queue outgoing email", t)
                dir.deleteRecursively()
                val message = t.message?.takeIf { it.isNotBlank() } ?: "Could not prepare the email"
                notifyResult(app, false, request.to, message)
                _events.tryEmit(Event.Failed(message))
            }
        }
    }

    internal fun publish(event: Event) {
        _events.tryEmit(event)
    }

    internal fun notifySending(context: Context, to: String) {
        val text = context.getString(R.string.send_sending_text, to.ifBlank { "…" })
        post(context, R.string.send_sending_title, text, ongoing = true)
    }

    internal fun notifyResult(context: Context, success: Boolean, to: String, detail: String?) {
        if (success) {
            post(context, R.string.send_sent_title, context.getString(R.string.send_sent_text, to), ongoing = false)
        } else {
            post(
                context,
                R.string.send_failed_title,
                detail?.takeIf { it.isNotBlank() } ?: context.getString(R.string.send_failed_generic),
                ongoing = false
            )
        }
    }

    private fun post(context: Context, titleRes: Int, text: String, ongoing: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(context)
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lucide_send)
            .setContentTitle(context.getString(titleRes))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_EMAIL)
            .setContentIntent(openApp)
            .setAutoCancel(!ongoing)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(ongoing)
            .build()
        NotificationManagerCompat.from(context).notify(SEND_NOTIFICATION_ID, notification)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.send_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = context.getString(R.string.send_channel_description)
                }
            )
        }
    }

    private const val TAG = "EmailSender"
    private const val CHANNEL_ID = "send_status"
    private const val SEND_NOTIFICATION_ID = 5001
    internal const val STAGING_ROOT = "outbox"
    internal const val REQUEST_FILE = "request.json"
}

/** Keeps staged files inside the outbox directory: no separators, no traversal. */
internal fun safeAttachmentFileName(name: String): String {
    val cleaned = name.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_')
    return (cleaned.ifBlank { "attachment" }).takeLast(120)
}

internal class StagedAttachments(
    val attachments: List<JMapClient.Attachment>,
    val directory: File
) {
    fun cleanup() {
        directory.deleteRecursively()
    }
}

/**
 * Copies picker URIs into the app cache, outside the main thread. Streaming from the
 * resulting files (instead of reading every byte into a ByteArray) keeps videos out of
 * the heap, and the copies remain readable once the picker's temporary grant is gone.
 */
internal fun stageAttachments(
    context: Context,
    attachments: List<OutgoingAttachment>
): StagedAttachments {
    val dir = File(File(context.cacheDir, EmailSender.STAGING_ROOT), UUID.randomUUID().toString())
    if (!dir.mkdirs() && !dir.isDirectory) throw IOException("Could not create the outbox")
    return try {
        val staged = ArrayList<JMapClient.Attachment>(attachments.size)
        attachments.forEachIndexed { index, attachment ->
            val blobId = attachment.existingBlobId
            val uri = attachment.uri
            if (!blobId.isNullOrBlank()) {
                staged += JMapClient.Attachment(
                    name = attachment.name,
                    mimeType = attachment.mimeType,
                    size = attachment.size,
                    data = ByteArray(0),
                    existingBlobId = blobId
                )
            } else if (uri != null) {
                val file = File(dir, "${index}_${safeAttachmentFileName(attachment.name)}")
                val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                    true
                } ?: false
                if (!copied) throw IOException("Could not read ${attachment.name}")
                staged += JMapClient.Attachment(
                    name = attachment.name,
                    mimeType = attachment.mimeType,
                    size = file.length(),
                    data = ByteArray(0),
                    file = file
                )
            } else {
                throw IOException("Could not read ${attachment.name}")
            }
        }
        StagedAttachments(staged, dir)
    } catch (t: Throwable) {
        dir.deleteRecursively()
        throw t
    }
}

class EmailSendWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val stagingDir = inputData.getString(KEY_STAGING_DIR)?.let(::File)
            ?: return Result.failure()
        val request = runCatching {
            JSONObject(File(stagingDir, EmailSender.REQUEST_FILE).readText())
        }.getOrNull() ?: return fail(stagingDir, "Could not read the outgoing message")

        val accountEmail = request.optString("accountEmail")
        val to = request.optString("to")
        val cc = request.optString("cc")
        val bcc = request.optString("bcc")
        val subject = request.optString("subject")
        val body = request.optString("body")
        val oldDraftId = request.optString("oldDraftId").takeIf { it.isNotBlank() }
        val account = SecureStorage.connectedAccounts(applicationContext)
            .firstOrNull { it.email.equals(accountEmail, ignoreCase = true) }
            ?: return fail(stagingDir, "Account not found")

        val attachments = try {
            val array = request.optJSONArray("attachments") ?: JSONArray()
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val name = item.optString("name").ifBlank { "attachment" }
                val path = item.optString("path").takeIf { it.isNotBlank() }
                val file = path?.let(::File)
                if (file != null && !file.isFile) {
                    throw IOException("Could not read $name")
                }
                JMapClient.Attachment(
                    name = name,
                    mimeType = item.optString("mimeType").ifBlank { "application/octet-stream" },
                    size = item.optLong("size"),
                    data = ByteArray(0),
                    existingBlobId = item.optString("existingBlobId").takeIf { it.isNotBlank() },
                    file = file
                )
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            return fail(stagingDir, t.message ?: "Could not read attachments")
        }

        EmailSender.notifySending(applicationContext, to)
        val outcome = JMapClient(applicationContext)
            .sendEmail(account, to, subject, body, "text/html", attachments, cc, bcc)

        if (outcome.success) {
            oldDraftId?.let { draftId ->
                runCatching { JMapClient(applicationContext).destroyEmail(account, draftId) }
            }
            Log.i(TAG, "Outgoing email submitted")
            EmailSender.notifyResult(applicationContext, true, to, null)
            EmailSender.publish(EmailSender.Event.Sent(to, subject))
        } else {
            val message = outcome.error ?: "Failed to send email"
            Log.w(TAG, "Outgoing email failed: $message")
            EmailSender.notifyResult(applicationContext, false, to, message)
            EmailSender.publish(EmailSender.Event.Failed(message))
        }
        stagingDir.deleteRecursively()
        return Result.success()
    }

    private fun fail(stagingDir: File, message: String): Result {
        Log.e(TAG, message)
        EmailSender.notifyResult(applicationContext, false, "", message)
        EmailSender.publish(EmailSender.Event.Failed(message))
        stagingDir.deleteRecursively()
        return Result.failure()
    }

    companion object {
        private const val TAG = "EmailSendWorker"
        const val KEY_STAGING_DIR = "staging_dir"
    }
}
