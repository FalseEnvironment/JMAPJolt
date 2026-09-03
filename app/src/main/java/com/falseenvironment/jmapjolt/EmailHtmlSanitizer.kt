package com.falseenvironment.jmapjolt

import org.jsoup.Jsoup
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/**
 * HTML sanitizer for message bodies rendered in the detail WebView and quoted into
 * replies/forwards.
 *
 * Parser-based on purpose: the previous implementation was a chain of regexes over the raw
 * string, which missed case variants (`ONCLICK=`) and could be defeated by markup that
 * reassembles a stripped tag (`<scr<script>ipt>`). Jsoup tokenizes the document the way a
 * browser does, so an element either survives the allowlist or it does not — there is no
 * "one more pass" left to trick.
 *
 * JavaScript is disabled on the WebView, so this is defense in depth; it also protects the
 * quote islands that ComposeHelper embeds into outgoing mail.
 *
 * A `Safelist`-based `Jsoup.clean` is not used because it drops the document shell and
 * `<style>` blocks, which email layouts depend on and which `buildHtmlContent` patches to
 * inject the theme CSS.
 */
private val ALLOWED_TAGS = setOf(
    "html", "head", "body", "style", "title",
    "a", "abbr", "address", "article", "aside", "b", "bdi", "bdo", "big", "blockquote",
    "br", "caption", "center", "cite", "code", "col", "colgroup", "dd", "del", "details",
    "dfn", "div", "dl", "dt", "em", "figcaption", "figure", "font", "footer", "h1", "h2",
    "h3", "h4", "h5", "h6", "header", "hgroup", "hr", "i", "img", "ins", "kbd", "li",
    "main", "mark", "nav", "ol", "p", "pre", "q", "s", "samp", "section", "small", "span",
    "strike", "strong", "sub", "summary", "sup", "table", "tbody", "td", "tfoot", "th",
    "thead", "time", "tr", "tt", "u", "ul", "var", "wbr",
)

/** Tags dropped together with their subtree: their content is code or markup, not prose. */
private val DROPPED_WITH_CONTENT = setOf(
    "script", "noscript", "iframe", "object", "embed", "applet", "frame", "frameset",
    "base", "link", "meta", "form", "input", "select", "option", "optgroup", "textarea",
    "button", "fieldset", "legend", "label", "svg", "math", "template", "canvas", "audio",
    "video", "source", "track", "map", "area", "param", "dialog", "menu", "portal",
)

/** Attributes allowed on every element. */
private val GLOBAL_ATTRS = setOf("style", "class", "id", "dir", "lang", "title", "role")

/** Extra attributes allowed per tag. Table layout attributes are what email HTML lives on. */
private val TAG_ATTRS = mapOf(
    "a" to setOf("href", "name", "target"),
    "img" to setOf("src", "alt", "width", "height", "border", "hspace", "vspace", "align"),
    "font" to setOf("color", "face", "size"),
    "table" to setOf("width", "height", "border", "cellpadding", "cellspacing", "align", "bgcolor", "background", "summary"),
    "col" to setOf("width", "span", "align", "valign"),
    "colgroup" to setOf("width", "span", "align", "valign"),
    "tbody" to setOf("align", "valign", "bgcolor"),
    "thead" to setOf("align", "valign", "bgcolor"),
    "tfoot" to setOf("align", "valign", "bgcolor"),
    "tr" to setOf("align", "valign", "bgcolor", "height"),
    "td" to setOf("colspan", "rowspan", "width", "height", "align", "valign", "bgcolor", "background", "nowrap"),
    "th" to setOf("colspan", "rowspan", "width", "height", "align", "valign", "bgcolor", "background", "nowrap", "scope"),
    "ol" to setOf("start", "type"),
    "ul" to setOf("type"),
    "li" to setOf("value", "type"),
    "hr" to setOf("width", "size", "noshade", "align"),
    "div" to setOf("align", "data-quoted-html", "data-forwarded-html"),
    "p" to setOf("align"),
    "blockquote" to setOf("cite", "type"),
    "details" to setOf("open"),
    "del" to setOf("cite", "datetime"),
    "ins" to setOf("cite", "datetime"),
    "time" to setOf("datetime"),
)

/** Schemes a navigable link may carry. */
private val LINK_SCHEMES = setOf("https", "http", "mailto")

/** Schemes an embedded image may carry: remote images over TLS, or inline payloads. */
private val IMAGE_SCHEMES = setOf("https", "data")

private val UNSAFE_CSS = Regex("""expression\s*\(|(javascript|vbscript|data)\s*:""", RegexOption.IGNORE_CASE)

/**
 * Returns [html] with every script vector removed. A full document stays a full document
 * (so `buildHtmlContent` can keep patching `<head>`); a fragment stays a fragment.
 */
internal fun sanitizeEmailHtml(html: String): String {
    val doc = Jsoup.parse(html, "", Parser.htmlParser())
    doc.outputSettings().prettyPrint(false)
    sanitizeNode(doc.root())
    val isFullDoc = html.contains("<html", ignoreCase = true)
    return if (isFullDoc) doc.outerHtml() else doc.body().html()
}

/** Depth-first cleanup. Children are snapshotted because the walk rewrites the tree. */
private fun sanitizeNode(element: Element) {
    for (child in ArrayList(element.children())) sanitizeNode(child)

    val tag = element.tagName().lowercase()
    if (tag == "#root" || tag == "html" || tag == "head" || tag == "body") {
        sanitizeAttributes(element, tag)
        return
    }
    if (tag in DROPPED_WITH_CONTENT) {
        element.remove()
        return
    }
    if (tag !in ALLOWED_TAGS) {
        // Unknown but inert — including the fragments left by markup that tried to
        // reassemble a stripped tag. Keep the text, drop the element.
        element.unwrap()
        return
    }
    if (tag == "style") {
        sanitizeStyleBlock(element)
        return
    }
    sanitizeAttributes(element, tag)
}

private fun sanitizeAttributes(element: Element, tag: String) {
    val allowed = GLOBAL_ATTRS + (TAG_ATTRS[tag] ?: emptySet())
    for (attr in ArrayList(element.attributes().asList())) {
        val key = attr.key.lowercase()
        val value = attr.value
        when {
            // Catches ONCLICK, oNcLiCk and every other casing: the comparison runs on the
            // lowercased attribute name, never on the raw source text.
            key.startsWith("on") || key !in allowed -> element.removeAttr(attr.key)
            key == "style" && UNSAFE_CSS.containsMatchIn(value) ->
                element.attr(attr.key, UNSAFE_CSS.replace(value, ""))
            key == "href" && !hasAllowedScheme(value, LINK_SCHEMES) -> element.attr(attr.key, "#")
            key == "src" && !hasAllowedScheme(value, IMAGE_SCHEMES) -> element.removeAttr(attr.key)
            key == "background" && !hasAllowedScheme(value, IMAGE_SCHEMES) -> element.removeAttr(attr.key)
        }
    }
}

/**
 * `<style>` content survives — emails depend on it — but is scrubbed of the CSS constructs
 * that can execute or phone out: `expression()` and script-bearing URL schemes.
 */
private fun sanitizeStyleBlock(element: Element) {
    val css = element.data()
    if (!UNSAFE_CSS.containsMatchIn(css)) return
    val cleaned = UNSAFE_CSS.replace(css, "")
    element.empty()
    element.appendChild(DataNode(cleaned))
}

/**
 * A URL is kept only when its scheme is explicitly allowed. Relative and scheme-relative
 * (`//host/x`) URLs carry no scheme of their own and are left alone: the WebView loads the
 * body with a null base, so they resolve to nothing.
 */
private fun hasAllowedScheme(url: String, allowed: Set<String>): Boolean {
    // Whitespace (including the tab/newline tricks browsers tolerate inside a URL) is
    // removed before the scheme is read, so "java\tscript:x" cannot hide its scheme.
    val value = url.trim().filterNot { it.isWhitespace() }
    val colon = value.indexOf(':')
    if (colon < 0) return true
    val slash = value.indexOf('/')
    if (slash in 0 until colon) return true  // "path:with:colon/..." is a relative path
    return value.substring(0, colon).lowercase() in allowed
}
