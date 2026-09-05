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
                folderCache[navId] = newEmailsList

                if (navId == selectedFolder) {
                    withContext(Dispatchers.Main) {
                        updateEmailsList(newEmailsList)
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
 * Persists the folders touched since the last write.
 *
 * Called from ~30 mutation sites, so it does two things to stay cheap: only
 * dirty buckets are written (see [FolderCache]), and a burst of taps or
 * swipes is coalesced into a single pass by [MainActivity.CACHE_SAVE_DEBOUNCE_MS].
 * Pass [immediate] to skip the debounce when the activity is going away.
 */
internal fun MainActivity.saveEmailCache(immediate: Boolean = false) {
    if (currentAccountEmail == null) return
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
