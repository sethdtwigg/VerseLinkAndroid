package com.verselink.android.util

/**
 * Filename rules for imported translations.
 *
 * Pure Kotlin with no Android imports so the rules can be unit-tested: the
 * name of an imported document comes from a content provider, i.e. from
 * another app, and is used as a path component under filesDir/bibles. It is
 * untrusted input and gets treated as such.
 */
object TranslationFileName {

    private const val ALLOWED_EXTRA_CHARS = "._- "
    private const val EXTENSION = ".xml"

    /**
     * Reduces a provider-supplied display name to a bare, whitelisted,
     * .xml-suffixed filename. Never returns a path: separators and traversal
     * segments cannot survive.
     */
    fun sanitise(displayName: String?): String {
        val base = displayName
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.filter { it.isLetterOrDigit() || it in ALLOWED_EXTRA_CHARS }
            ?.trim()
            ?.trimStart('.')
            ?.trim()
            .orEmpty()
        val name = base.ifEmpty { "IMPORTED_${System.currentTimeMillis()}" }
        return if (name.endsWith(EXTENSION, ignoreCase = true)) name else "$name$EXTENSION"
    }

    /**
     * Keeps an import off a name that is already [taken] by a bundled asset.
     * AssetBibleRepository.open() resolves assets before filesDir, so an
     * imported "KJV.xml" would be listed but never loaded; importing it as
     * "KJV-2.xml" keeps both reachable.
     */
    fun disambiguate(name: String, taken: Collection<String>): String {
        if (name !in taken) return name
        val stem = name.removeSuffix(EXTENSION)
        var n = 2
        while ("$stem-$n$EXTENSION" in taken) n++
        return "$stem-$n$EXTENSION"
    }
}
