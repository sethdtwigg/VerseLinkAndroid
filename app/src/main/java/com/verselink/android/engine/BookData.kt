package com.verselink.android.engine

/**
 * Faithful port of the BookAliases table and canonical book order from
 * VerseLinkWindows/Bible.cpp so Android parses exactly like Windows.
 *
 * Note: the C++ std::map ignores duplicate keys (first insert wins), and the
 * original table contains a few duplicates ("jud" appears for both Judges and
 * Jude). We replicate first-wins semantics by building in the same order with
 * putIfAbsent, so "jud" resolves to Judges exactly like Windows.
 */
object BookData {

    /** Canonical 66-book order; indices drive BOOK_RANGE expansion. */
    val CANONICAL_ORDER: List<String> = listOf(
        "Genesis", "Exodus", "Leviticus", "Numbers", "Deuteronomy", "Joshua",
        "Judges", "Ruth", "1 Samuel", "2 Samuel", "1 Kings", "2 Kings",
        "1 Chronicles", "2 Chronicles", "Ezra", "Nehemiah", "Esther", "Job",
        "Psalms", "Proverbs", "Ecclesiastes", "Song of Solomon", "Isaiah",
        "Jeremiah", "Lamentations", "Ezekiel", "Daniel", "Hosea", "Joel",
        "Amos", "Obadiah", "Jonah", "Micah", "Nahum", "Habakkuk",
        "Zephaniah", "Haggai", "Zechariah", "Malachi",
        "Matthew", "Mark", "Luke", "John", "Acts", "Romans",
        "1 Corinthians", "2 Corinthians", "Galatians", "Ephesians",
        "Philippians", "Colossians", "1 Thessalonians", "2 Thessalonians",
        "1 Timothy", "2 Timothy", "Titus", "Philemon", "Hebrews", "James",
        "1 Peter", "2 Peter", "1 John", "2 John", "3 John", "Jude",
        "Revelation"
    )

    private fun aliasesInWindowsOrder(): List<Pair<String, String>> = listOf(
        // ---- Old Testament ----
        "genesis" to "Genesis", "gen" to "Genesis", "ge" to "Genesis", "gn" to "Genesis",
        "exodus" to "Exodus", "exo" to "Exodus", "ex" to "Exodus",
        "leviticus" to "Leviticus", "lev" to "Leviticus", "le" to "Leviticus", "lv" to "Leviticus",
        "numbers" to "Numbers", "num" to "Numbers", "nu" to "Numbers", "nm" to "Numbers",
        "deuteronomy" to "Deuteronomy", "deut" to "Deuteronomy", "de" to "Deuteronomy", "dt" to "Deuteronomy",
        "joshua" to "Joshua", "josh" to "Joshua", "jos" to "Joshua", "jsh" to "Joshua",
        "judges" to "Judges", "judg" to "Judges", "jud" to "Judges", "jdg" to "Judges",
        "ruth" to "Ruth", "rut" to "Ruth", "ru" to "Ruth",
        "1 samuel" to "1 Samuel", "1sam" to "1 Samuel", "1 sa" to "1 Samuel", "1s" to "1 Samuel",
        "2 samuel" to "2 Samuel", "2sam" to "2 Samuel", "2 sa" to "2 Samuel", "2s" to "2 Samuel",
        "1 kings" to "1 Kings", "1kings" to "1 Kings", "1 ki" to "1 Kings", "1k" to "1 Kings",
        "2 kings" to "2 Kings", "2kings" to "2 Kings", "2 ki" to "2 Kings", "2k" to "2 Kings",
        "1 chronicles" to "1 Chronicles", "1chron" to "1 Chronicles", "1 ch" to "1 Chronicles", "1chr" to "1 Chronicles",
        "2 chronicles" to "2 Chronicles", "2chron" to "2 Chronicles", "2 ch" to "2 Chronicles", "2chr" to "2 Chronicles",
        "ezra" to "Ezra", "ezr" to "Ezra",
        "nehemiah" to "Nehemiah", "neh" to "Nehemiah", "ne" to "Nehemiah",
        "esther" to "Esther", "est" to "Esther", "es" to "Esther",
        "job" to "Job", "jb" to "Job",
        "psalms" to "Psalms", "psalm" to "Psalms", "ps" to "Psalms", "psa" to "Psalms",
        "proverbs" to "Proverbs", "prov" to "Proverbs", "pro" to "Proverbs", "pr" to "Proverbs",
        "ecclesiastes" to "Ecclesiastes", "eccles" to "Ecclesiastes", "ecc" to "Ecclesiastes", "ec" to "Ecclesiastes",
        "song of solomon" to "Song of Solomon", "song of songs" to "Song of Solomon",
        "song" to "Song of Solomon", "sng" to "Song of Solomon", "ss" to "Song of Solomon",
        "isaiah" to "Isaiah", "isa" to "Isaiah", "is" to "Isaiah",
        "jeremiah" to "Jeremiah", "jer" to "Jeremiah", "je" to "Jeremiah", "jr" to "Jeremiah",
        "lamentations" to "Lamentations", "lam" to "Lamentations", "la" to "Lamentations",
        "ezekiel" to "Ezekiel", "eze" to "Ezekiel", "ez" to "Ezekiel", "ezk" to "Ezekiel",
        "daniel" to "Daniel", "dan" to "Daniel", "da" to "Daniel", "dn" to "Daniel",
        "hosea" to "Hosea", "hos" to "Hosea", "ho" to "Hosea",
        "joel" to "Joel", "joe" to "Joel", "jl" to "Joel",
        "amos" to "Amos", "amo" to "Amos", "am" to "Amos",
        "obadiah" to "Obadiah", "oba" to "Obadiah", "ob" to "Obadiah",
        "jonah" to "Jonah", "jon" to "Jonah", "jnh" to "Jonah",
        "micah" to "Micah", "mic" to "Micah", "mi" to "Micah",
        "nahum" to "Nahum", "nah" to "Nahum", "na" to "Nahum", "nam" to "Nahum",
        "habakkuk" to "Habakkuk", "hab" to "Habakkuk", "ha" to "Habakkuk",
        "zephaniah" to "Zephaniah", "zep" to "Zephaniah", "ze" to "Zephaniah",
        "haggai" to "Haggai", "hg" to "Haggai", "hag" to "Haggai",
        "zechariah" to "Zechariah", "zech" to "Zechariah", "zec" to "Zechariah", "zc" to "Zechariah",
        "malachi" to "Malachi", "mal" to "Malachi", "ma" to "Malachi",
        // ---- New Testament ----
        "matthew" to "Matthew", "matt" to "Matthew", "mat" to "Matthew", "mt" to "Matthew",
        "mark" to "Mark", "mrk" to "Mark", "mar" to "Mark", "mk" to "Mark", "mr" to "Mark",
        "luke" to "Luke", "luk" to "Luke", "lk" to "Luke",
        "john" to "John", "jhn" to "John", "joh" to "John", "jn" to "John",
        "acts" to "Acts", "act" to "Acts", "ac" to "Acts",
        "romans" to "Romans", "rom" to "Romans", "ro" to "Romans", "rm" to "Romans",
        "1 corinthians" to "1 Corinthians", "1cor" to "1 Corinthians", "1 co" to "1 Corinthians", "1co" to "1 Corinthians",
        "2 corinthians" to "2 Corinthians", "2cor" to "2 Corinthians", "2 co" to "2 Corinthians", "2co" to "2 Corinthians",
        "galatians" to "Galatians", "gal" to "Galatians", "ga" to "Galatians",
        "ephesians" to "Ephesians", "eph" to "Ephesians", "ep" to "Ephesians",
        "philippians" to "Philippians", "phil" to "Philippians", "phi" to "Philippians", "php" to "Philippians",
        "colossians" to "Colossians", "col" to "Colossians", "co" to "Colossians",
        "1 thessalonians" to "1 Thessalonians", "1thess" to "1 Thessalonians", "1 th" to "1 Thessalonians", "1th" to "1 Thessalonians",
        "2 thessalonians" to "2 Thessalonians", "2thess" to "2 Thessalonians", "2 th" to "2 Thessalonians", "2th" to "2 Thessalonians",
        "1 timothy" to "1 Timothy", "1tim" to "1 Timothy", "1 ti" to "1 Timothy", "1ti" to "1 Timothy",
        "2 timothy" to "2 Timothy", "2tim" to "2 Timothy", "2 ti" to "2 Timothy", "2ti" to "2 Timothy",
        "titus" to "Titus", "tit" to "Titus", "ti" to "Titus",
        "philemon" to "Philemon", "phile" to "Philemon", "phm" to "Philemon", "pm" to "Philemon",
        "hebrews" to "Hebrews", "heb" to "Hebrews", "he" to "Hebrews",
        "james" to "James", "jas" to "James", "jam" to "James", "jm" to "James",
        "1 peter" to "1 Peter", "1pet" to "1 Peter", "1 pe" to "1 Peter", "1pe" to "1 Peter", "1pt" to "1 Peter",
        "2 peter" to "2 Peter", "2pet" to "2 Peter", "2 pe" to "2 Peter", "2pe" to "2 Peter", "2pt" to "2 Peter",
        "1 john" to "1 John", "1jn" to "1 John", "1 jo" to "1 John", "1st john" to "1 John",
        "2 john" to "2 John", "2jn" to "2 John", "2 jo" to "2 John", "2nd john" to "2 John",
        "3 john" to "3 John", "3jn" to "3 John", "3 jo" to "3 John", "3rd john" to "3 John",
        "jude" to "Jude", "jd" to "Jude",
        "revelation" to "Revelation", "rev" to "Revelation", "re" to "Revelation", "rv" to "Revelation"
    )

    val ALIASES: Map<String, String> = buildMap {
        // putIfAbsent = C++ std::map::emplace semantics: first definition wins.
        aliasesInWindowsOrder().forEach { (key, value) -> putIfAbsent(key, value) }
    }

    private val canonLowerToCanonical: Map<String, String> =
        CANONICAL_ORDER.associateBy { it.lowercase() }

    /**
     * Port of Bible::NormalizeBookName - lowercase, trim, alias lookup, then
     * case-insensitive canonical match. Returns null when unknown.
     */
    fun normalizeBookName(raw: String): String? {
        val normalized = raw.trim().lowercase()
        if (normalized.isEmpty()) return null
        ALIASES[normalized]?.let { return it }
        return canonLowerToCanonical[normalized]
    }

    fun isValidBook(raw: String): Boolean = normalizeBookName(raw) != null

    fun indexOf(bookName: String): Int =
        CANONICAL_ORDER.indexOf(bookName)
}
