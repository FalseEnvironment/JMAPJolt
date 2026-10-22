package com.falseenvironment.jmapjolt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RootHistoryTest {

    @Test
    fun `re-selecting the current screen is not a move`() {
        val history = ArrayDeque<RootDestination>()
        val inbox = RootDestination.Folder(1)

        pushRootDestination(history, inbox, inbox)

        assertTrue(history.isEmpty())
    }

    @Test
    fun `nothing is recorded before a root screen exists`() {
        val history = ArrayDeque<RootDestination>()

        pushRootDestination(history, null, RootDestination.Calendar)

        assertTrue(history.isEmpty())
    }

    @Test
    fun `each move remembers the screen it left`() {
        val history = ArrayDeque<RootDestination>()
        val inbox = RootDestination.Folder(1)
        val favorite = RootDestination.Folder(2)

        pushRootDestination(history, inbox, favorite)
        pushRootDestination(history, favorite, RootDestination.Calendar)

        assertEquals(listOf(inbox, favorite), history.toList())
    }

    @Test
    fun `oldest moves fall off past the limit`() {
        val history = ArrayDeque<RootDestination>()
        var current: RootDestination = RootDestination.Folder(0)

        repeat(ROOT_HISTORY_LIMIT + 3) { i ->
            val next = RootDestination.Folder(i + 1)
            pushRootDestination(history, current, next)
            current = next
        }

        assertEquals(ROOT_HISTORY_LIMIT, history.size)
        assertEquals(RootDestination.Folder(3), history.first())
        assertEquals(RootDestination.Folder(ROOT_HISTORY_LIMIT + 2), history.last())
    }
}
