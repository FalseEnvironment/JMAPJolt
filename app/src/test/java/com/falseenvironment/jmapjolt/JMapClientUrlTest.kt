package com.falseenvironment.jmapjolt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * URL handling in [JMapClient]. Both functions gate where Basic auth credentials
 * are allowed to travel, so the negative cases matter more than the happy path.
 */
class JMapClientUrlTest {

    private val session = "https://mail.example.com/jmap/session"

    // --- isTrustedServerUrl -------------------------------------------------

    @Test
    fun `same host over https is trusted`() {
        assertTrue(JMapClient.isTrustedServerUrl("https://mail.example.com/download/blob1", session))
    }

    @Test
    fun `host comparison ignores case`() {
        assertTrue(JMapClient.isTrustedServerUrl("https://MAIL.Example.COM/upload", session))
    }

    @Test
    fun `plain http is rejected even on the same host`() {
        assertFalse(JMapClient.isTrustedServerUrl("http://mail.example.com/download", session))
    }

    @Test
    fun `another host is rejected`() {
        assertFalse(JMapClient.isTrustedServerUrl("https://evil.example.com/download", session))
    }

    @Test
    fun `a host that merely ends with the session host is rejected`() {
        assertFalse(JMapClient.isTrustedServerUrl("https://notmail.example.com/download", session))
    }

    @Test
    fun `another port on the same host is rejected`() {
        assertFalse(JMapClient.isTrustedServerUrl("https://mail.example.com:8443/download", session))
    }

    @Test
    fun `explicit default port still matches the implicit one`() {
        assertTrue(JMapClient.isTrustedServerUrl("https://mail.example.com:443/download", session))
    }

    @Test
    fun `unparseable url is rejected`() {
        assertFalse(JMapClient.isTrustedServerUrl("not a url", session))
    }

    @Test
    fun `unparseable session url rejects everything`() {
        assertFalse(JMapClient.isTrustedServerUrl("https://mail.example.com/x", "garbage"))
    }

    // --- buildSessionCandidates --------------------------------------------

    private val expectedForExampleCom = listOf(
        "https://mail.example.com/",
        "https://mail.example.com/.well-known/jmap",
        "https://mail.example.com/jmap",
        "https://mail.example.com/jmap/session"
    )

    @Test
    fun `bare host expands to the four candidates in order`() {
        assertEquals(expectedForExampleCom, JMapClient.buildSessionCandidates("mail.example.com"))
    }

    @Test
    fun `http input is upgraded to https`() {
        assertEquals(expectedForExampleCom, JMapClient.buildSessionCandidates("http://mail.example.com"))
    }

    @Test
    fun `https input is kept`() {
        assertEquals(expectedForExampleCom, JMapClient.buildSessionCandidates("https://mail.example.com"))
    }

    @Test
    fun `trailing jmap path is stripped`() {
        assertEquals(expectedForExampleCom, JMapClient.buildSessionCandidates("https://mail.example.com/jmap"))
    }

    @Test
    fun `trailing jmap session path is stripped`() {
        assertEquals(
            expectedForExampleCom,
            JMapClient.buildSessionCandidates("https://mail.example.com/jmap/session")
        )
    }

    @Test
    fun `surrounding whitespace and a trailing slash are ignored`() {
        assertEquals(expectedForExampleCom, JMapClient.buildSessionCandidates("  https://mail.example.com/  "))
    }

    @Test
    fun `scheme match is case insensitive`() {
        assertEquals(expectedForExampleCom, JMapClient.buildSessionCandidates("HTTP://mail.example.com"))
    }

    @Test
    fun `a non-default port is preserved on every candidate`() {
        assertEquals(
            listOf(
                "https://mail.example.com:8443/",
                "https://mail.example.com:8443/.well-known/jmap",
                "https://mail.example.com:8443/jmap",
                "https://mail.example.com:8443/jmap/session"
            ),
            JMapClient.buildSessionCandidates("mail.example.com:8443")
        )
    }

    @Test
    fun `blank input yields no candidates`() {
        assertTrue(JMapClient.buildSessionCandidates("").isEmpty())
        assertTrue(JMapClient.buildSessionCandidates("   ").isEmpty())
    }

    @Test
    fun `structurally invalid input yields no candidates`() {
        assertTrue(JMapClient.buildSessionCandidates("http://").isEmpty())
        assertTrue(JMapClient.buildSessionCandidates("https://").isEmpty())
        assertTrue(JMapClient.buildSessionCandidates("://nope").isEmpty())
        assertTrue(JMapClient.buildSessionCandidates("/").isEmpty())
    }

    @Test
    fun `a non http scheme is refused rather than coerced to https`() {
        assertTrue(JMapClient.buildSessionCandidates("ftp://mail.example.com").isEmpty())
        assertTrue(JMapClient.buildSessionCandidates("javascript://mail.example.com").isEmpty())
    }
}
