package com.falseenvironment.jmapjolt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [collapseDeepQuotes] folds a reply chain deeper than the threshold into one
 * <details> block. The invariants that matter: nothing is collapsed below the
 * threshold, exactly one wrapper opens per chain, and the markup stays balanced.
 */
class CollapseDeepQuotesTest {

    private val open = "<details class=\"jj-quote-collapse\">"

    private fun nestedQuotes(depth: Int, inner: String = "text"): String =
        "<blockquote>".repeat(depth) + inner + "</blockquote>".repeat(depth)

    @Test
    fun `html without quotes is returned untouched`() {
        val html = "<div><p>hello</p></div>"
        assertEquals(html, collapseDeepQuotes(html))
    }

    @Test
    fun `quotes at the threshold are not collapsed`() {
        val html = nestedQuotes(4)
        assertEquals(html, collapseDeepQuotes(html))
    }

    @Test
    fun `quotes past the threshold are wrapped in a details block`() {
        val out = collapseDeepQuotes(nestedQuotes(5))
        assertTrue("expected a collapse wrapper in: $out", out.contains(open))
        assertEquals(1, countOf(out, open))
        assertEquals(1, countOf(out, "</details>"))
    }

    @Test
    fun `the wrapper opens at the fifth quote, not before`() {
        val out = collapseDeepQuotes(nestedQuotes(5, inner = "deep"))
        // Four blockquotes precede the wrapper; the fifth is inside it.
        val beforeWrapper = out.substringBefore(open)
        assertEquals(4, countOf(beforeWrapper, "<blockquote>"))
        assertTrue(out.substringAfter(open).contains("deep"))
    }

    @Test
    fun `a very deep chain still produces a single wrapper`() {
        val out = collapseDeepQuotes(nestedQuotes(9))
        assertEquals(1, countOf(out, open))
        assertEquals(1, countOf(out, "</details>"))
    }

    @Test
    fun `the details block closes with its quote`() {
        val out = collapseDeepQuotes(nestedQuotes(5) + "<p>after</p>")
        // The trailing sibling stays outside the collapsed region.
        assertTrue(out.endsWith("<p>after</p>"))
        assertTrue(out.indexOf("</details>") < out.indexOf("<p>after</p>"))
    }

    @Test
    fun `quoted-html-island divs count towards the depth`() {
        val html = "<div class=\"quoted-html-island\">".repeat(5) + "x" + "</div>".repeat(5)
        val out = collapseDeepQuotes(html)
        assertEquals(1, countOf(out, open))
        assertEquals(1, countOf(out, "</details>"))
    }

    @Test
    fun `plain divs do not count towards the depth`() {
        val html = "<div>".repeat(8) + "x" + "</div>".repeat(8)
        assertEquals(html, collapseDeepQuotes(html))
    }

    @Test
    fun `self closing div does not open a frame`() {
        val html = "<div/>" + nestedQuotes(4)
        assertEquals(html, collapseDeepQuotes(html))
    }

    @Test
    fun `sibling chains are collapsed independently`() {
        val out = collapseDeepQuotes(nestedQuotes(5) + nestedQuotes(5))
        assertEquals(2, countOf(out, open))
        assertEquals(2, countOf(out, "</details>"))
    }

    @Test
    fun `a lower threshold collapses earlier`() {
        val out = collapseDeepQuotes(nestedQuotes(3), threshold = 2)
        assertEquals(1, countOf(out, open))
        assertEquals(2, countOf(out.substringBefore(open), "<blockquote>"))
    }

    @Test
    fun `unbalanced closing tags do not throw`() {
        val out = collapseDeepQuotes("</blockquote></div>" + nestedQuotes(5))
        assertEquals(1, countOf(out, open))
    }

    private fun countOf(haystack: String, needle: String): Int {
        var count = 0
        var i = haystack.indexOf(needle)
        while (i >= 0) {
            count++
            i = haystack.indexOf(needle, i + needle.length)
        }
        return count
    }
}
