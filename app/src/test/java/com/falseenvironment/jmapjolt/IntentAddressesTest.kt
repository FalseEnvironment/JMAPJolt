package com.falseenvironment.jmapjolt

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Recipient extras on share intents are usually String[] but senders vary: a single
 * String, a list, or several addresses separated by comma/semicolon all have to land
 * as individual chips in compose.
 */
class IntentAddressesTest {

    @Test
    fun `string array keeps each address`() {
        assertEquals(
            listOf("a@example.com", "b@example.com"),
            parseIntentAddresses(arrayOf("a@example.com", "b@example.com"))
        )
    }

    @Test
    fun `a single comma separated string is split`() {
        assertEquals(
            listOf("a@example.com", "b@example.com", "c@example.com"),
            parseIntentAddresses("a@example.com, b@example.com; c@example.com")
        )
    }

    @Test
    fun `blank entries are dropped and duplicates removed`() {
        assertEquals(
            listOf("a@example.com", "b@example.com"),
            parseIntentAddresses(arrayOf("a@example.com", "", " b@example.com ", "a@example.com"))
        )
    }

    @Test
    fun `a missing extra yields no addresses`() {
        assertEquals(emptyList<String>(), parseIntentAddresses(null))
    }

    @Test
    fun `unexpected types are ignored`() {
        assertEquals(emptyList<String>(), parseIntentAddresses(42))
    }
}
