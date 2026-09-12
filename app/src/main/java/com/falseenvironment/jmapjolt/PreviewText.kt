package com.falseenvironment.jmapjolt

import org.jsoup.Jsoup

// Cleanup for the one-line body preview shown in the list, the widget and notifications.
//
// Servers derive `preview` from the first body part. For a marketing HTML email that
// part starts with the <style> block or a tracking link, so the raw value reads as
// "@media only screen and (max-width:500px)", a leaked @font-face block, or
// "<https://mandrillapp.com/ecc...>" instead of the message. Stripped here, once,
// where the summary is built rather than per display surface.
internal object PreviewText {

    // Quoted/forwarded boilerplate: line-level, so it runs before the text is flattened.
    private val FORWARD_SEPARATOR = Regex("^-+\\s*(forwarded|original)\\b.*", RegexOption.IGNORE_CASE)
    private val BEGIN_FORWARD = Regex("^begin forwarded message:?\\s*$", RegexOption.IGNORE_CASE)
    private val REPLY_INTRO = Regex("^On .+wrote:\\s*$", RegexOption.IGNORE_CASE)
    private val HEADER_LINE = Regex(
        "^(from|to|cc|bcc|date|sent|subject|reply-to)\\s*:.*", RegexOption.IGNORE_CASE
    )

    private val WHITESPACE = Regex("\\s+")

    // Plain-text mail draws rules with repeated punctuation ("*****", "=====", "-----").
    // A whole line of it carries no words; a run left inline is visual noise in a
    // single-line preview. Both are dropped rather than shown.
    private val DECOR_LINE = Regex("^[*=_~+#\\-]{3,}$")
    private val DECOR_RUN = Regex("[*=_~]{3,}|-{4,}|#{3,}")

    private val CSS_COMMENT = Regex("/\\*.*?(\\*/|$)", RegexOption.DOT_MATCHES_ALL)

    // At-rules are named explicitly: a bare "@\w+" would swallow everything after an
    // email address or an @mention in real prose.
    private const val AT_RULES = "media|font-face|import|charset|supports|keyframes|namespace|page|viewport"
    private val AT_RULE_BLOCK = Regex("@(?:$AT_RULES)\\b[^{}]*\\{[^{}]*\\}?", RegexOption.IGNORE_CASE)
    private val AT_RULE_BARE = Regex("@(?:$AT_RULES)\\b[^;{}]*[;]?", RegexOption.IGNORE_CASE)

    // Selector + block together, so "body,td {font-family:...}" leaves no orphan selector.
    // A selector token is a tag, class or id with any chained .class/#id/:pseudo/[attr].
    private const val SELECTOR = "[.#]?[\\w-]+(?:[.#:][\\w-]+|\\[[^\\]]*\\])*"
    // The combinator alternative must not overlap the plain-whitespace one. Written as
    // "\\s*[,>+~\\s]\\s*" the separator can split a run of two or more spaces in
    // exponentially many ways, and on prose that never reaches the "{" the engine tries
    // all of them: a preview with double spaces hung the fetch thread indefinitely.
    private const val SELECTOR_SEPARATOR = "(?:\\s*[,>+~]\\s*|\\s)"
    private val RULE_WITH_SELECTOR =
        Regex(SELECTOR + "(?:" + SELECTOR_SEPARATOR + SELECTOR + ")*\\s*\\{[^{}]*\\}?")
    private val BRACE_BLOCK = Regex("\\{[^{}]*\\}")

    // Only known CSS properties. A generic "word: value;" also matches ordinary
    // sentences ("Meeting notes: budget approved; ...") and would eat them.
    private const val CSS_PROPS =
        "margin[\\w-]*|padding[\\w-]*|font[\\w-]*|colou?r|background[\\w-]*|width|height|" +
            "max-width|min-width|max-height|min-height|border[\\w-]*|text[\\w-]*|line-height|" +
            "display|float|clear|vertical-align|letter-spacing|word-break|white-space|" +
            "src|content|opacity|overflow[\\w-]*|position|top|right|bottom|left|z-index|" +
            "mso-[\\w-]*|-webkit-[\\w-]*|-ms-[\\w-]*"
    private val DECLARATION = Regex("\\b(?:$CSS_PROPS)\\s*:\\s*[^;{}\\n]+;?", RegexOption.IGNORE_CASE)

    private val ANGLE_URL = Regex("<https?://[^>\\s]*>?", RegexOption.IGNORE_CASE)
    private val BARE_URL = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    private val HTML_TAG = Regex("<[^>]{1,200}>")
    private val ENTITY = Regex("&(nbsp|zwnj|amp|lt|gt|quot|apos|#\\d+|#x[0-9a-fA-F]+);")

    // Still stylesheet leftovers rather than prose. No preview beats a broken one: the
    // row already shows sender and subject, so an empty third line reads as "nothing to
    // add", while "{margin:0;padding:0}" reads as a bug.
    private val CSS_RESIDUE = Regex(
        "[{}]|@(?:$AT_RULES)\\b|" +
            "\\b(font-family|max-width|min-width|line-height|mso-|-webkit-|!important)\\b",
        RegexOption.IGNORE_CASE
    )

    // Longest preview kept from a body: the row shows two lines, the rest is wasted memory
    // in the list cache.
    const val MAX_BODY_PREVIEW_CHARS = 200

    // Reply history and signatures in HTML bodies: everything a client nests under these.
    private const val HTML_QUOTE_SELECTOR =
        "head, style, script, blockquote, .gmail_quote, .quoted-html-island, " +
            "[data-quoted-html], [data-forwarded-html], #divRplyFwdMsg, .moz-cite-prefix"
    private const val HTML_BLOCK_SELECTOR = "p, div, li, tr, h1, h2, h3, h4, h5, h6, table"
    private val SIGNATURE_DELIMITER = Regex("^--\\s*$")

    /**
     * Preview rebuilt from a downloaded body, preferring the plain-text part and falling
     * back to the HTML one. Quoted history is cut before cleaning, so a reply previews
     * its new text rather than the message it answers. Empty when neither part has prose.
     */
    fun fromBody(textBody: String?, htmlBody: String?): String {
        val fromText = textBody?.takeIf { it.isNotBlank() }?.let { clean(ownLines(it)) }.orEmpty()
        val preview = fromText.ifBlank {
            htmlBody?.takeIf { it.isNotBlank() }?.let { clean(ownLines(htmlToText(it))) }.orEmpty()
        }
        return truncateAtWord(preview, MAX_BODY_PREVIEW_CHARS)
    }

    /** Lines written by the sender: stops at the reply lead-in or the signature delimiter. */
    private fun ownLines(text: String): String = text.lineSequence()
        .takeWhile { line ->
            val trimmed = line.trim()
            !REPLY_INTRO.matches(trimmed) && !SIGNATURE_DELIMITER.matches(trimmed)
        }
        .filterNot { it.trimStart().startsWith(">") }
        .joinToString("\n")

    private fun htmlToText(html: String): String {
        val body = Jsoup.parse(html).body()
        body.select(HTML_QUOTE_SELECTOR).remove()
        body.select("br").before("\n")
        body.select(HTML_BLOCK_SELECTOR).after("\n")
        return body.wholeText()
    }

    private fun truncateAtWord(text: String, max: Int): String {
        if (text.length <= max) return text
        val cut = text.lastIndexOf(' ', max).takeIf { it > max / 2 } ?: max
        return text.substring(0, cut).trimEnd()
    }

    fun clean(raw: String?): String {
        if (raw.isNullOrBlank()) return ""

        val withoutBoilerplate = raw.lineSequence()
            .map { it.trim() }
            .filterNot { line ->
                line.isEmpty() ||
                    line.startsWith(">") ||
                    DECOR_LINE.matches(line) ||
                    FORWARD_SEPARATOR.matches(line) ||
                    BEGIN_FORWARD.matches(line) ||
                    REPLY_INTRO.matches(line) ||
                    HEADER_LINE.matches(line)
            }
            .joinToString(" ")
            // Collapse whitespace before the CSS passes rather than after: every pattern
            // below treats whitespace as a separator, so a run of it multiplies the ways
            // they can match without ever changing the result.
            .replace(WHITESPACE, " ")

        // Order matters: comments can wrap braces, and a rule must go with its selector
        // before a bare brace block strips the braces out from under it.
        val cleaned = withoutBoilerplate
            .replace(CSS_COMMENT, " ")
            .replace(AT_RULE_BLOCK, " ")
            .replace(AT_RULE_BARE, " ")
            .replace(RULE_WITH_SELECTOR, " ")
            .replace(BRACE_BLOCK, " ")
            .replace(DECLARATION, " ")
            .replace(ANGLE_URL, " ")
            .replace(BARE_URL, " ")
            .replace(HTML_TAG, " ")
            .replace(ENTITY, " ")
            .replace(DECOR_RUN, " ")
            .replace(' ', ' ')
            .replace(WHITESPACE, " ")
            .trim()
            .trim(';', ':', ',', '{', '}', '*', '/', '-')
            .trim()

        // Empty beats garbage. The previous cleaner fell back to the raw string here,
        // which is why stylesheet previews survived it.
        if (cleaned.isBlank() || CSS_RESIDUE.containsMatchIn(cleaned)) return ""
        // Punctuation and symbols with no real words is not a preview.
        if (cleaned.count { it.isLetter() } < 3) return ""
        return cleaned
    }
}
