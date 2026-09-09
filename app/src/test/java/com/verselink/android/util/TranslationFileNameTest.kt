package com.verselink.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The import filename comes from a content provider - another app - and is
 * used as a path component under filesDir/bibles, so these rules are the
 * boundary check for that input.
 */
class TranslationFileNameTest {

    @Test fun `ordinary name passes through`() {
        assertEquals("ASV.xml", TranslationFileName.sanitise("ASV.xml"))
    }

    @Test fun `missing extension is added`() {
        assertEquals("ASV.xml", TranslationFileName.sanitise("ASV"))
        assertEquals("My Bible.xml", TranslationFileName.sanitise("My Bible"))
    }

    @Test fun `existing extension is not doubled`() {
        assertEquals("asv.XML", TranslationFileName.sanitise("asv.XML"))
    }

    @Test fun `path separators cannot survive`() {
        val name = TranslationFileName.sanitise("../../shared_prefs/verselink_settings.xml")
        assertFalse(name.contains("/"))
        assertFalse(name.contains("\\"))
        assertFalse(name.startsWith("."))
        assertEquals("verselink_settings.xml", name)
    }

    @Test fun `windows separators cannot survive`() {
        assertEquals("evil.xml", TranslationFileName.sanitise("..\\..\\evil.xml"))
    }

    @Test fun `traversal only name falls back`() {
        val name = TranslationFileName.sanitise("../..")
        assertTrue(name, name.startsWith("IMPORTED_"))
        assertTrue(name, name.endsWith(".xml"))
    }

    @Test fun `null and blank fall back`() {
        assertTrue(TranslationFileName.sanitise(null).startsWith("IMPORTED_"))
        assertTrue(TranslationFileName.sanitise("   ").startsWith("IMPORTED_"))
    }

    @Test fun `exotic characters are stripped`() {
        assertEquals("KJV 2.xml", TranslationFileName.sanitise("KJV (2)*?.xml"))
    }

    @Test fun `name free of collisions is untouched`() {
        assertEquals("ASV.xml", TranslationFileName.disambiguate("ASV.xml", listOf("KJV.xml")))
    }

    @Test fun `collision with a bundled asset is renamed`() {
        assertEquals("KJV-2.xml", TranslationFileName.disambiguate("KJV.xml", listOf("KJV.xml")))
    }

    @Test fun `renaming keeps counting past taken suffixes`() {
        assertEquals(
            "KJV-4.xml",
            TranslationFileName.disambiguate("KJV.xml", listOf("KJV.xml", "KJV-2.xml", "KJV-3.xml"))
        )
    }
}
