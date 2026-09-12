package com.falseenvironment.jmapjolt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JmapSseProbeUrlsTest {

    @Test
    fun `origin session url expands to the standard session paths`() {
        val urls = JmapSse.sessionProbeUrls("https://mail.example.com/")

        assertEquals(
            listOf(
                "https://mail.example.com/",
                "https://mail.example.com/.well-known/jmap",
                "https://mail.example.com/jmap/session",
                "https://mail.example.com/jmap"
            ),
            urls
        )
    }

    @Test
    fun `stored session url stays first and is not duplicated`() {
        val urls = JmapSse.sessionProbeUrls("https://mail.example.com/jmap/session")

        assertEquals("https://mail.example.com/jmap/session", urls.first())
        assertEquals(urls.size, urls.distinct().size)
    }

    @Test
    fun `non default port is kept on every candidate`() {
        val urls = JmapSse.sessionProbeUrls("https://mail.example.com:8443/")

        assertTrue(urls.all { it.startsWith("https://mail.example.com:8443/") })
    }

    @Test
    fun `unparseable session url is returned unchanged`() {
        val urls = JmapSse.sessionProbeUrls("not a url")

        assertEquals(listOf("not a url"), urls)
    }
}
