package com.falseenvironment.jmapjolt

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [FaviconRepository.getRootDomain] decides which domain the favicon is looked up
 * under and which cache entry it shares, so subdomains of one company must collapse
 * to the same key without collapsing two different registrable domains together.
 */
class FaviconRootDomainTest {

    @Test
    fun `plain registrable domain is returned unchanged`() {
        assertEquals("example.com", FaviconRepository.getRootDomain("example.com"))
    }

    @Test
    fun `single subdomain is dropped`() {
        assertEquals("example.com", FaviconRepository.getRootDomain("mail.example.com"))
    }

    @Test
    fun `several subdomains are dropped`() {
        assertEquals("example.com", FaviconRepository.getRootDomain("a.b.example.com"))
    }

    @Test
    fun `multi part tld keeps the registrable label`() {
        assertEquals("example.co.uk", FaviconRepository.getRootDomain("mail.example.co.uk"))
    }

    @Test
    fun `multi part tld with several subdomains keeps the registrable label`() {
        assertEquals("example.co.uk", FaviconRepository.getRootDomain("a.b.example.co.uk"))
    }

    @Test
    fun `bare multi part tld is left alone`() {
        assertEquals("co.uk", FaviconRepository.getRootDomain("co.uk"))
    }

    @Test
    fun `a two label domain that is not a multi part tld is kept whole`() {
        assertEquals("example.it", FaviconRepository.getRootDomain("example.it"))
    }

    @Test
    fun `com au is treated as a multi part tld`() {
        assertEquals("example.com.au", FaviconRepository.getRootDomain("shop.example.com.au"))
    }

    @Test
    fun `single label input is returned unchanged`() {
        assertEquals("localhost", FaviconRepository.getRootDomain("localhost"))
    }
}
