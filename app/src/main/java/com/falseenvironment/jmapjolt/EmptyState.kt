package com.falseenvironment.jmapjolt

import android.view.View

/**
 * Contextual empty state for the message list.
 *
 * The list previously showed one fixed string everywhere, so "no results for this
 * search" and "your Trash is empty" read identically. The message now names the
 * place the user is actually looking at, and search adds a recovery hint.
 */
internal fun MainActivity.emptyStateMessage(): String = when {
    isSearchActive ->
        getString(R.string.empty_search) + "\n" + getString(R.string.empty_search_hint)
    selectedFolder == R.id.nav_archive   -> getString(R.string.empty_archive)
    selectedFolder == R.id.nav_trash     -> getString(R.string.empty_trash)
    selectedFolder == R.id.nav_spam      -> getString(R.string.empty_spam)
    selectedFolder == R.id.nav_sent      -> getString(R.string.empty_sent)
    selectedFolder == R.id.nav_drafts    -> getString(R.string.empty_drafts)
    selectedFolder == R.id.nav_favourite -> getString(R.string.empty_favourite)
    selectedFolder == R.id.nav_inbox     -> getString(R.string.empty_inbox)
    // Custom JMAP folder: no dedicated copy, so stay generic rather than claim Inbox.
    else -> getString(R.string.empty_folder)
}

/**
 * Show or hide the empty state for the current list, keeping the recycler's
 * visibility in sync. Replaces the visibility toggles that were repeated at seven
 * call sites, each of which had to remember to flip both views.
 */
internal fun MainActivity.updateEmptyState() {
    val isEmpty = emails.isEmpty()
    if (isEmpty) {
        emptyStateView.text = emptyStateMessage()
        // Announce it: a screen reader otherwise hears nothing after a search returns
        // no rows, since no list item ever gains focus.
        emptyStateView.announceForAccessibility(emptyStateView.text)
    }
    emptyStateView.visibility = if (isEmpty) View.VISIBLE else View.GONE
    emailsRecyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
}
