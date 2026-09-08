package com.verselink.android.engine

/**
 * Pure-Kotlin port of VerseRetrieveInterface::GetVerseText + the observable
 * replacement formatting of VerseLinkTask (VerseLinkWindows.cpp:118-139).
 *
 * No Android imports: this class is unit-testable on the JVM.
 */
class VerselinkBibleEngine(
    private val textProvider: BibleTextProvider,
    private val options: FormatterOptions = FormatterOptions()
) : BibleEngine {

    // ---- Parsing ----

    override fun tryParseReference(raw: String): BibleReference? =
        tryParseReferences(raw).firstOrNull()

    override fun tryParseReferences(raw: String): List<BibleReference> =
        ReferenceParser.parseReferences(raw)

    // ---- Lookup ----

    override fun getVerseText(ref: BibleReference): BibleVerseResult? {
        val body = assembleBody(ref)
        if (body.isBlank()) return null
        return BibleVerseResult(ref, clean(body), referenceLabel(ref))
    }

    /**
     * Full formatted replacement for a selection that may contain several
     * references. Semantics match the Windows app's final replacement step:
     *
     *  includeReference=false            -> verse text only
     *  includeReference=true             -> "{reference} {text}" (template)
     *  includeReference + firstLine=true -> "{reference}\n{text}"
     */
    override fun getReplacementText(refs: List<BibleReference>): String? {
        if (refs.isEmpty()) return null

        // Verse-number prefixing already happened inside renderVerse, so
        // assembling + labelling is all that remains (matches Windows output
        // where getVersesFromChapter inserted the numbers during collection).
        //
        // Each reference carries its OWN label: a selection like
        // "John 3:16; Romans 8:28" used to emit one leading "John 3:16" for
        // the whole thing, filing the Romans text under the wrong reference.
        val blocks = refs.mapNotNull { ref ->
            val body = clean(assembleBody(ref)).takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            if (!options.includeReferenceInReplacement) return@mapNotNull body
            val label = referenceLabel(ref)
            if (options.referenceOnFirstLine) "$label\n$body" else "$label $body"
        }
        if (blocks.isEmpty()) return null
        return blocks.joinToString(if (options.referenceOnFirstLine) "\n" else " ")
    }

    /** Port of prepareResult(): collapse runs of spaces/tabs, drop \r, trim. */
    private fun clean(s: String): String =
        s.replace(Regex("[ \\t\\u000C\\u000B]+"), " ")
            .replace("\r", "")
            .trim { it.isWhitespace() }

    // ---- Body assembly (port of the big dispatch in GetVerseText) ----

    private fun assembleBody(ref: BibleReference): String = when (ref.type) {
        // Routed through versesInRange so includeVerseNumbers applies
        // uniformly. (The Windows single-verse branch skips numbering - a
        // quirk we intentionally do not reproduce.)
        ReferenceType.SINGLE_VERSE ->
            versesInRange(ref.book, ref.chapter, ref.verseStart, ref.verseStart, " ")

        // Non-contiguous list: same rendering rules as a range, just a
        // hand-picked set of verse numbers. Routed through renderVerse so
        // includeVerseNumbers applies here too - this used to be the one
        // branch that ignored the flag.
        ReferenceType.MULTIPLE_VERSES ->
            ref.verseList.orEmpty()
                .mapNotNull { v -> renderVerse(ref.book, ref.chapter, v) }
                .joinToString(" ")

        ReferenceType.CHAPTER_ONLY ->
            versesInRange(ref.book, ref.chapter, 1, MAX_VERSE, " ")

        ReferenceType.CHAPTER_RANGE -> {
            var out = ""
            for (ch in ref.chapter..lastChapter(ref.book, ref.endChapter ?: ref.chapter)) {
                appendWith(out, versesInRange(ref.book, ch, 1, MAX_VERSE, " "),
                    sep(options.newLineBetweenChapters)) { out = it }
            }
            out
        }

        ReferenceType.VERSE_RANGE -> {
            val endChapter = ref.endChapter
            if (endChapter != null && endChapter != ref.chapter) {
                crossChapterRange(ref, endChapter)
            } else {
                versesInRange(ref.book, ref.chapter, ref.verseStart,
                    ref.verseEnd ?: ref.verseStart, " ")
            }
        }

        ReferenceType.BOOK_RANGE -> bookRange(ref)
    }

    /** "Romans 8:28-9:1": tail of first chapter + full middles + head of last. */
    private fun crossChapterRange(ref: BibleReference, endChapter: Int): String {
        val parts = mutableListOf<String>()
        versesInRange(ref.book, ref.chapter, ref.verseStart, MAX_VERSE, " ")
            .takeIf { it.isNotBlank() }?.let { parts.add(it) }
        for (ch in (ref.chapter + 1) until endChapter) {
            versesInRange(ref.book, ch, 1, MAX_VERSE, " ")
                .takeIf { it.isNotBlank() }?.let { parts.add(it) }
        }
        versesInRange(ref.book, endChapter, 1, ref.verseEnd ?: MAX_VERSE, " ")
            .takeIf { it.isNotBlank() }?.let { parts.add(it) }
        return parts.joinToString(sep(options.newLineBetweenChapters))
    }

    /** Port of the BOOK_RANGE branch incl. its partial-range chapter rules. */
    private fun bookRange(ref: BibleReference): String {
        val startIdx = BookData.indexOf(ref.book)
        val endIdx = BookData.indexOf(ref.endBook ?: ref.book)
        if (startIdx < 0 || endIdx < 0 || endIdx < startIdx) return ""

        val parts = mutableListOf<String>()
        for (i in startIdx..endIdx) {
            val book = BookData.CANONICAL_ORDER[i]
            val from: Int
            val to: Int
            if (startIdx == endIdx) {
                from = ref.chapter
                to = ref.endChapter ?: MAX_CHAPTER
            } else if (i == startIdx) {
                from = ref.chapter
                to = MAX_CHAPTER
            } else if (i == endIdx) {
                from = 1
                to = ref.endChapter ?: MAX_CHAPTER
            } else {
                from = 1
                to = MAX_CHAPTER
            }

            var bookText = ""
            for (ch in from..lastChapter(book, to)) {
                appendWith(bookText, versesInRange(book, ch, 1, MAX_VERSE, " "),
                    sep(options.newLineBetweenChapters)) { bookText = it }
            }
            if (bookText.isNotBlank()) {
                parts.add(bookText)
            }
        }
        return parts.joinToString(if (options.newLineBetweenBooks) "\n\n" else " ")
    }

    /**
     * Clamps a requested end-chapter to what the book actually contains, so
     * "Genesis - Revelation" walks each book once instead of the MAX_CHAPTER
     * sentinel. Providers that cannot answer keep the sentinel.
     */
    private fun lastChapter(book: String, requested: Int): Int =
        textProvider.maxChapter(book)?.let { minOf(requested, it) } ?: requested

    /**
     * Joins verses in [start, end] that exist; unknown verses are skipped.
     * The end is clamped to the real length of the chapter (and an unknown
     * chapter exits immediately) so a chapter costs one lookup per verse that
     * exists rather than MAX_VERSE misses.
     */
    private fun versesInRange(
        book: String, chapter: Int, start: Int, end: Int, separator: String
    ): String {
        val last = textProvider.maxVerse(book, chapter) ?: return ""
        val sb = StringBuilder()
        for (v in start..minOf(end, last)) {
            val t = renderVerse(book, chapter, v) ?: continue
            if (sb.isNotEmpty()) sb.append(separator)
            sb.append(t)
        }
        return sb.toString()
    }

    /** One verse with the configured numbering applied, or null if absent. */
    private fun renderVerse(book: String, chapter: Int, verse: Int): String? {
        val text = textProvider.verseText(book, chapter, verse) ?: return null
        return if (options.includeVerseNumbers) "$verse $text" else text
    }

    // ---- Reference labels (port of the reference-building block, lines 468-501) ----

    internal fun referenceLabel(first: BibleReference): String {
        // "Dynamic" means "collapse a range down to its opening verse". Types
        // that carry no verse of their own (a whole chapter, a chapter range,
        // a book range) have nothing to collapse - labelling those
        // "Psalms 23:1" invents a verse the user never asked for.
        if (options.dynamicReference && hasExplicitVerse(first)) {
            return "${first.book} ${first.chapter}:${first.verseStart}"
        }
        return labelFromReference(first)
    }

    private fun hasExplicitVerse(r: BibleReference): Boolean = when (r.type) {
        ReferenceType.SINGLE_VERSE,
        ReferenceType.VERSE_RANGE,
        ReferenceType.MULTIPLE_VERSES -> true
        else -> false
    }

    /**
     * Canonical label per reference type. The verse part is emitted only for
     * the types that actually carry one: CHAPTER_ONLY, CHAPTER_RANGE and
     * BOOK_RANGE all default verseStart to 1 internally, which used to leak
     * out as "Psalms 23:1" for a plain "Psalm 23".
     */
    private fun labelFromReference(r: BibleReference): String = when (r.type) {
        ReferenceType.SINGLE_VERSE -> "${r.book} ${r.chapter}:${r.verseStart}"

        ReferenceType.MULTIPLE_VERSES ->
            "${r.book} ${r.chapter}:" + (r.verseList ?: listOf(r.verseStart)).joinToString(",")

        ReferenceType.VERSE_RANGE -> {
            val head = "${r.book} ${r.chapter}:${r.verseStart}"
            when {
                r.endBook != null && r.endBook != r.book ->
                    "$head - ${r.endBook} ${r.endChapter ?: r.chapter}:${r.verseEnd ?: 1}"
                r.endChapter != null && r.endChapter != r.chapter ->
                    "$head-${r.endChapter}:${r.verseEnd ?: 1}"
                r.verseEnd != null && r.verseEnd != r.verseStart -> "$head-${r.verseEnd}"
                else -> head
            }
        }

        ReferenceType.CHAPTER_ONLY -> "${r.book} ${r.chapter}"

        ReferenceType.CHAPTER_RANGE ->
            if (r.endChapter != null && r.endChapter != r.chapter) {
                "${r.book} ${r.chapter}-${r.endChapter}"
            } else {
                "${r.book} ${r.chapter}"
            }

        ReferenceType.BOOK_RANGE -> {
            // "Genesis - Exodus" carries no chapters; "Jonah 1 - Micah 1" does,
            // and then both ends show theirs.
            val endBook = r.endBook ?: r.book
            if (r.endChapter != null) {
                "${r.book} ${r.chapter} - $endBook ${r.endChapter}"
            } else if (endBook != r.book) {
                "${r.book} - $endBook"
            } else {
                r.book
            }
        }
    }

    private inline fun appendWith(
        target: String, addition: String, separator: String, setter: (String) -> Unit
    ) {
        if (addition.isBlank()) return
        setter(if (target.isEmpty()) addition else target + separator + addition)
    }

    private fun sep(newLine: Boolean) = if (newLine) "\n" else " "

    companion object {
        private const val MAX_VERSE = 999
        private const val MAX_CHAPTER = 999
    }
}
