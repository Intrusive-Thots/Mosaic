package com.intrusivethots.mosaic.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureMessageTest {
    @Test
    fun outOfMemoryAndPermissionGetPlainMessages() {
        assertTrue(generationFailureMessage(OutOfMemoryError()).contains("memory"))
        assertTrue(generationFailureMessage(SecurityException()).contains("permission"))
        assertEquals("Mosaic creation failed.", generationFailureMessage(IllegalStateException(" ")))
        assertEquals("boom", generationFailureMessage(IllegalStateException("boom")))
    }

    @Test
    fun skippedCountReadsNaturally() {
        assertEquals("1 image could not be read and was skipped.", skippedImageMessage(1))
        assertEquals("3 images could not be read and were skipped.", skippedImageMessage(3))
    }
}
