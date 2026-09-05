package com.falseenvironment.jmapjolt

/**
 * The in-memory folder snapshots, with per-folder dirty tracking.
 *
 * Persisting the cache used to rewrite every bucket on every mutation (one star
 * tap re-encrypted every cached folder, full HTML bodies included). Mutations
 * are spread over ~30 call sites, so the bookkeeping lives here rather than in
 * each of them: any write through the map marks that folder dirty, and
 * [takeDirty] hands the pending set to the persistence pass.
 *
 * Reads that only warm the map from disk call [markClean] so the loader does not
 * schedule a write of the rows it just read.
 */
internal class FolderCache(
    private val delegate: MutableMap<Int, List<DisplayEmail>> = mutableMapOf()
) : MutableMap<Int, List<DisplayEmail>> by delegate {

    private val dirty = mutableSetOf<Int>()

    fun markDirty(folderId: Int) {
        dirty.add(folderId)
    }

    fun markClean(folderId: Int) {
        dirty.remove(folderId)
    }

    val hasDirtyFolders: Boolean get() = dirty.isNotEmpty()

    /** Returns the pending folders and empties the set. */
    fun takeDirty(): Set<Int> {
        val pending = dirty.toSet()
        dirty.clear()
        return pending
    }

    /** Re-queues folders whose write did not go through, so they are retried. */
    fun restoreDirty(folderIds: Collection<Int>) {
        dirty.addAll(folderIds)
    }

    override fun put(key: Int, value: List<DisplayEmail>): List<DisplayEmail>? {
        dirty.add(key)
        return delegate.put(key, value)
    }

    override fun putAll(from: Map<out Int, List<DisplayEmail>>) {
        dirty.addAll(from.keys)
        delegate.putAll(from)
    }

    override fun remove(key: Int): List<DisplayEmail>? {
        dirty.add(key)
        return delegate.remove(key)
    }

    override fun clear() {
        dirty.addAll(delegate.keys)
        delegate.clear()
    }
}
