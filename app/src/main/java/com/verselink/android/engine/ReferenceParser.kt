package com.verselink.android.engine

/**
 * Port of VerseRetrieveInterface::parseSingleReference (VerseLinkWindows,
 * lines 98-296). The nine patterns are kept in the exact same order because
 * first-match-wins ordering IS the algorithm: e.g. the single-verse pattern
 * must be tried before the comma-list pattern or "John 3:16" would match the
 * MULTIPLE_VERSES shape.
 *
 * Faithful-port notes:
 *  - Range separators are hyphen and Unicode en/em dashes.
 *  - The book token accepts several words, unlike the upstream single-word
 *    token: see [BOOK].
 */
object ReferenceParser {

    // Character class for range separators: '-', en dash (–), em dash (—).
    private const val DASH = "\\u002D\\u2013\\u2014"

    /**
     * Optional leading number, then one or more words.
     *
     * Windows used a single-word token, which meant "Song of Solomon 2:1" and
     * "1st John 2:1" never parsed even though both names are in the alias
     * table. Allowing extra words costs nothing in false positives, because
     * the captured token still has to match an alias exactly - "Meeting room
     * 5" is a book name lookup that fails, not a reference.
     */
    private val BOOK = "([0-9]*\\s*[a-zA-Z]+(?:\\s+[a-zA-Z]+)*)"

    private data class Pattern(val regex: Regex)

    private val patterns: List<Pattern> = listOf(
        // 0: Cross-chapter verse range with book repeated: "Romans 8:28 - Romans 9:1"
        Pattern(Regex("^\\s*$BOOK\\s+(\\d+):(\\d+)\\s*[$DASH]\\s*$BOOK\\s+(\\d+):(\\d+)\\s*\$", RegexOption.IGNORE_CASE)),
        // 1: Cross-chapter verse range: "Romans 8:28-9:1"
        Pattern(Regex("^\\s*$BOOK\\s+(\\d+):(\\d+)\\s*[$DASH]\\s*(\\d+):(\\d+)\\s*\$", RegexOption.IGNORE_CASE)),
        // 2: Book range with chapters: "Jonah 1 - Micah 1"
        Pattern(Regex("^\\s*$BOOK\\s+(\\d+)\\s*[$DASH]\\s*$BOOK\\s+(\\d+)\\s*\$", RegexOption.IGNORE_CASE)),
        // 3: Book range without chapters: "Genesis - Exodus"
        Pattern(Regex("^\\s*$BOOK\\s*[$DASH]\\s*$BOOK\\s*\$", RegexOption.IGNORE_CASE)),
        // 4: Chapter range: "John 1-2"
        Pattern(Regex("^\\s*$BOOK\\s+(\\d+)\\s*[$DASH]\\s*(\\d+)\\s*\$", RegexOption.IGNORE_CASE)),
        // 5: Verse range: "Romans 8:1-5"
        Pattern(Regex("^\\s*$BOOK\\s+(\\d+):(\\d+)\\s*[$DASH]\\s*(\\d+)\\s*\$", RegexOption.IGNORE_CASE)),
        // 6: Single verse: "John 3:16"
        Pattern(Regex("^\\s*$BOOK\\s+(\\d+):(\\d+)\\s*\$", RegexOption.IGNORE_CASE)),
        // 7: Multiple verses (requires >= 1 comma): "John 3:16,18,20"
        Pattern(Regex("^\\s*$BOOK\\s+(\\d+):(\\d+(?:\\s*,\\s*\\d+)+)\\s*\$", RegexOption.IGNORE_CASE)),
        // 8: Chapter only: "Genesis 1"
        Pattern(Regex("^\\s*$BOOK\\s+(\\d+)\\s*\$", RegexOption.IGNORE_CASE))
    )

    /**
     * Splits a selection into candidate fragments, mirroring
     * splitMultipleReferences(). Only used when the whole input fails to parse
     * as one reference.
     *
     * [includeComma] is false for the first pass: splitting on ',' unconditionally
     * would tear "John 3:16,18 and Romans 8:28" into an orphaned "18" that
     * matches no pattern and is silently dropped. Commas are only used as a
     * separator for a fragment that failed to parse on its own.
     */
    fun splitMultipleReferences(input: String, includeComma: Boolean = true): List<String> {
        val separators = if (includeComma) ";|,|\\s+and\\s+" else ";|\\s+and\\s+"
        return input.split(Regex(separators, RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /** Tries the whole string as ONE reference. Returns null if no pattern matches. */
    fun parseSingleReference(refStr: String): BibleReference? {
        for ((index, p) in patterns.withIndex()) {
            val m = p.regex.find(refStr) ?: continue
            val g = m.groupValues

            // Unknown book aborts this fragment, exactly like Windows.
            fun norm(i: Int): String? {
                return BookData.normalizeBookName(g[i]) ?: return null
            }

            return when (index) {
                0 -> {
                    val b1 = norm(1) ?: return null
                    val b2 = norm(4) ?: return null
                    BibleReference(
                        book = b1, chapter = g[2].toInt(), verseStart = g[3].toInt(),
                        endChapter = g[5].toInt(), verseEnd = g[6].toInt(), endBook = b2,
                        type = ReferenceType.VERSE_RANGE
                    )
                }
                1 -> {
                    val book = norm(1) ?: return null
                    BibleReference(
                        book = book, chapter = g[2].toInt(), verseStart = g[3].toInt(),
                        endChapter = g[4].toInt(), verseEnd = g[5].toInt(), endBook = book,
                        type = ReferenceType.VERSE_RANGE
                    )
                }
                2 -> {
                    val b1 = norm(1) ?: return null
                    val b2 = norm(3) ?: return null
                    BibleReference(
                        book = b1, chapter = g[2].toInt(), verseStart = 1,
                        endBook = b2, endChapter = g[4].toInt(),
                        type = ReferenceType.BOOK_RANGE
                    )
                }
                3 -> {
                    val b1 = norm(1) ?: return null
                    val b2 = norm(2) ?: return null
                    BibleReference(
                        book = b1, chapter = 1, verseStart = 1,
                        endBook = b2, type = ReferenceType.BOOK_RANGE
                    )
                }
                4 -> {
                    val book = norm(1) ?: return null
                    BibleReference(
                        book = book, chapter = g[2].toInt(), verseStart = 1,
                        endChapter = g[3].toInt(), type = ReferenceType.CHAPTER_RANGE
                    )
                }
                5 -> {
                    val book = norm(1) ?: return null
                    BibleReference(
                        book = book, chapter = g[2].toInt(), verseStart = g[3].toInt(),
                        verseEnd = g[4].toInt(), type = ReferenceType.VERSE_RANGE
                    )
                }
                6 -> {
                    val book = norm(1) ?: return null
                    BibleReference(
                        book = book, chapter = g[2].toInt(), verseStart = g[3].toInt(),
                        type = ReferenceType.SINGLE_VERSE
                    )
                }
                7 -> {
                    val book = norm(1) ?: return null
                    val verses = g[3].split(",").mapNotNull { it.trim().toIntOrNull() }
                    BibleReference(
                        book = book, chapter = g[2].toInt(), verseStart = verses.firstOrNull() ?: return null,
                        verseList = verses, type = ReferenceType.MULTIPLE_VERSES
                    )
                }
                else -> { // 8: chapter only
                    val book = norm(1) ?: return null
                    BibleReference(
                        book = book, chapter = g[2].toInt(), verseStart = 1,
                        type = ReferenceType.CHAPTER_ONLY
                    )
                }
            }
        }
        return null
    }

    /**
     * Port of parseBibleReference(): try the whole trimmed input first so
     * legitimate comma-lists ("John 3:16,18,20") are not torn apart; only on
     * failure fall back to splitting into multiple references and keep every
     * fragment that parses (partial success counts).
     *
     * The fallback is two-stage: ';' and " and " first (which leaves comma
     * lists intact), then ',' applied only to fragments that still fail.
     */
    fun parseReferences(raw: String): List<BibleReference> {
        if (raw.isBlank()) return emptyList()
        val trimmed = raw.trim()
        parseSingleReference(trimmed)?.let { return listOf(it) }

        val out = mutableListOf<BibleReference>()
        for (fragment in splitMultipleReferences(trimmed, includeComma = false)) {
            val whole = parseSingleReference(fragment)
            if (whole != null) {
                out.add(whole)
                continue
            }
            // Only now is a comma a reference separator rather than part of a
            // verse list, e.g. "John 3:16, Romans 8:28".
            fragment.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNullTo(out) { parseSingleReference(it) }
        }
        return out
    }
}
