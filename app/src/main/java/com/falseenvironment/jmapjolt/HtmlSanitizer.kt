package com.falseenvironment.jmapjolt

/**
 * Structural transforms on already-sanitised email HTML.
 *
 * Kept free of any Android or MainActivity dependency (the sanitiser proper lives
 * in [sanitizeEmailHtml]) so the rules can be exercised by plain JVM unit tests.
 */

internal fun collapseDeepQuotes(html: String, threshold: Int = 4): String {
    val tagRegex = Regex("<(/?)(div|blockquote)\\b([^>]*)>", RegexOption.IGNORE_CASE)
    // Frame per open div/blockquote: whether it is a quote container + the matching insert.
    data class Frame(val isQuote: Boolean, val collapsedRoot: Boolean)
    val stack = ArrayDeque<Frame>()
    val inserts = mutableListOf<Pair<Int, String>>()  // (index, text-to-insert)
    var quoteDepth = 0
    var collapsedActive = false  // a <details> is already open above us
    val openDetails = "<details class=\"jj-quote-collapse\"><summary>" +
        "<span class=\"jj-chev\">▸</span><span class=\"jj-fav\"></span><span class=\"jj-lbl\"></span>" +
        "</summary>"

    for (m in tagRegex.findAll(html)) {
        val closing = m.groupValues[1] == "/"
        val tag = m.groupValues[2].lowercase()
        val attrs = m.groupValues[3]
        // Self-closing (e.g. <div .../>) opens and closes nothing structural — skip.
        if (!closing && attrs.trimEnd().endsWith("/")) continue

        if (!closing) {
            val isQuote = tag == "blockquote" ||
                Regex("class\\s*=\\s*[\"'][^\"']*quoted-html-island", RegexOption.IGNORE_CASE).containsMatchIn(attrs)
            if (isQuote) quoteDepth++
            val crossesThreshold = isQuote && !collapsedActive && quoteDepth == threshold + 1
            if (crossesThreshold) {
                inserts.add(m.range.first to openDetails)
                collapsedActive = true
            }
            stack.addLast(Frame(isQuote, crossesThreshold))
        } else {
            val frame = stack.removeLastOrNull() ?: continue
            if (frame.isQuote) quoteDepth--
            if (frame.collapsedRoot) {
                inserts.add((m.range.last + 1) to "</details>")
                collapsedActive = false
            }
        }
    }
    if (inserts.isEmpty()) return html
    val sb = StringBuilder(html)
    for ((idx, text) in inserts.sortedByDescending { it.first }) sb.insert(idx, text)
    return sb.toString()
}

/**
 * Returns [html] with [insertion] placed right after the first opening `<tagName …>`
 * tag, or null when the document has no such tag.
 *
 * Inserting by `replaceFirst("<head", "<head>…")` left the original `>` of the tag
 * behind the inserted markup; the parser then treated that `>` as body text and the
 * message opened with a stray `>`.
 */
internal fun insertAfterOpeningTag(html: String, tagName: String, insertion: String): String? {
    val openTag = Regex("<${Regex.escape(tagName)}(?=[\\s/>])[^>]*>", RegexOption.IGNORE_CASE)
    val match = openTag.find(html) ?: return null
    val end = match.range.last + 1
    return html.substring(0, end) + insertion + html.substring(end)
}
