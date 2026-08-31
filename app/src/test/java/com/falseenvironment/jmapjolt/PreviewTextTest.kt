package com.falseenvironment.jmapjolt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewTextTest {

    @Test
    fun `prose with repeated double spaces cleans in bounded time`() {
        // Regression: the selector separator used to accept a run of whitespace in
        // exponentially many ways, so a preview whose words were double-spaced and
        // never reached a "{" hung the fetch thread and left the list empty.
        val preview = List(40) { "word" }.joinToString("  ") + " end of message."

        val elapsed = timed { PreviewText.clean(preview) }

        assertTrue("clean() took ${elapsed}ms", elapsed < 1_000)
    }

    @Test
    fun `column aligned report cleans in bounded time`() {
        // The preview that actually froze the inbox fetch: a Proxmox backup report whose
        // columns are padded with long runs of spaces.
        val preview = "\nDetails\n=======\nVMID    Name           Status    Time       " +
            "Size           Filename                                                            \n" +
            "101     pihole         ok        25s        506.657 MiB    " +
            "/mnt/pve/backups/dump/vzdump-lxc-101-2026_05_10-0"

        val elapsed = timed { PreviewText.clean(preview) }

        assertTrue("clean() took ${elapsed}ms", elapsed < 1_000)
    }

    @Test
    fun `ascii rule lines are dropped`() {
        val raw = """
            ***********************
            È bello averti tra noi!
            ***********************

            Ciao Luca,
        """.trimIndent()
        assertEquals("È bello averti tra noi! Ciao Luca", PreviewText.clean(raw))
    }

    @Test
    fun `inline decoration runs are dropped`() {
        assertEquals(
            "Your order is on the way Linsoul Audio",
            PreviewText.clean("Your order is on the way\n****************\nLinsoul Audio ****")
        )
    }

    @Test
    fun `prose survives cleaning`() {
        assertEquals(
            "Hello there, your order has shipped.",
            PreviewText.clean("Hello there,  your order   has shipped.")
        )
    }

    @Test
    fun `stylesheet preview is dropped`() {
        val raw = "body,td { font-family:Arial; margin:0 } .hdr > .x { color:red }"
        assertEquals("", PreviewText.clean(raw))
    }

    @Test
    fun `body after a stylesheet is kept`() {
        val raw = "body,td { font-family:Arial }  Your invoice is ready to download."
        assertEquals("Your invoice is ready to download.", PreviewText.clean(raw))
    }

    @Test
    fun `quoted reply boilerplate is dropped`() {
        val raw = """
            On Mon, 1 Jan 2024 someone wrote:
            > the quoted original
            Sounds good to me.
        """.trimIndent()
        assertEquals("Sounds good to me.", PreviewText.clean(raw))
    }

    @Test
    fun `tracking urls are dropped`() {
        assertEquals(
            "Track your parcel here",
            PreviewText.clean("<https://example.com/t/abc123> Track your parcel here")
        )
    }

    private fun timed(block: () -> Unit): Long {
        val start = System.currentTimeMillis()
        block()
        return System.currentTimeMillis() - start
    }
}
