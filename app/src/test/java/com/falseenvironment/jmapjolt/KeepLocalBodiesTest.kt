package com.falseenvironment.jmapjolt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [keepLocalBodies] stops a body-less list sync from undoing a downloaded body and the
 * preview rebuilt from it, both in memory and in the offline cache.
 */
class KeepLocalBodiesTest {

    private fun row(
        id: String,
        preview: String = "server snippet",
        body: String = "",
        account: String = "a@example.com"
    ) = DisplayEmail(id, "Subject", "Acme", "news@example.com", preview, body, accountEmail = account)

    @Test
    fun `synced row keeps the local body and body preview`() {
        val fresh = listOf(row("1"))
        val known = listOf(row("1", preview = "Weekly news: three new features.", body = "<p>body</p>"))

        val merged = fresh.keepLocalBodies(known).single()

        assertEquals("<p>body</p>", merged.fullBody)
        assertEquals("Weekly news: three new features.", merged.preview)
    }

    @Test
    fun `server fields other than body and preview come from the sync`() {
        val fresh = listOf(row("1").copy(seen = true, subject = "Renamed"))
        val known = listOf(row("1", preview = "Local", body = "<p>body</p>"))

        val merged = fresh.keepLocalBodies(known).single()

        assertEquals(true, merged.seen)
        assertEquals("Renamed", merged.subject)
    }

    @Test
    fun `blank local preview keeps the server one`() {
        val merged = listOf(row("1")).keepLocalBodies(listOf(row("1", preview = "", body = "<p>b</p>"))).single()
        assertEquals("server snippet", merged.preview)
    }

    @Test
    fun `row that already has a body is left alone`() {
        val fresh = listOf(row("1", preview = "New", body = "<p>new</p>"))
        val merged = fresh.keepLocalBodies(listOf(row("1", preview = "Old", body = "<p>old</p>"))).single()
        assertEquals("<p>new</p>", merged.fullBody)
        assertEquals("New", merged.preview)
    }

    @Test
    fun `same id in another account does not match`() {
        val fresh = listOf(row("1", account = "b@example.com"))
        val known = listOf(row("1", preview = "Other account", body = "<p>b</p>"))
        val merged = fresh.keepLocalBodies(known).single()
        assertEquals("", merged.fullBody)
        assertEquals("server snippet", merged.preview)
    }

    @Test
    fun `row without account matches by id`() {
        val merged = listOf(row("1", account = "")).keepLocalBodies(
            listOf(row("1", preview = "Local", body = "<p>b</p>"))
        ).single()
        assertEquals("Local", merged.preview)
    }

    @Test
    fun `nothing known returns the same list`() {
        val fresh = listOf(row("1"))
        assertSame(fresh, fresh.keepLocalBodies(listOf(row("1"))))
    }
}
