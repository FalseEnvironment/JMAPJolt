package com.falseenvironment.jmapjolt

/**
 * Short-lived record of local mutations that the server has not confirmed yet.
 *
 * Every mail action is optimistic: the row is removed (or repainted) immediately and the
 * JMAP call runs in the background. The periodic sync, the SSE StateChange refresh and the
 * pull-to-refresh all replace the visible list with whatever the server returns. When one
 * of those lands before the server has applied the move — a fetch already in flight, a
 * StateChange fired by an unrelated change, a slow `Email/set` — the deleted or archived
 * email pops back into the list.
 *
 * Sync results are filtered through [apply] so a mutation stays authoritative until the
 * server agrees with it, or until [TTL_MS] passes (the call failed and the server truth
 * wins again).
 */
object PendingMutations {

    /** How long a local mutation overrides the server before it is given up on. */
    private const val TTL_MS = 3 * 60 * 1000L

    /** Target nav id for an email destroyed outright (permanent delete). */
    private const val DESTROYED = -1

    private data class Move(val targetNavId: Int, val at: Long)
    private data class Seen(val seen: Boolean, val at: Long)

    private val moves = HashMap<String, Move>()
    private val seenFlags = HashMap<String, Seen>()

    /** [targetNavId] is the folder the email was moved into (`R.id.nav_trash`, …). */
    fun markMoved(id: String, targetNavId: Int) {
        moves[id] = Move(targetNavId, System.currentTimeMillis())
    }

    fun markMoved(ids: Collection<String>, targetNavId: Int) =
        ids.forEach { markMoved(it, targetNavId) }

    fun markDestroyed(ids: Collection<String>) = markMoved(ids, DESTROYED)

    fun markSeen(id: String, seen: Boolean) {
        seenFlags[id] = Seen(seen, System.currentTimeMillis())
    }

    fun markSeen(ids: Collection<String>, seen: Boolean) = ids.forEach { markSeen(it, seen) }

    /** Drops the override after a failed server call so the next sync restores the truth. */
    fun forget(id: String) {
        moves.remove(id)
        seenFlags.remove(id)
    }

    fun forget(ids: Collection<String>) = ids.forEach { forget(it) }

    fun clear() {
        moves.clear()
        seenFlags.clear()
    }

    /**
     * Filters/patches a freshly fetched list for the folder currently on screen.
     *
     * - an email moved elsewhere is hidden while it is still returned by [folderNavId];
     * - an email moved *into* [folderNavId] is left untouched;
     * - a locally toggled read state overrides the server's until it matches.
     *
     * Overrides are dropped as soon as the server agrees, so this never masks a change
     * made on another device.
     */
    fun apply(list: List<DisplayEmail>, folderNavId: Int): List<DisplayEmail> {
        purgeExpired()
        if (moves.isEmpty() && seenFlags.isEmpty()) return list

        val ids = list.map { it.id }.toSet()

        // The server caught up: the email is gone from the folder it left, or it has
        // arrived in the folder it was moved to. Either way the override is done.
        moves.entries.removeAll { (id, move) ->
            if (move.targetNavId == folderNavId) id in ids else id !in ids
        }
        seenFlags.entries.removeAll { (id, flag) ->
            list.firstOrNull { it.id == id }?.seen == flag.seen
        }
        if (moves.isEmpty() && seenFlags.isEmpty()) return list

        return list
            .filter { email ->
                val move = moves[email.id]
                move == null || move.targetNavId == folderNavId
            }
            .map { email ->
                val pending = seenFlags[email.id] ?: return@map email
                if (pending.seen == email.seen) email else email.copy(seen = pending.seen)
            }
    }

    private fun purgeExpired() {
        val cutoff = System.currentTimeMillis() - TTL_MS
        moves.entries.removeAll { it.value.at < cutoff }
        seenFlags.entries.removeAll { it.value.at < cutoff }
    }
}
