package com.falseenvironment.jmapjolt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PreviewText.fromBody] rebuilds the list preview from the downloaded body, the way
 * Flectar replaces the server snippet once a message body is local. The server
 * `preview` of an HTML newsletter is often stylesheet text that [PreviewText.clean]
 * reduces to nothing; the body carries the real prose.
 */
class PreviewFromBodyTest {

    @Test
    fun `plain text body wins over html`() {
        assertEquals(
            "Plain version of the message.",
            PreviewText.fromBody("Plain version of the message.", "<p>HTML version</p>")
        )
    }

    @Test
    fun `blank plain text falls back to html prose`() {
        assertEquals(
            "Hello there, your invoice is ready.",
            PreviewText.fromBody("  \n ", "<html><body><p>Hello there,</p><p>your invoice is ready.</p></body></html>")
        )
    }

    @Test
    fun `html style and head content never reach the preview`() {
        val html = "<html><head><title>Newsletter</title><style>body{margin:0}</style></head>" +
            "<body><div>Weekly news: three new features.</div></body></html>"
        assertEquals("Weekly news: three new features.", PreviewText.fromBody(null, html))
    }

    @Test
    fun `quoted reply history is excluded from html previews`() {
        val html = "<div>Sounds good, see you Monday.</div>" +
            "<blockquote>On Fri, Alice wrote: shall we meet?</blockquote>" +
            "<div class=\"gmail_quote\">older text</div>"
        assertEquals("Sounds good, see you Monday.", PreviewText.fromBody(null, html))
    }

    @Test
    fun `quoted lines are excluded from plain text previews`() {
        val text = "Thanks, done.\n\nOn Mon, Bob wrote:\n> can you check?\n> thanks"
        assertEquals("Thanks, done.", PreviewText.fromBody(text, null))
    }

    @Test
    fun `preview is capped for the list row`() {
        val long = List(200) { "word" }.joinToString(" ")
        val preview = PreviewText.fromBody(long, null)
        assertTrue("length ${preview.length}", preview.length <= PreviewText.MAX_BODY_PREVIEW_CHARS)
        assertTrue(preview.startsWith("word word"))
    }

    @Test
    fun `no body gives an empty preview`() {
        assertEquals("", PreviewText.fromBody(null, null))
    }
}
