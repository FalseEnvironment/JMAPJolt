package com.falseenvironment.jmapjolt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [resolveWidgetSelection] decides what an inbox widget renders. The unified inbox only
 * exists with two or more accounts: a widget saved as unified falls back to the single
 * remaining account instead of showing a one-account "Unified inbox".
 */
class WidgetSelectionTest {

    private val one = listOf("a@example.com")
    private val two = listOf("a@example.com", "b@example.com")

    @Test
    fun `no accounts renders nothing`() {
        assertNull(resolveWidgetSelection(saved = null, accounts = emptyList()))
        assertNull(resolveWidgetSelection(saved = WidgetSupport.UNIFIED, accounts = emptyList()))
    }

    @Test
    fun `unconfigured widget picks the only account or the unified inbox`() {
        assertEquals("a@example.com", resolveWidgetSelection(saved = null, accounts = one))
        assertEquals(WidgetSupport.UNIFIED, resolveWidgetSelection(saved = null, accounts = two))
    }

    @Test
    fun `unified selection with a single account falls back to that account`() {
        assertEquals("a@example.com", resolveWidgetSelection(saved = WidgetSupport.UNIFIED, accounts = one))
    }

    @Test
    fun `unified selection with several accounts stays unified`() {
        assertEquals(WidgetSupport.UNIFIED, resolveWidgetSelection(saved = WidgetSupport.UNIFIED, accounts = two))
    }

    @Test
    fun `explicit account selection is kept`() {
        assertEquals("b@example.com", resolveWidgetSelection(saved = "b@example.com", accounts = two))
    }

    @Test
    fun `account strips only show in a unified inbox of several accounts`() {
        assertTrue(showsAccountStrips(WidgetSupport.UNIFIED, accountCount = 2))
        assertFalse(showsAccountStrips(WidgetSupport.UNIFIED, accountCount = 1))
        assertFalse(showsAccountStrips("a@example.com", accountCount = 2))
    }
}
