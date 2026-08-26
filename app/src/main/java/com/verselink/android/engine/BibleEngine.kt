package com.verselink.android.engine

/**
 * Core engine API. Mirrors the Windows VerseRetrieveInterface behaviour:
 * parse a selected string into reference(s), then look up + format verse text.
 *
 * The signatures requested by the project spec are kept exactly; richer
 * multi-reference / cross-chapter support lives in the extra fields of
 * [BibleReference] and the `parseAll`/`formatReplacement` helpers.
 */
enum class ReferenceType {
    SINGLE_VERSE,
    VERSE_RANGE,
    CHAPTER_ONLY,
    CHAPTER_RANGE,
    BOOK_RANGE,
    MULTIPLE_VERSES
}

data class BibleReference(
    val book: String,                 // canonical book name, e.g. "John"
    val chapter: Int,
    val verseStart: Int,
    val verseEnd: Int? = null,        // same-chapter range end
    val endChapter: Int? = null,      // cross-chapter range end chapter
    val endBook: String? = null,      // BOOK_RANGE end book
    val type: ReferenceType = ReferenceType.SINGLE_VERSE,
    /** MULTIPLE_VERSES only, e.g. [16, 18, 20] for "John 3:16,18,20". */
    val verseList: List<Int>? = null
)

data class BibleVerseResult(
    val reference: BibleReference,
    val text: String,
    /** Canonical label ("John 3:16-17") used by formatting options. */
    val referenceLabel: String = ""
)

interface BibleEngine {
    fun tryParseReference(raw: String): BibleReference?
    fun getVerseText(ref: BibleReference): BibleVerseResult?

    /**
     * Parses every reference contained in the selection (the Windows app
     * splits on ';', ',' and " and " when the whole-string parse fails).
     * Returns an empty list if nothing parses.
     */
    fun tryParseReferences(raw: String): List<BibleReference>

    /**
     * Full replacement text for one or more references, applying the user's
     * FormatterOptions. Returns null if no verse text could be resolved.
     */
    fun getReplacementText(refs: List<BibleReference>): String?
}
