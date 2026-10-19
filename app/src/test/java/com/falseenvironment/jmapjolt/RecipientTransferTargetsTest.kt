package com.falseenvironment.jmapjolt

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The chip menu never offers the category the address already sits in: a To chip
 * can only move to Cc/Bcc, and so on.
 */
class RecipientTransferTargetsTest {

    @Test
    fun `to chip offers cc and bcc`() {
        assertEquals(listOf(1, 2), recipientTransferTargets(0))
    }

    @Test
    fun `cc chip offers to and bcc`() {
        assertEquals(listOf(0, 2), recipientTransferTargets(1))
    }

    @Test
    fun `bcc chip offers to and cc`() {
        assertEquals(listOf(0, 1), recipientTransferTargets(2))
    }

    @Test
    fun `current category is never a destination`() {
        for (category in 0..2) {
            assertEquals(false, recipientTransferTargets(category).contains(category))
        }
    }
}
