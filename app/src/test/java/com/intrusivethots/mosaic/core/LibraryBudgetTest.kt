package com.intrusivethots.mosaic.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryBudgetTest {
    @Test
    fun oneHundredFiftyOneStickersStayInsideTheBudget() {
        val edge = collageLibraryEdge(151)
        val bytes = 151L * edge * edge * 4L
        assertTrue("edge $edge uses $bytes bytes", bytes <= 36L * 1024 * 1024)
        assertTrue(edge >= 128)
    }

    @Test
    fun aSmallLibraryStaysSharperThanAThumbnail() {
        assertEquals(480, collageLibraryEdge(1))
        assertEquals(480, collageLibraryEdge(8))
        assertTrue(collageLibraryEdge(80) in 129..479)
    }

    @Test
    fun etaPhrasesStayShort() {
        assertEquals("A few seconds left", etaPhrase(3_000))
        assertEquals("About 20 seconds left", etaPhrase(19_200))
        assertEquals("About 1 minute left", etaPhrase(70_000))
        assertEquals("About 3 minutes left", etaPhrase(150_000))
    }
}
