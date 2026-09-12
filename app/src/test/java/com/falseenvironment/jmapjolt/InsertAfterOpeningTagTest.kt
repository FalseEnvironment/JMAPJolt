package com.falseenvironment.jmapjolt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [insertAfterOpeningTag] is how the detail view patches a full email document. The
 * previous `replaceFirst("<head", "<head><meta …>")` kept the original `>` of `<head>`
 * after the inserted tag, and the WebView rendered that stray `>` as the first
 * character of the message.
 */
class InsertAfterOpeningTagTest {

    private val meta = "<meta name=\"viewport\">"

    @Test
    fun `insertion lands after the whole opening tag without a stray bracket`() {
        val html = "<html><head><title>x</title></head><body>hi</body></html>"
        val out = insertAfterOpeningTag(html, "head", meta)
        assertEquals("<html><head>$meta<title>x</title></head><body>hi</body></html>", out)
        assertFalse(requireNotNull(out).contains(">>"))
    }

    @Test
    fun `attributes on the opening tag are preserved`() {
        val html = "<body class=\"a\" style=\"margin:0\">text</body>"
        assertEquals(
            "<body class=\"a\" style=\"margin:0\">[S]text</body>",
            insertAfterOpeningTag(html, "body", "[S]")
        )
    }

    @Test
    fun `tag match is case insensitive`() {
        assertEquals("<HEAD>[M]</HEAD>", insertAfterOpeningTag("<HEAD></HEAD>", "head", "[M]"))
    }

    @Test
    fun `a longer tag name sharing the prefix is not matched`() {
        val html = "<header>top</header><head></head>"
        assertEquals("<header>top</header><head>[M]</head>", insertAfterOpeningTag(html, "head", "[M]"))
    }

    @Test
    fun `missing tag returns null so the caller can choose a fallback`() {
        assertEquals(null, insertAfterOpeningTag("<div>no head</div>", "head", "[M]"))
    }
}
