package com.falseenvironment.jmapjolt

import android.content.Context.MODE_PRIVATE
import android.util.Log
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Offline cache persistence: which folders get written, when, and the background
 * warm-up that fills the other folders after the visible one has loaded.
 */

internal fun MainActivity.fetchAllFoldersBackground() {
    if (DemoInbox.isEnabled(this)) return
    val account = connectedAccount ?: return
    lifecycleScope.launch(Dispatchers.IO) {
        try {
            for (navId in categoryOrder) {
                val role = getFolderRole(navId)
                val isFav = navId == R.id.nav_favourite
                val isInbox = navId == R.id.nav_inbox

                val fresh = if (isFav) {
                    jmapClient.fetchStarredEmails(account)
                } else if (isInbox) {
                    jmapClient.fetchEmails(account)
                } else if (role != null) {
                    val mailboxId = jmapClient.resolveMailboxIdByRole(account, role)
                    if (mailboxId != null) jmapClient.fetchEmails(account, mailboxId) else continue
                } else {
                    continue
                }

                val newEmailsList = fresh.map {
                    DisplayEmail(it.id, it.subject, it.from, it.fromEmail, it.preview, it.fullBody, it.seen, it.isStarred, it.receivedAt, toEmail = it.toEmail, ccEmail = it.ccEmail, bccEmail = it.bccEmail, attachments = it.attachments, labels = it.keywords.toList())
                }
                val merged = withLocalBodies(navId, newEmailsList)
                folderCache[navId] = merged

                if (navId == selectedFolder) {
                    withContext(Dispatchers.Main) {
                        updateEmailsList(merged)
                    }
                }
            }
            withContext(Dispatchers.Main) {
                saveEmailCache()
            }
        } catch (e: Exception) {
            Log.e(MainActivity.TAG, "Background fetch all folders failed", e)
        }
    }
}

/**
 * [fresh] with the bodies and body previews already downloaded for [folderId]. When the
 * folder is not in memory yet (sync landing before the offline cache load, background
 * warm-up of other folders) its Room bucket is the source.
 */
internal suspend fun MainActivity.withLocalBodies(folderId: Int, fresh: List<DisplayEmail>): List<DisplayEmail> {
    val known = folderCache[folderId] ?: cacheBucket(folderId)?.let { bucket ->
        runCatching {
            com.falseenvironment.jmapjolt.cache.EmailCacheStore.load(this, bucket)
        }.getOrNull()
    }
    return if (known.isNullOrEmpty()) fresh else fresh.keepLocalBodies(known)
}

/**
 * Stores a body fetched for one email, and the preview rebuilt from it, in every list
 * that shows the email, then schedules the offline cache write so both survive a restart.
 */
internal fun MainActivity.applyFetchedBody(fresh: DisplayEmail) {
    fun DisplayEmail.withBody() = copy(
        fullBody = fresh.fullBody.ifBlank { fullBody },
        preview = fresh.preview.ifBlank { preview },
        attachments = fresh.attachments
    )
    val idx = emails.indexOfFirst { it.id == fresh.id }
    if (idx >= 0) {
        emails[idx] = emails[idx].withBody()
        emailAdapter.notifyItemChanged(idx)
    }
    val bi = baseEmails.indexOfFirst { it.id == fresh.id }
    if (bi >= 0) baseEmails[bi] = baseEmails[bi].withBody()
    folderCache.filterValues { list -> list.any { it.id == fresh.id } }.keys.forEach { folderId ->
        folderCache[folderId] = folderCache[folderId].orEmpty().map { if (it.id == fresh.id) it.withBody() else it }
    }
    saveEmailCache()
}

/**
 * Persists the folders touched since the last write.
 *
 * Called from ~30 mutation sites, so it does two things to stay cheap: only
 * dirty buckets are written (see [FolderCache]), and a burst of taps or
 * swipes is coalesced into a single pass by [MainActivity.CACHE_SAVE_DEBOUNCE_MS].
 * Pass [immediate] to skip the debounce when the activity is going away.
 */
internal fun MainActivity.saveEmailCache(immediate: Boolean = false) {
    if (currentAccountEmail == null) return
    // Demo rows must never reach the offline cache of the real account.
    if (DemoInbox.isEnabled(this)) return
    if (folderCache.isEmpty() && emails.isEmpty()) return
    // The visible folder always counts: `emails` is the live list and can be
    // ahead of the folderCache entry for that folder.
    folderCache.markDirty(selectedFolder)
    getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE).edit()
            .putInt(MainActivity.KEY_LAST_SELECTED_FOLDER, selectedFolder)
            .apply()
    cacheSaveJob?.cancel()
    cacheSaveJob = lifecycleScope.launch {
        if (!immediate) delay(MainActivity.CACHE_SAVE_DEBOUNCE_MS)
        val pending = folderCache.takeDirty()
        if (pending.isEmpty()) return@launch
        val snapshot = pending.mapNotNull { folderId ->
            val list = if (folderId == selectedFolder) emails.toList() else folderCache[folderId]
            if (list == null) null else folderId to list
        }
        // The dirty set is already emptied, so the writes must finish even if
        // the scope is cancelled mid-pass; otherwise those folders are lost.
        withContext(NonCancellable) { writeBuckets(snapshot) }
    }
}

/** Writes each (folder, snapshot) pair; folders that fail stay dirty for the next pass. */
private suspend fun MainActivity.writeBuckets(snapshot: List<Pair<Int, List<DisplayEmail>>>) {
    val failed = mutableListOf<Int>()
    snapshot.forEach { (folderId, list) ->
        val bucket = cacheBucket(folderId) ?: return@forEach
        runCatching {
            com.falseenvironment.jmapjolt.cache.EmailCacheStore.save(this, bucket, list)
        }.onFailure { failed.add(folderId) }
    }
    if (failed.isNotEmpty()) folderCache.restoreDirty(failed)
    InboxWidgetProvider.refreshAll(applicationContext)
}
