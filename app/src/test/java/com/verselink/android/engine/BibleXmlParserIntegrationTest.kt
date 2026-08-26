package com.verselink.android.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.File
import java.io.FileInputStream
import java.util.TreeMap

/**
 * Runs the production parse loop (BibleXmlParser) over the REAL bundled
 * KJV.xml with the same pull-parser implementation used on-device.
 * Catches format/encoding/structure issues that map-based fakes cannot.
 */
class BibleXmlParserIntegrationTest {

    private fun loadRealKjv(): LinkedHashMap<String, TreeMap<Int, TreeMap<Int, String>>> {
        // Path relative to the app module - where Gradle unit tests run.
        val file = File("src/main/assets/bibles/KJV.xml")
        assumeTrue("KJV.xml not found at ${file.absolutePath}", file.exists())
        val parser = KXmlParser()
        FileInputStream(file).use { input ->
            BibleXmlParser.configure(parser, input)
            return BibleXmlParser.parse(parser)
        }
    }

    @Test fun `parses all 66 books from real KJV`() {
        val books = loadRealKjv()
        assertEquals(66, books.size)
        assertEquals(listOf("Genesis", "Exodus", "Leviticus"),
            books.keys.take(3))
        assertEquals("Revelation", books.keys.last())
    }

    @Test fun `john 3 16 resolves from real data`() {
        val books = loadRealKjv()
        val text = books["John"]?.get(3)?.get(16)
        assertNotNull(text)
        assertTrue(text!!.startsWith("For God so loved the world"))
    }

    @Test fun `psalms 119 has full verse count`() {
        val books = loadRealKjv()
        assertEquals(176, books["Psalms"]?.get(119)?.size)
    }

    private fun assertTrue(b: Boolean) {
        org.junit.Assert.assertTrue(b)
    }
}
