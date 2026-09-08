package com.verselink.android.engine

import android.content.Context
import android.util.Log
import java.io.InputStream
import java.util.SortedMap

/**
 * Loads VerseLinkWindows-format XML files (same files the Windows app uses).
 *
 * Design notes:
 *  - Streamed with XmlPullParser instead of a DOM: a full KJV DOM would cost
 *    ~10x the file size in RAM, which matters for an IME process.
 *  - One version is cached per process; classifier + IME share this instance.
 *  - Loading is lazy; callers must do the first access off the main thread.
 *  - Failures are cached too (see [CacheEntry]): a missing or malformed file
 *    must fail once, not once per verse lookup.
 */
class AssetBibleRepository(private val context: Context) : BibleTextProvider {

    data class Chapter(val verses: SortedMap<Int, String>)
    data class Book(val chapters: Map<Int, Chapter>)

    /**
     * Outcome of loading one version. [books] is null when the file was
     * missing or unparseable - memoising that is what stops a broken bible
     * from re-parsing 4.7 MB of XML on every single verseText() call.
     */
    private class CacheEntry(val version: String, val books: Map<String, Book>?)

    /**
     * Translation selection; Settings UI writes this before first use.
     * Assigning a different file drops the cached bible so the next lookup
     * loads the newly chosen one.
     */
    @Volatile
    var selectedVersion: String = DEFAULT_VERSION
        set(value) {
            if (field == value) return
            synchronized(this) {
                field = value
                cache = null
            }
        }

    @Volatile
    private var cache: CacheEntry? = null

    /** Drops the cached bible; used after importing/replacing a file on disk. */
    fun invalidate() {
        synchronized(this) { cache = null }
    }

    /** Translations shipped inside the APK. */
    fun bundledVersions(): List<String> = runCatching {
        context.assets.list("bibles")?.toList() ?: emptyList()
    }.getOrDefault(emptyList())

    /** Lists bundled assets plus user-imported translations (filesDir/bibles). */
    fun availableVersions(): List<String> {
        val importedDir = java.io.File(context.filesDir, "bibles")
        val imported = importedDir.listFiles { f -> f.extension == "xml" }
            ?.map { it.name } ?: emptyList()
        // A name can only appear once, and open() resolves assets first, so an
        // import is kept off a bundled name at import time instead.
        return (bundledVersions() + imported).distinct().sorted()
    }

    private fun open(versionFile: String): InputStream? =
        runCatching { context.assets.open("bibles/$versionFile") }.getOrNull()
            ?: java.io.File(java.io.File(context.filesDir, "bibles"), versionFile)
                .takeIf { it.exists() }?.inputStream()

    /**
     * Double-checked locking: after first load the hot path is lock-free,
     * which matters because onClassifyText/onStartInputView are synchronous.
     */
    private fun books(versionFile: String): Map<String, Book>? {
        cache?.let { if (it.version == versionFile) return it.books }
        synchronized(this) {
            cache?.let { if (it.version == versionFile) return it.books }
            val entry = CacheEntry(versionFile, load(versionFile))
            cache = entry
            return entry.books
        }
    }

    private fun load(versionFile: String): Map<String, Book>? {
        val stream = open(versionFile)
        if (stream == null) {
            Log.e(TAG, "Bible file not found: $versionFile (available=${availableVersions()})")
            return null
        }
        return stream.use { parse(it, versionFile) }
    }

    private fun parse(input: InputStream, version: String): Map<String, Book>? {
        return try {
            val parser = android.util.Xml.newPullParser()
            BibleXmlParser.configure(parser, input)
            val books = BibleXmlParser.parse(parser)

            if (books.isEmpty()) {
                Log.e(TAG, "Parsed 0 books from $version - wrong format?")
                return null
            }
            Log.i(TAG, "Loaded $version: ${books.size} books, " +
                "${books["John"]?.get(3)?.size ?: 0} verses in John 3")

            books.mapValues { (_, chapters) ->
                Book(chapters.mapValues { (_, verses) -> Chapter(verses) })
            }
        } catch (e: Exception) {
            // Never silent: a malformed/missing bible used to look identical
            // to an unknown reference, which made field failures undiagnosable.
            Log.e(TAG, "Failed to parse $version", e)
            null
        }
    }

    // ---- BibleTextProvider ----

    override fun verseText(book: String, chapter: Int, verse: Int): String? =
        books(selectedVersion)?.get(book)?.chapters?.get(chapter)?.verses?.get(verse)

    override fun maxVerse(book: String, chapter: Int): Int? =
        books(selectedVersion)?.get(book)?.chapters?.get(chapter)?.verses
            ?.takeIf { it.isNotEmpty() }?.lastKey()

    override fun maxChapter(book: String): Int? =
        books(selectedVersion)?.get(book)?.chapters?.keys?.maxOrNull()

    companion object {
        private const val TAG = "VerseLink"
        const val DEFAULT_VERSION = "KJV.xml"
    }
}
