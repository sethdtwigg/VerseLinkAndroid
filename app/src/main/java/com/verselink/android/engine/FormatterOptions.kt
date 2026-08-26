package com.verselink.android.engine

/**
 * Abstraction over the verse text store so the engine stays testable on the
 * JVM (tests use a simple map-backed implementation; production uses the
 * asset-backed repository).
 */
interface BibleTextProvider {
    fun verseText(book: String, chapter: Int, verse: Int): String?

    /** Highest verse number that exists in this chapter, or null if unknown chapter. */
    fun maxVerse(book: String, chapter: Int): Int?
}

/**
 * Formatting options - 1:1 port of the Windows config.json flags that affect
 * replacement output.
 */
data class FormatterOptions(
    /**
     * Windows quirk preserved: when false, GetVerseText still prepends the
     * reference but VerseLinkTask's final override discards it, so the net
     * observable behaviour is "verse text only". We implement the observable
     * behaviour directly.
     */
    val includeReferenceInReplacement: Boolean = true,
    val referenceOnFirstLine: Boolean = false,
    val dynamicReference: Boolean = false,
    val includeVerseNumbers: Boolean = false,
    val newLineBetweenChapters: Boolean = false,
    val newLineBetweenBooks: Boolean = false
)
