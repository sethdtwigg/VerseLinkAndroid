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

        // Verse-number prefixing already happened inside versesInRange, so
        // assembling + joining is all that remains (matches Windows output
        // where getVersesFromChapter inserted the numbers during collection).
        val bodies = refs.mapNotNull { ref ->
            assembleBody(ref).takeIf { it.isNotBlank() }
        }
        if (bodies.isEmpty()) return null
        val body = clean(bodies.joinToString(" "))
        if (body.isEmpty()) return null

        if (!options.includeReferenceInReplacement) return body

        val label = referenceLabel(refs.first())
        return if (options.referenceOnFirstLine) "$label\n$body" else "$label $body"
    }

    /** Port of prepareResult(): collapse runs of spaces/tabs, drop \r, trim. */
    private fun clean(s: String): String =
        s.replace(Regex("[ \\t\\u000C\\u000B]+"), " ")
            .replace("\r", "")
            .trim { it.isWhitespace() }

    // ---- Body assembly (port of the big dispatch in GetVerseText) ----

    private fun assembleBody(ref: BibleReference): String = when (ref.type) {
        // Routed through versesInRange so includeVerseNumbers applies
        // uniformly. (Windows' single-verse branch skips numbering - a quirk
        // we intentionally do not reproduce.)
        ReferenceType.SINGLE_VERSE ->
            versesInRange(ref.book, ref.chapter, ref.verseStart, ref.verseStart, " ")

        ReferenceType.MULTIPLE_VERSES ->
            ref.verseList.orEmpty()
                .mapNotNull { v -> textProvider.verseText(ref.book, ref.chapter, v) }
                .joinToString(" ")

        ReferenceType.CHAPTER_ONLY ->
            versesInRange(ref.book, ref.chapter, 1, MAX_VERSE, " ")

        ReferenceType.CHAPTER_RANGE -> {
            var out = ""
            for (ch in ref.chapter..(ref.endChapter ?: ref.chapter)) {
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
            for (ch in from..to) {
                appendWith(bookText, versesInRange(book, ch, 1, MAX_VERSE, " "),
                    sep(options.newLineBetweenChapters)) { bookText = it }
            }
            if (bookText.isNotBlank()) {
                parts.add(bookText)
            }
        }
        return parts.joinToString(if (options.newLineBetweenBooks) "\n\n" else " ")
    }

    /** Joins verses in [start, end] that exist; unknown verses are skipped. */
    private fun versesInRange(
        book: String, chapter: Int, start: Int, end: Int, separator: String
    ): String {
        val sb = StringBuilder()
        for (v in start..minOf(end, MAX_VERSE)) {
            val t = textProvider.verseText(book, chapter, v) ?: continue
            if (sb.isNotEmpty()) sb.append(separator)
            if (options.includeVerseNumbers) sb.append(v).append(' ')
            sb.append(t)
        }
        return sb.toString()
    }

    // ---- Reference labels (port of the reference-building block, lines 468-501) ----

    internal fun referenceLabel(first: BibleReference): String {
        if (options.dynamicReference) {
            return "${first.book} ${first.chapter}:${first.verseStart}"
        }
        return labelFromReference(first)
    }

    private fun labelFromReference(r: BibleReference): String {
        val sb = StringBuilder(r.book)
        sb.append(' ').append(r.chapter)
        if (r.type == ReferenceType.SINGLE_VERSE || r.verseStart > 0) {
            sb.append(':').append(r.verseStart)
        }
        when {
            r.endBook != null && r.endBook != r.book ->
                sb.append(" - ").append(r.endBook)
                    .append(' ').append(r.endChapter ?: r.chapter)
                    .append(':').append(r.verseEnd ?: 1)
            r.endChapter != null && r.endChapter != r.chapter ->
                sb.append('-').append(r.endChapter).append(':')
                    .append(r.verseEnd ?: 1)
            r.verseEnd != null ->
                sb.append('-').append(r.verseEnd)
        }
        return sb.toString()
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
