package com.falseenvironment.jmapjolt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailHtmlSanitizerTest {

    private fun clean(html: String) = sanitizeEmailHtml(html).lowercase()

    @Test
    fun `uppercase event handler is stripped`() {
        // The regex chain had no IGNORE_CASE on the on* rules, so ONCLICK survived.
        val out = clean("""<div ONCLICK="steal()">hi</div>""")

        assertFalse(out, out.contains("onclick"))
        assertTrue(out, out.contains("hi"))
    }

    @Test
    fun `mixed case and unquoted event handlers are stripped`() {
        val out = clean("""<img src="https://x/y.png" oNeRrOr=alert(1) ONLOAD='alert(2)'>""")

        assertFalse(out, out.contains("onerror"))
        assertFalse(out, out.contains("onload"))
        assertTrue(out, out.contains("https://x/y.png"))
    }

    @Test
    fun `nested script tag does not reconstitute`() {
        // Single-pass removal turned "<scr<script>ipt>" back into "<script>".
        val out = clean("<scr<script>ipt>alert(1)</script>")

        // What is left is inert text ("ipt&gt;alert(1)"): no element named script, and the
        // leftover ">" is entity-escaped so it cannot open one either.
        assertFalse(out, out.contains("<script"))
        assertFalse(out, out.contains("<scr"))
        assertFalse(out, out.contains("ipt>"))
    }

    @Test
    fun `plain script block and its body are removed`() {
        val out = clean("<p>before</p><SCRIPT>var a = 1;</SCRIPT><p>after</p>")

        assertFalse(out, out.contains("<script"))
        assertFalse(out, out.contains("var a"))
        assertTrue(out, out.contains("before") && out.contains("after"))
    }

    @Test
    fun `javascript href is neutralized`() {
        val out = clean("""<a href="JaVaScRiPt:alert(1)">click</a>""")

        assertFalse(out, out.contains("javascript:"))
        assertTrue(out, out.contains("""href="#""""))
        assertTrue(out, out.contains("click"))
    }

    @Test
    fun `javascript href hidden behind whitespace is neutralized`() {
        val out = clean("<a href=\"java\tscript:alert(1)\">click</a>")

        assertFalse(out, out.contains("script:"))
    }

    @Test
    fun `stylesheet link is removed`() {
        val out = clean("""<head><link rel="stylesheet" href="https://evil/x.css"></head><body>hi</body>""")

        assertFalse(out, out.contains("<link"))
        assertFalse(out, out.contains("x.css"))
    }

    @Test
    fun `iframe is removed with its content`() {
        val out = clean("""<p>a</p><iframe src="https://evil/x" srcdoc="<script>alert(1)</script>">fallback</iframe>""")

        assertFalse(out, out.contains("<iframe"))
        assertFalse(out, out.contains("srcdoc"))
        assertTrue(out, out.contains("a"))
    }

    @Test
    fun `form and its inputs are removed`() {
        val out = clean("""<form action="https://evil/steal"><input name="pw" type="password"><button>go</button></form><p>body</p>""")

        assertFalse(out, out.contains("<form"))
        assertFalse(out, out.contains("<input"))
        assertFalse(out, out.contains("<button"))
        assertTrue(out, out.contains("body"))
    }

    @Test
    fun `meta refresh is removed`() {
        val out = clean("""<html><head><META HTTP-EQUIV="refresh" CONTENT="0;url=https://evil/"></head><body>hi</body></html>""")

        assertFalse(out, out.contains("<meta"))
        assertFalse(out, out.contains("refresh"))
    }

    @Test
    fun `object embed base and applet are removed`() {
        val out = clean("""<base href="https://evil/"><object data="x"></object><embed src="y"><applet code="z"></applet><p>keep</p>""")

        listOf("<base", "<object", "<embed", "<applet").forEach {
            assertFalse("$it survived in $out", out.contains(it))
        }
        assertTrue(out, out.contains("keep"))
    }

    @Test
    fun `full document keeps its shell and style block`() {
        // buildHtmlContent patches <head> afterwards, so the shell has to survive.
        val out = clean("""<html><head><style>p{color:red}</style></head><body><p>hi</p></body></html>""")

        assertTrue(out, out.contains("<html"))
        assertTrue(out, out.contains("<head"))
        assertTrue(out, out.contains("p{color:red}"))
        assertTrue(out, out.contains("<p>hi</p>"))
    }

    @Test
    fun `css expression and script schemes are scrubbed from style blocks`() {
        val out = clean("""<html><head><style>a{width:expression(alert(1));background:url(javascript:alert(2))}</style></head><body>x</body></html>""")

        assertFalse(out, out.contains("expression("))
        assertFalse(out, out.contains("javascript:"))
    }

    @Test
    fun `style attribute keeps layout but loses expression`() {
        val out = clean("""<div style="color:#ff0000;width:expression(alert(1))">x</div>""")

        assertTrue(out, out.contains("color:#ff0000"))
        assertFalse(out, out.contains("expression("))
    }

    @Test
    fun `fragment stays a fragment`() {
        val out = sanitizeEmailHtml("<p>hello</p>")

        assertEquals("<p>hello</p>", out)
    }

    @Test
    fun `table layout attributes survive`() {
        val out = clean(
            """<table width="600" cellpadding="0" cellspacing="0" border="0" bgcolor="#ffffff">""" +
                """<tr><td colspan="2" align="center" valign="top">cell</td></tr></table>"""
        )

        listOf("width=\"600\"", "cellpadding", "cellspacing", "bgcolor", "colspan", "valign").forEach {
            assertTrue("$it missing from $out", out.contains(it))
        }
    }

    @Test
    fun `https and data images survive while other schemes are dropped`() {
        val out = clean(
            """<img src="https://cdn/x.png"><img src="data:image/png;base64,AAAA">""" +
                """<img src="http://tracker/x.gif"><img src="javascript:alert(1)">"""
        )

        assertTrue(out, out.contains("https://cdn/x.png"))
        assertTrue(out, out.contains("data:image/png;base64,aaaa"))
        assertFalse(out, out.contains("http://tracker"))
        assertFalse(out, out.contains("javascript:"))
    }

    @Test
    fun `mailto and http links survive`() {
        val out = clean("""<a href="mailto:a@b.c">mail</a><a href="http://x/y">web</a>""")

        assertTrue(out, out.contains("mailto:a@b.c"))
        assertTrue(out, out.contains("http://x/y"))
    }

    @Test
    fun `quote island markup used by compose survives`() {
        // ComposeHelper wraps quoted bodies in this div; collapseDeepQuotes keys off it.
        val out = clean("""<div data-quoted-html="" class="quoted-html-island" style="border-left:3px solid #000">q</div>""")

        assertTrue(out, out.contains("data-quoted-html"))
        assertTrue(out, out.contains("quoted-html-island"))
    }

    @Test
    fun `text of unknown tags is kept`() {
        val out = clean("<weird>text</weird>")

        assertFalse(out, out.contains("<weird"))
        assertTrue(out, out.contains("text"))
    }
}
