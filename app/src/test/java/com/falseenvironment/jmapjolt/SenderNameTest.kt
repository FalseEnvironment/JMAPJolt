package com.falseenvironment.jmapjolt

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SenderName.clean] strips the address some senders and relays glue onto the display
 * name ("Acme 'support at acme.example'"), so the row shows just "Acme".
 */
class SenderNameTest {

    @Test
    fun `quoted obfuscated address after the name is removed`() {
        assertEquals("Acme", SenderName.clean("Acme 'support at acme.example'"))
        assertEquals("Example Shop", SenderName.clean("Example Shop 'no-reply at mail.example.com'"))
    }

    @Test
    fun `quoted or bracketed real address after the name is removed`() {
        assertEquals("Acme", SenderName.clean("Acme \"news@acme.example\""))
        assertEquals("Acme", SenderName.clean("Acme <news@acme.example>"))
        assertEquals("Acme", SenderName.clean("Acme (news@acme.example)"))
        assertEquals("Acme", SenderName.clean("Acme ‘news@acme.example’"))
    }

    @Test
    fun `quotes wrapping the whole name are dropped`() {
        assertEquals("Jane Doe", SenderName.clean("'Jane Doe'"))
        assertEquals("Jane Doe", SenderName.clean("\"Jane Doe\""))
    }

    @Test
    fun `ordinary names are left alone`() {
        assertEquals("Jane Doe", SenderName.clean("Jane Doe"))
        assertEquals("Team (Support)", SenderName.clean("Team (Support)"))
        assertEquals("O'Brien", SenderName.clean("O'Brien"))
        assertEquals("Meet at noon", SenderName.clean("Meet at noon"))
    }

    @Test
    fun `a name made only of an address becomes empty so the caller falls back`() {
        assertEquals("", SenderName.clean("'info at example.com'"))
        assertEquals("", SenderName.clean("   "))
    }
}
