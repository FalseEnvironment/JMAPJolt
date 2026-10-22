package com.falseenvironment.jmapjolt

import android.view.View

/**
 * A top-level screen: the granularity the back gesture walks back through.
 *
 * Mail destinations are identified by their drawer id, which also covers labels and
 * user-created subfolders (both use generated view ids kept in [MainActivity.labelNavIds]
 * and [MainActivity.subfolderNavIds]).
 */
internal sealed interface RootDestination {
    data class Folder(val navId: Int) : RootDestination
    data object Calendar : RootDestination
    data object Contacts : RootDestination
    data object Settings : RootDestination
}

/**
 * How many root moves are remembered. Ten is deep enough for a realistic bounce between
 * mail, calendar and contacts while keeping back predictable; older entries are dropped
 * from the front.
 */
internal const val ROOT_HISTORY_LIMIT = 10

/** Drawer ids that exist for every session and never need validating against the menus. */
private val STATIC_MAIL_NAV_IDS = setOf(
    R.id.nav_unified_inbox,
    R.id.nav_inbox,
    R.id.nav_favourite,
    R.id.nav_archive,
    R.id.nav_sent,
    R.id.nav_drafts,
    R.id.nav_spam,
    R.id.nav_trash
)

/**
 * Records [current] as the place to return to before moving to [destination]. Re-selecting
 * the current screen is not a move, and the oldest entry falls off past [ROOT_HISTORY_LIMIT].
 */
internal fun pushRootDestination(
    history: ArrayDeque<RootDestination>,
    current: RootDestination?,
    destination: RootDestination
) {
    if (current == null || current == destination) return
    history.addLast(current)
    while (history.size > ROOT_HISTORY_LIMIT) history.removeFirst()
}

/** The root screen currently on top, or null while onboarding/login/compose own the window. */
internal fun MainActivity.currentRootDestination(): RootDestination? = when {
    settingsContainer.visibility == View.VISIBLE -> RootDestination.Settings
    calendarPanelView?.visibility == View.VISIBLE -> RootDestination.Calendar
    contactsPanelView?.visibility == View.VISIBLE -> RootDestination.Contacts
    mailboxContainer.visibility == View.VISIBLE -> RootDestination.Folder(selectedFolder)
    else -> null
}

/** Switches top-level screen, remembering the one being left so [restorePreviousRoot] can undo it. */
internal fun MainActivity.navigateToRoot(
    destination: RootDestination,
    mailboxSkipRefresh: Boolean = false
) {
    pushRootDestination(rootDestinationHistory, currentRootDestination(), destination)
    showRootDestination(destination, mailboxSkipRefresh)
}

/**
 * Back within the root screens: restores the most recent destination still available and
 * returns true. Stale entries (a disabled Calendar/Contacts tab, a deleted subfolder) are
 * skipped; false means the history is spent and the caller decides what comes next.
 */
internal fun MainActivity.restorePreviousRoot(): Boolean {
    while (rootDestinationHistory.isNotEmpty()) {
        val destination = rootDestinationHistory.removeLast()
        if (!isRootDestinationAvailable(destination)) continue
        showRootDestination(destination, mailboxSkipRefresh = false)
        return true
    }
    return false
}

private fun MainActivity.showRootDestination(
    destination: RootDestination,
    mailboxSkipRefresh: Boolean
) {
    when (destination) {
        is RootDestination.Folder -> {
            selectedFolder = destination.navId
            showMailboxScreen(skipRefresh = mailboxSkipRefresh)
        }
        RootDestination.Calendar -> showCalendarScreen()
        RootDestination.Contacts -> showContactsScreen()
        RootDestination.Settings -> showSettingsScreen()
    }
}

private fun MainActivity.isRootDestinationAvailable(destination: RootDestination): Boolean =
    when (destination) {
        is RootDestination.Folder ->
            destination.navId in STATIC_MAIL_NAV_IDS ||
                labelNavIds.containsKey(destination.navId) ||
                subfolderNavIds.containsKey(destination.navId)
        RootDestination.Calendar -> CalendarPrefs.isEnabled(this)
        RootDestination.Contacts -> ContactsPrefs.isEnabled(this)
        RootDestination.Settings -> true
    }
