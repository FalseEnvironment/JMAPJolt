package com.falseenvironment.jmapjolt

import android.util.Log
import android.view.View
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// Undo for the optimistic destructive moves (archive / spam / trash).
// Those mutations paint first and push to the server in the background, so without
// a recovery path a mis-swipe silently relocates mail on the account. The undo
// reverses all three layers in the same order the move applied them: the visible
// row, the folder caches, then the server.

// Where the email came from, for both the cache restore and the server move back.
internal fun MainActivity.sourceNavIdForUndo(): Int =
    if (selectedFolder == R.id.nav_favourite || selectedFolder == 0) R.id.nav_inbox
    else selectedFolder

// Reverse of [MainActivity.updateFolderCachesForMove]: put [email] back into
// [sourceNavId] and drop it from the folder the move sent it to.
internal fun MainActivity.undoFolderMove(
    email: DisplayEmail,
    movedToNavId: Int,
    sourceNavId: Int
) {
    if (sourceNavId == R.id.nav_inbox) {
        updateFolderCachesForInbox(email)
    } else {
        // Keep the restore authoritative until the server confirms, same as the move did.
        PendingMutations.markMoved(email.id, sourceNavId)
        val source = folderCache[sourceNavId]
        if (source != null && source.none { it.id == email.id }) {
            folderCache[sourceNavId] = insertSortedByDate(source, email)
        }
    }
    if (movedToNavId > 0 && movedToNavId != sourceNavId) {
        folderCache[movedToNavId]?.let { cache ->
            folderCache[movedToNavId] = cache.filter { it.id != email.id }
        }
    }
}

// Show "<message> · Undo" and, if tapped, put [email] back where it was.
// @param row       index the row occupied before the move; it is re-inserted there.
// @param movedTo   drawer nav id the move targeted, so the undo can clear that cache.
// @param wasSpam   also clears the `$junk` keyword the spam action set.
// @param pendingMove the in-flight server call; awaited first so the undo cannot
// overtake it and be overwritten by the move it is undoing.
internal fun MainActivity.showUndoSnackbar(
    message: String,
    email: DisplayEmail,
    row: Int,
    movedTo: Int,
    sourceNavId: Int,
    wasSpam: Boolean = false,
    pendingMove: Job? = null
) {
    showThemedSnackbar(message, actionLabel = getString(R.string.action_undo)) {
        val account = resolveAccountFor(email) ?: connectedAccount ?: return@showThemedSnackbar

        // 1. Row back in place.
        if (emails.none { it.id == email.id }) {
            val at = row.coerceIn(0, emails.size)
            emails.add(at, email)
            emailAdapter.notifyItemInserted(at)
        }
        if (baseEmails.none { it.id == email.id }) baseEmails.add(email)
        updateEmptyState()

        // 2. Caches back in sync.
        undoFolderMove(email, movedTo, sourceNavId)
        saveEmailCache()

        // 3. Server back in sync.
        lifecycleScope.launch {
            try {
                pendingMove?.join()
                if (wasSpam) jmapClient.setJunkKeyword(account, email.id, false)
                val mailboxId = subfolderNavIds[sourceNavId]
                    ?: jmapClient.resolveMailboxIdByRole(
                        account, getFolderRole(sourceNavId) ?: "inbox"
                    )
                if (mailboxId != null) jmapClient.setMailbox(account, email.id, mailboxId)
            } catch (e: Exception) {
                PendingMutations.forget(email.id)
                Log.e(MainActivity.TAG, "Undo of move for ${email.id} failed", e)
            }
        }
    }
}
