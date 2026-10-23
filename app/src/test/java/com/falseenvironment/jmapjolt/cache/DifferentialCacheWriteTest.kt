package com.falseenvironment.jmapjolt.cache

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [planDifferentialWrite] must keep rows that already match the disk state out of the
 * write pass, so a single star tap or body fetch does not re-encrypt the whole folder.
 */
class DifferentialCacheWriteTest {

    private fun row(
        id: String,
        subject: String = "Subject $id",
        body: String = "",
        seen: Boolean = false,
        preview: String = "p",
    ) = CachedEmailRow(
        bucket = "a@example.com#1",
        id = id,
        subject = subject,
        from = "Acme",
        fromEmail = "news@example.com",
        preview = preview,
        fullBody = body,
        seen = seen,
        isFavorite = false,
        receivedAt = 1_000L,
        toEmail = "me@example.com",
        accountEmail = "a@example.com",
        attachmentsJson = "",
        labelsJson = "",
    )

    @Test
    fun `fully unchanged folder writes nothing`() {
        val stored = listOf(row("1", body = "<p>b</p>"), row("2"), row("3"))
        assertEquals(DifferentialCacheWrite(emptyList(), emptyList()), planDifferentialWrite(stored, stored))
    }

    @Test
    fun `only the changed row is written`() {
        val stored = listOf(row("1", body = "<p>b</p>"), row("2", body = "<p>c</p>"))
        val fresh = listOf(row("1", body = "<p>b</p>"), row("2", body = "<p>c</p>", seen = true))

        val plan = planDifferentialWrite(stored, fresh)

        assertEquals(listOf<String>(), plan.deleteIds)
        assertEquals(listOf("2"), plan.upsertRows.map { it.id })
        assertEquals(true, plan.upsertRows.single().seen)
    }

    @Test
    fun `removed rows are deleted, kept rows untouched`() {
        val stored = listOf(row("1"), row("2"), row("3"))
        val fresh = listOf(row("2"))

        val plan = planDifferentialWrite(stored, fresh)

        assertEquals(listOf("1", "3"), plan.deleteIds)
        assertEquals(emptyList<String>(), plan.upsertRows.map { it.id })
    }

    @Test
    fun `new row is inserted`() {
        val stored = listOf(row("1"))
        val fresh = listOf(row("1"), row("9"))

        val plan = planDifferentialWrite(stored, fresh)

        assertEquals(emptyList<String>(), plan.deleteIds)
        assertEquals(listOf("9"), plan.upsertRows.map { it.id })
    }

    @Test
    fun `body-only change counts as a change`() {
        val stored = listOf(row("1", body = ""))
        val fresh = listOf(row("1", body = "<p>new</p>"))

        val plan = planDifferentialWrite(stored, fresh)

        assertEquals(1, plan.upsertRows.size)
        assertEquals("<p>new</p>", plan.upsertRows.single().fullBody)
    }

    @Test
    fun `empty stored and empty fresh write nothing`() {
        assertEquals(DifferentialCacheWrite(emptyList(), emptyList()), planDifferentialWrite(emptyList(), emptyList()))
    }
}
