package com.verselink.android.engine

import org.xmlpull.v1.XmlPullParser
import java.util.TreeMap

/**
 * Streams a VerseLinkWindows-format bible document into the compact in-memory
 * model:
 *
 *   <bible>
 *     <b n="Genesis">
 *       <c n="1">
 *         <v n="1">In the beginning...</v>
 *
 * Extracted from AssetBibleRepository so it can be integration-tested on the
 * JVM with KXmlParser (the same pull-parser implementation Android uses).
 */
object BibleXmlParser {

    /** book -> chapter -> verse -> text (sorted for range iteration). */
    fun parse(parser: XmlPullParser): LinkedHashMap<String, TreeMap<Int, TreeMap<Int, String>>> {
        val books = LinkedHashMap<String, TreeMap<Int, TreeMap<Int, String>>>()
        var bookName: String? = null
        var chapter: Int = -1

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "b" -> {
                        bookName = parser.getAttributeValue(null, "n")
                        if (bookName != null) books[bookName] = TreeMap()
                    }
                    "c" -> chapter = parser.getAttributeValue(null, "n")?.toIntOrNull() ?: -1
                    "v" -> {
                        val verse = parser.getAttributeValue(null, "n")?.toIntOrNull()
                        val b = bookName
                        if (verse != null && chapter > 0 && b != null) {
                            books[b]!!.getOrPut(chapter) { TreeMap() }[verse] =
                                readVerseText(parser)
                        }
                    }
                }
            }
            event = parser.next()
        }
        return books
    }

    /**
     * Reads the text of the current <v> element up to its END_TAG.
     *
     * Why not parser.nextText()? Real-world VerseLink XML files embed nested
     * markup inside verses - e.g. KJV.xml contains 149 <H2>Psalm n</H2>
     * section headings glued to the tail of the previous verse. nextText()
     * throws XmlPullParserException on any child element, which previously
     * aborted the whole load. tinyxml2 on Windows tolerated this by returning
     * only the first text node, so we match that behaviour: collect DIRECT
     * text children and skip nested subtrees entirely.
     */
    private fun readVerseText(parser: XmlPullParser): String {
        val sb = StringBuilder()
        var depth = 0
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++ // nested tag: ignore its subtree
                XmlPullParser.END_TAG ->
                    if (depth == 0) return sb.toString().trim()
                    else depth--
                XmlPullParser.TEXT -> if (depth == 0) sb.append(parser.text)
            }
        }
    }

    fun configure(parser: XmlPullParser, input: java.io.InputStream) {
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)
    }
}
