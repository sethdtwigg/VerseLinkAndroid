package com.verselink.android.engine

import android.content.Context
import android.util.Log
import android.util.Xml
import java.io.InputStream
import java.util.SortedMap
import java.util.TreeMap

/**
 * Loads VerseLinkWindows-format XML files (same files the Windows app uses).
 *
 * Design notes:
 *  - Streamed with XmlPullParser instead of a DOM: a full KJV DOM would cost
 *    ~10x the file size in RAM, which matters for an IME process.
 *  - One version is cached per process; classifier + IME share this instance.
 *  - Loading is lazy; callers must do the first access off the main thread.
 */
class AssetBibleRepository(private val context: Context) : BibleTextProvider {

    data class Chapter(val verses: SortedMap<Int, String>)
    data class Book(val chapters: Map<Int, Chapter>)

    private class LoadedBible(
        val books: Map<String, Book>,
        val version: String
    )

    /** Translation selection; Settings UI writes this before first use. */
    @Volatile
    var selectedVersion: String = DEFAULT_VERSION

    @Volatile
    private var cache: LoadedBible? = null

    /** Lists bundled assets plus user-imported translations (filesDir/bibles). */
    fun availableVersions(): List<String> {
        val fromAssets = runCatching {
            context.assets.list("bibles")?.toList() ?: emptyList()
        }.getOrDefault(emptyList())
        val importedDir = java.io.File(context.filesDir, "bibles")
        val imported = importedDir.listFiles { f -> f.extension == "xml" }
            ?.map { it.name } ?: emptyList()
        return (fromAssets + imported).distinct().sorted()
    }

    private fun open(versionFile: String): InputStream? =
        runCatching { context.assets.open("bibles/$versionFile") }.getOrNull()
            ?: java.io.File(java.io.File(context.filesDir, "bibles"), versionFile)
                .takeIf { it.exists() }?.inputStream()

    /**
     * Double-checked locking: after first load the hot path is lock-free,
     * which matters because onClassifyText/onStartInputView are synchronous.
     */
    private fun get(versionFile: String): LoadedBible? {
        cache?.let { if (it.version == versionFile) return it }
        synchronized(this) {
            cache?.let { if (it.version == versionFile) return it }
            val stream = open(versionFile)
            if (stream == null) {
                Log.e(TAG, "Bible file not found: $versionFile " +
                    "(assets=${availableVersions()})")
                return null
            }
            stream.use { parsed -> cache = parse(parsed, versionFile) }
            return cache
        }
    }

    private fun parse(input: InputStream, version: String): LoadedBible? {
        return try {
            val parser = Xml.newPullParser()
            BibleXmlParser.configure(parser, input)
            val books = BibleXmlParser.parse(parser)

            if (books.isEmpty()) {
                Log.e(TAG, "Parsed 0 books from $version - wrong format?")
                return null
            }
            Log.i(TAG, "Loaded $version: ${books.size} books, " +
                "${books["John"]?.get(3)?.size ?: 0} verses in John 3")

            LoadedBible(
                books.mapValues { (_, chapters) ->
                    Book(chapters.mapValues { (_, verses) -> Chapter(verses) })
                },
                version
            )
        } catch (e: Exception) {
            // Never silent: a malformed/missing bible used to look identical
            // to an unknown reference, which made field failures undiagnosable.
            Log.e(TAG, "Failed to parse $version", e)
            null
        }
    }

    // ---- BibleTextProvider ----

    override fun verseText(book: String, chapter: Int, verse: Int): String? =
        get(selectedVersion)?.books?.get(book)?.chapters?.get(chapter)?.verses?.get(verse)

    override fun maxVerse(book: String, chapter: Int): Int? =
        get(selectedVersion)?.books?.get(book)?.chapters?.get(chapter)?.verses?.lastKey()

    companion object {
        private const val TAG = "VerseLink"
        const val DEFAULT_VERSION = "KJV.xml"
    }
}
