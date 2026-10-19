package com.falseenvironment.jmapjolt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SafeAttachmentFileNameTest {

    @Test
    fun keepsPlainNames() {
        assertEquals("video.mp4", safeAttachmentFileName("video.mp4"))
    }

    @Test
    fun stripsPathSeparatorsAndTraversal() {
        val name = safeAttachmentFileName("../../etc/passwd")
        assertFalse(name.contains('/'))
        assertFalse(name.contains('\\'))
        assertEquals(".._.._etc_passwd", name)
    }

    @Test
    fun blanksFallBackToAttachment() {
        assertEquals("attachment", safeAttachmentFileName("   "))
    }

    @Test
    fun truncatesLongNamesKeepingTheExtension() {
        val long = "a".repeat(300) + ".mp4"
        val name = safeAttachmentFileName(long)
        assertEquals(120, name.length)
        assertEquals(".mp4", name.takeLast(4))
    }
}
