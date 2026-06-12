package com.falseenvironment.jmapjolt

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class EmailCache(private val filesDir: File) {

    data class Result(
        val emails: List<DisplayEmail>,
        val folderCache: Map<Int, List<DisplayEmail>>,
        val selectedFolder: Int
    )

    suspend fun save(
        accountEmail: String,
        selectedFolder: Int,
        folderCache: Map<Int, List<DisplayEmail>>,
        currentEmails: List<DisplayEmail>
    ) = withContext(Dispatchers.IO) {
        if (accountEmail.isBlank()) return@withContext
        if (folderCache.isEmpty() && currentEmails.isEmpty()) return@withContext

        val safe = accountEmail.toSafeFilename()
        val temp = File(filesDir, "cache_$safe.json.tmp")
        val dest = File(filesDir, "cache_$safe.json")

        try {
            val allFolders = buildMap {
                putAll(folderCache)
                put(selectedFolder, currentEmails)
            }

            val foldersObj = JSONObject()
            for ((folderId, emails) in allFolders) {
                val arr = JSONArray()
                for (e in emails) {
                    arr.put(JSONObject().apply {
                        put("id", e.id)
                        put("subject", e.subject)
                        put("from", e.from)
                        put("fromEmail", e.fromEmail)
                        put("preview", e.preview)
                        put("seen", e.seen)
                        put("isFavorite", e.isFavorite)
                        put("receivedAt", e.receivedAt)
                        if (e.labels.isNotEmpty()) {
                            put("labels", JSONArray().also { l -> e.labels.forEach { l.put(it) } })
                        }
                        if (e.attachments.isNotEmpty()) {
                            put("attachments", JSONArray().also { atts ->
                                e.attachments.forEach { att ->
                                    atts.put(JSONObject().apply {
                                        put("blobId", att.blobId)
                                        put("name", att.name)
                                        put("mimeType", att.mimeType)
                                        put("size", att.size)
                                    })
                                }
                            })
                        }
                    })
                }
                foldersObj.put(folderId.toString(), arr)
            }
            val root = JSONObject().apply {
                put("selectedFolder", selectedFolder)
                put("folders", foldersObj)
            }
            temp.writeText(root.toString())
            if (!temp.renameTo(dest)) {
                Log.e(TAG, "Cache rename failed: ${temp.path} -> ${dest.path}")
                temp.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cache save failed", e)
            temp.delete()
        }
    }

    suspend fun load(accountEmail: String): Result? = withContext(Dispatchers.IO) {
        val safe = accountEmail.toSafeFilename()
        val file = File(filesDir, "cache_$safe.json")
        if (!file.exists()) return@withContext null

        try {
            val content = file.readText()
            if (content.isBlank()) return@withContext null

            val root = JSONObject(content)
            val selectedFolder = root.optInt("selectedFolder", R.id.nav_inbox)
            val folderCache = mutableMapOf<Int, List<DisplayEmail>>()

            when {
                root.has("folders") -> {
                    val obj = root.getJSONObject("folders")
                    for (key in obj.keys()) {
                        folderCache[key.toInt()] = parseEmails(obj.optJSONArray(key) ?: JSONArray())
                    }
                }
                root.has("emails") -> {
                    // Legacy single-folder format
                    folderCache[selectedFolder] = parseEmails(root.optJSONArray("emails") ?: JSONArray())
                }
            }

            Result(folderCache[selectedFolder] ?: emptyList(), folderCache, selectedFolder)
        } catch (e: Exception) {
            Log.e(TAG, "Cache load failed", e)
            null
        }
    }

    private fun parseEmails(arr: JSONArray): List<DisplayEmail> = buildList {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val atts = mutableListOf<EmailAttachmentInfo>()
            val attsArr = o.optJSONArray("attachments")
            if (attsArr != null) {
                for (j in 0 until attsArr.length()) {
                    val a = attsArr.optJSONObject(j) ?: continue
                    atts.add(EmailAttachmentInfo(
                        blobId = a.optString("blobId"),
                        name = a.optString("name", "attachment"),
                        mimeType = a.optString("mimeType", "application/octet-stream"),
                        size = a.optLong("size", 0L)
                    ))
                }
            }
            add(DisplayEmail(
                id = o.optString("id"),
                subject = o.optString("subject"),
                from = o.optString("from"),
                fromEmail = o.optString("fromEmail"),
                preview = o.optString("preview"),
                fullBody = "",
                seen = o.optBoolean("seen"),
                isFavorite = o.optBoolean("isFavorite"),
                receivedAt = o.optLong("receivedAt"),
                attachments = atts,
                labels = o.optJSONArray("labels")?.let { arr2 ->
                    buildList { for (j in 0 until arr2.length()) arr2.optString(j).takeIf { it.isNotBlank() }?.let { add(it) } }
                } ?: emptyList()
            ))
        }
    }

    // Kept stable intentionally — changing this would invalidate existing on-disk caches.
    // Note: two different addresses that contain literal "_at_" or "_dot_" could collide,
    // but real-world email addresses make this effectively impossible.
    private fun String.toSafeFilename() =
        replace("@", "_at_").replace(".", "_dot_")

    companion object {
        private const val TAG = "EmailCache"
    }
}
