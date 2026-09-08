package com.verselink.android.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser + lookup + formatting tests. Runs on the JVM via a tiny in-memory
 * Bible that mirrors KJV structure (see manual device checklist in README for
 * integration testing).
 */
class ReferenceParserTest {

    private class MapProvider : BibleTextProvider {
        val data = mapOf(
            "Genesis" to mapOf(
                1 to mapOf(1 to "In the beginning God created the heaven and the earth."),
                50 to mapOf(26 to "So Joseph died, being an hundred and ten years old.")
            ),
            "Exodus" to mapOf(1 to mapOf(1 to "Now these are the names of the children of Israel...")),
            "Psalms" to mapOf(
                23 to mapOf(1 to "The LORD is my shepherd; I shall not want."),
                119 to (1..176).associateWith { v -> "Aleph verse $v." }
            ),
            "Jonah" to mapOf(1 to mapOf(1 to "Now the word of the LORD came unto Jonah...")),
            "Micah" to mapOf(1 to mapOf(1 to "The word of the LORD that came to Micah...")),
            "Matthew" to mapOf(2 to mapOf(1 to "Now when Jesus was born in Bethlehem...")),
            "Luke" to mapOf(4 to mapOf(1 to "And Jesus being full of the Holy Ghost returned...", 2 to "And he went about...")),
            "John" to mapOf(
                3 to mapOf(
                    16 to "For God so loved the world, that he gave his only begotten Son...",
                    17 to "For God sent not his Son into the world to condemn the world...",
                    18 to "He that believeth on him is not condemned...",
                    20 to "For every one that doeth evil hateth the light..."
                )
            ),
            "Romans" to mapOf(
                8 to mapOf(28 to "And we know that all things work together for good...", 30 to "Moreover whom he did predestinate..."),
                9 to mapOf(1 to "I say the truth in Christ, I lie not...")
            ),
            "1 Peter" to mapOf(1 to mapOf(3 to "Blessed be the God and Father of our Lord Jesus Christ...", 5 to "Who are kept by the power of God...", 7 to "That the trial of your faith..."))
        )

        override fun verseText(book: String, chapter: Int, verse: Int): String? =
            data[book]?.get(chapter)?.get(verse)

        override fun maxVerse(book: String, chapter: Int): Int? =
            data[book]?.get(chapter)?.keys?.maxOrNull()

        override fun maxChapter(book: String): Int? =
            data[book]?.keys?.maxOrNull()
    }

    /** Counts provider hits so range iteration bounds can be asserted. */
    private class CountingProvider(private val inner: BibleTextProvider) : BibleTextProvider {
        var verseLookups = 0
            private set

        override fun verseText(book: String, chapter: Int, verse: Int): String? {
            verseLookups++
            return inner.verseText(book, chapter, verse)
        }

        override fun maxVerse(book: String, chapter: Int): Int? = inner.maxVerse(book, chapter)

        override fun maxChapter(book: String): Int? = inner.maxChapter(book)
    }

    private val engine = VerselinkBibleEngine(MapProvider())

    // ---------- Parsing: single formats ----------

    @Test fun `single verse`() {
        val ref = engine.tryParseReference("John 3:16")
        assertEquals("John", ref!!.book)
        assertEquals(Triple(3, 16, ReferenceType.SINGLE_VERSE),
            Triple(ref.chapter, ref.verseStart, ref.type))
    }

    @Test fun `abbreviation jn resolves`() {
        assertEquals("John", engine.tryParseReference("jn 3:16")!!.book)
    }

    @Test fun `numbered book lowercase`() {
        val ref = engine.tryParseReference("1 peter 1:3")
        assertEquals("1 Peter", ref!!.book)
        assertEquals(ReferenceType.SINGLE_VERSE, ref.type)
    }

    @Test fun `verse range same chapter`() {
        val ref = engine.tryParseReference("John 3:16-18")
        assertEquals(ReferenceType.VERSE_RANGE, ref!!.type)
        assertEquals(16, ref.verseStart)
        assertEquals(18, ref.verseEnd)
    }

    @Test fun `verse range en dash`() {
        val ref = engine.tryParseReference("Jn 3:16–18")
        assertEquals("John", ref!!.book)
        assertEquals(18, ref.verseEnd)
    }

    @Test fun `verse range em dash with spaces`() {
        val ref = engine.tryParseReference("John 3:16 — 17")
        assertEquals(ReferenceType.VERSE_RANGE, ref!!.type)
        assertEquals(17, ref.verseEnd)
    }

    @Test fun `cross chapter verse range`() {
        val ref = engine.tryParseReference("Romans 8:28-9:1")
        assertEquals(ReferenceType.VERSE_RANGE, ref!!.type)
        assertEquals(8, ref.chapter)
        assertEquals(28, ref.verseStart)
        assertEquals(9, ref.endChapter)
        assertEquals(1, ref.verseEnd)
    }

    @Test fun `cross chapter range with repeated book`() {
        val ref = engine.tryParseReference("Romans 8:28 - Romans 9:1")
        assertEquals(ReferenceType.VERSE_RANGE, ref!!.type)
        assertEquals("Romans", ref.endBook)
        assertEquals(9, ref.endChapter)
    }

    @Test fun `chapter range`() {
        val ref = engine.tryParseReference("John 1-2")
        assertEquals(ReferenceType.CHAPTER_RANGE, ref!!.type)
        assertEquals(1, ref.chapter)
        assertEquals(2, ref.endChapter)
    }

    @Test fun `chapter only`() {
        val ref = engine.tryParseReference("Psalm 23")
        assertEquals(ReferenceType.CHAPTER_ONLY, ref!!.type)
        assertEquals("Psalms", ref.book) // alias psalm -> Psalms
    }

    @Test fun `multiple verses comma list`() {
        val ref = engine.tryParseReference("John 3:16,18,20")
        assertEquals(ReferenceType.MULTIPLE_VERSES, ref!!.type)
        assertEquals(listOf(16, 18, 20), ref.verseList)
    }

    @Test fun `numbered book comma list`() {
        val ref = engine.tryParseReference("1 Peter 1:3,5,7")
        assertEquals(listOf(3, 5, 7), ref!!.verseList)
    }

    @Test fun `book range without chapters`() {
        val ref = engine.tryParseReference("Genesis - Exodus")
        assertEquals(ReferenceType.BOOK_RANGE, ref!!.type)
        assertEquals("Exodus", ref.endBook)
    }

    @Test fun `book range with chapters`() {
        val ref = engine.tryParseReference("Jonah 1 - Micah 1")
        assertEquals(ReferenceType.BOOK_RANGE, ref!!.type)
        assertEquals("Micah", ref.endBook)
        assertEquals(1, ref.endChapter)
    }

    // ---------- Parsing: multi-reference selections ----------

    @Test fun `semicolon separated references`() {
        val refs = engine.tryParseReferences("John 3:16; Romans 8:28")
        assertEquals(2, refs.size)
        assertEquals("John", refs[0].book)
        assertEquals("Romans", refs[1].book)
    }

    @Test fun `and separated references`() {
        val refs = engine.tryParseReferences("John 3:16 and Romans 8:28")
        assertEquals(2, refs.size)
    }

    @Test fun `partial success keeps valid fragments`() {
        val refs = engine.tryParseReferences("John 3:16; Notabook 2:1")
        assertEquals(1, refs.size)
        assertEquals("John", refs[0].book)
    }

    // ---------- Negative cases: menu must NOT show VerseLink ----------

    @Test fun `non references rejected`() {
        assertNull(engine.tryParseReference(""))
        assertNull(engine.tryParseReference("   "))
        assertNull(engine.tryParseReference("Call me at 3:16 pm"))
        assertNull(engine.tryParseReference("Meeting room 5 at 10:00"))
        assertNull(engine.tryParseReference("Psalm 119 is a long chapter")) // trailing words break match
        assertNull(engine.tryParseReference("Foo bar 3:16")) // unknown book
        assertNull(engine.tryParseReference("16:9 widescreen"))
    }

    // ---------- Lookup & formatting ----------

    @Test fun `lookup single verse default format`() {
        val out = engine.getReplacementText(engine.tryParseReferences("John 3:16"))
        assertEquals(
            "John 3:16 For God so loved the world, that he gave his only begotten Son...",
            out!!.trim()
        )
    }

    @Test fun `range joins verses with space`() {
        val out = engine.getReplacementText(engine.tryParseReferences("John 3:16-17"))
        assertTrue(out!!.contains("begotten Son... For God sent not"))
    }

    @Test fun `cross chapter assembles both sides`() {
        val out = engine.getReplacementText(engine.tryParseReferences("Romans 8:28-9:1"))
        assertTrue(out!!.contains("work together for good"))
        assertTrue(out!!.contains("I say the truth in Christ"))
    }

    @Test fun `comma list skips missing verses gracefully`() {
        // 19 does not exist in the fake data; output must contain 16,18,20 only.
        val out = engine.getReplacementText(engine.tryParseReferences("John 3:16,19,18,20"))
        assertTrue(out!!.contains("believeth"))
        assertTrue(!out.contains("null"))
    }

    @Test fun `unknown book yields no replacement`() {
        assertNull(engine.getReplacementText(engine.tryParseReferences("Notabook 1:1")))
    }

    // ---------- Formatting options ----------

    private fun engineWith(vararg toggles: Pair<String, Boolean>): VerselinkBibleEngine {
        val base = FormatterOptions()
        val o = base.copy(
            includeReferenceInReplacement = toggles.find { it.first == "ref" }?.second ?: true,
            referenceOnFirstLine = toggles.find { it.first == "firstLine" }?.second ?: false,
            dynamicReference = toggles.find { it.first == "dynamic" }?.second ?: false,
            includeVerseNumbers = toggles.find { it.first == "numbers" }?.second ?: false,
            newLineBetweenChapters = toggles.find { it.first == "nlChapters" }?.second ?: false,
            newLineBetweenBooks = toggles.find { it.first == "nlBooks" }?.second ?: false
        )
        return VerselinkBibleEngine(MapProvider(), o)
    }

    @Test fun `option text only when reference excluded`() {
        val e = engineWith("ref" to false)
        val out = e.getReplacementText(e.tryParseReferences("John 3:16"))
        assertTrue(out!!.startsWith("For God so loved"))
        assertTrue(!out.startsWith("John 3:16"))
    }

    @Test fun `option verse numbers prefix each verse`() {
        val e = engineWith("numbers" to true, "ref" to false)
        val out = e.getReplacementText(e.tryParseReferences("John 3:16-17"))
        assertTrue(out!!.startsWith("16 For God so loved"))
        assertTrue(out.contains("17 For God sent not"))
    }

    @Test fun `option dynamic reference shortens label`() {
        val e = engineWith("dynamic" to true)
        val result = e.getVerseText(e.tryParseReference("John 3:16-17")!!)
        assertEquals("John 3:16", result!!.referenceLabel)
    }

    @Test fun `reference label canonicalises alias input`() {
        val result = engine.getVerseText(engine.tryParseReference("jn 3:16")!!)
        assertEquals("John 3:16", result!!.referenceLabel)
    }

    // ---------- Labels per reference type ----------
    // Regression: CHAPTER_ONLY / CHAPTER_RANGE / BOOK_RANGE all default
    // verseStart to 1 internally, which used to leak out as "Psalms 23:1".

    @Test fun `chapter only label has no verse`() {
        val result = engine.getVerseText(engine.tryParseReference("Psalm 23")!!)
        assertEquals("Psalms 23", result!!.referenceLabel)
    }

    @Test fun `chapter only replacement starts with bare chapter label`() {
        val out = engine.getReplacementText(engine.tryParseReferences("Psalm 23"))
        assertTrue(out!!.startsWith("Psalms 23 The LORD is my shepherd"))
    }

    @Test fun `chapter range label has no verse`() {
        val result = engine.getVerseText(engine.tryParseReference("Genesis 1-50")!!)
        assertEquals("Genesis 1-50", result!!.referenceLabel)
    }

    @Test fun `book range without chapters labels both books`() {
        val result = engine.getVerseText(engine.tryParseReference("Genesis - Exodus")!!)
        assertEquals("Genesis - Exodus", result!!.referenceLabel)
    }

    @Test fun `book range with chapters keeps them`() {
        val result = engine.getVerseText(engine.tryParseReference("Jonah 1 - Micah 1")!!)
        assertEquals("Jonah 1 - Micah 1", result!!.referenceLabel)
    }

    @Test fun `multiple verses label lists every verse`() {
        val result = engine.getVerseText(engine.tryParseReference("John 3:16,18,20")!!)
        assertEquals("John 3:16,18,20", result!!.referenceLabel)
    }

    @Test fun `verse range label keeps both ends`() {
        val result = engine.getVerseText(engine.tryParseReference("John 3:16-17")!!)
        assertEquals("John 3:16-17", result!!.referenceLabel)
    }

    @Test fun `cross chapter label keeps both chapters`() {
        val result = engine.getVerseText(engine.tryParseReference("Romans 8:28-9:1")!!)
        assertEquals("Romans 8:28-9:1", result!!.referenceLabel)
    }

    @Test fun `dynamic reference does not invent a verse for a chapter`() {
        val e = engineWith("dynamic" to true)
        val result = e.getVerseText(e.tryParseReference("Psalm 23")!!)
        assertEquals("Psalms 23", result!!.referenceLabel)
    }

    // ---------- Verse numbering ----------

    @Test fun `option verse numbers apply to comma lists`() {
        // Regression: MULTIPLE_VERSES bypassed the numbering helper entirely.
        val e = engineWith("numbers" to true, "ref" to false)
        val out = e.getReplacementText(e.tryParseReferences("John 3:16,18,20"))
        assertTrue(out!!.startsWith("16 For God so loved"))
        assertTrue(out.contains("18 He that believeth"))
        assertTrue(out.contains("20 For every one"))
    }

    // ---------- Multi-reference splitting ----------

    @Test fun `comma list survives an and separated selection`() {
        // Regression: the fallback split on ',' first, orphaning "18".
        val refs = engine.tryParseReferences("John 3:16,18 and Romans 8:28")
        assertEquals(2, refs.size)
        assertEquals(listOf(16, 18), refs[0].verseList)
        assertEquals("Romans", refs[1].book)
        val out = engine.getReplacementText(refs)
        assertTrue(out!!.contains("begotten Son"))
        assertTrue(out.contains("He that believeth"))
        assertTrue(out.contains("work together for good"))
    }

    @Test fun `comma still separates two whole references`() {
        val refs = engine.tryParseReferences("John 3:16, Romans 8:28")
        assertEquals(2, refs.size)
        assertEquals("John", refs[0].book)
        assertEquals("Romans", refs[1].book)
    }

    // ---------- Range iteration is bounded by the data ----------

    @Test fun `chapter lookup stops at the last verse that exists`() {
        val counting = CountingProvider(MapProvider())
        val e = VerselinkBibleEngine(counting)
        e.getReplacementText(e.tryParseReferences("Psalm 119"))
        // Exactly the 176 verses of the chapter, not the MAX_VERSE sentinel.
        assertEquals(176, counting.verseLookups)
    }

    @Test fun `unknown chapter costs no verse lookups`() {
        val counting = CountingProvider(MapProvider())
        val e = VerselinkBibleEngine(counting)
        assertNull(e.getReplacementText(e.tryParseReferences("Psalm 42")))
        assertEquals(0, counting.verseLookups)
    }

    @Test fun `book range walks only chapters that exist`() {
        val counting = CountingProvider(MapProvider())
        val e = VerselinkBibleEngine(counting)
        val out = e.getReplacementText(e.tryParseReferences("Genesis - Exodus"))
        assertTrue(out!!.contains("In the beginning"))
        assertTrue(out.contains("names of the children of Israel"))
        // Bounded by the data: Genesis 1 (1 verse) + Genesis 50 (verses 1-26,
        // only 26 exists) + Exodus 1 (1 verse). The empty chapters in between
        // cost nothing, and the MAX_CHAPTER/MAX_VERSE sentinels - which would
        // have meant ~2M probes for these two books - are never reached.
        assertEquals(28, counting.verseLookups)
    }
}
