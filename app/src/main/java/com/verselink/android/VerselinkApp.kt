package com.verselink.android

import android.content.Context
import com.verselink.android.engine.AssetBibleRepository
import com.verselink.android.engine.FormatterOptions
import com.verselink.android.engine.VerselinkBibleEngine

/**
 * SharedPreferences wrapper mirroring the Windows config.json options that
 * affect replacement output. Defaults match the Windows defaults.
 */
object VerselinkPrefs {

    /** Public so SettingsActivity can bind checkboxes generically by key name. */
    const val SETTINGS_PREFS_NAME = "verselink_settings"

    const val KEY_INCLUDE_REFERENCE = "includeReferenceInReplacement"
    const val KEY_REFERENCE_FIRST_LINE = "referenceOnFirstLine"
    const val KEY_DYNAMIC_REFERENCE = "dynamicReference"
    const val KEY_VERSE_NUMBERS = "includeVerseNumbers"
    const val KEY_NEW_LINE_CHAPTERS = "newLineBetweenChapters"
    const val KEY_NEW_LINE_BOOKS = "newLineBetweenBooks"

    data class Flag(val key: String, val default: Boolean)

    /**
     * Single source of truth for the formatting flags and their defaults. The
     * settings screen used to re-derive the defaults itself, which meant two
     * places to keep in sync.
     */
    val FLAGS: List<Flag> = listOf(
        Flag(KEY_INCLUDE_REFERENCE, true),
        Flag(KEY_REFERENCE_FIRST_LINE, false),
        Flag(KEY_DYNAMIC_REFERENCE, false),
        Flag(KEY_VERSE_NUMBERS, false),
        Flag(KEY_NEW_LINE_CHAPTERS, false),
        Flag(KEY_NEW_LINE_BOOKS, false)
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)

    /** Generic flag access used by the formatting checkboxes in settings. */
    fun getFlag(context: Context, key: String, def: Boolean) = prefs(context).getBoolean(key, def)
    fun setFlag(context: Context, key: String, value: Boolean) {
        prefs(context).edit().putBoolean(key, value).apply()
        EngineProvider.invalidate(context)
    }

    /** Master switch for all VerseLink UI entry points. */
    fun isEnabled(context: Context) = prefs(context).getBoolean("enabled", true)
    fun setEnabled(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean("enabled", value).apply()

    /** Bible XML file name, e.g. "KJV.xml". Must exist in assets/bibles or filesDir/bibles. */
    fun translation(context: Context) = prefs(context).getString("translation", AssetBibleRepository.DEFAULT_VERSION)!!
    fun setTranslation(context: Context, value: String) {
        prefs(context).edit().putString("translation", value).apply()
        EngineProvider.invalidate(context) // force reload with the new file
    }

    private fun flag(context: Context, key: String): Boolean =
        prefs(context).getBoolean(key, FLAGS.first { it.key == key }.default)

    // Formatting flags (same names/defaults as config.json on Windows).
    fun includeReferenceInReplacement(context: Context) = flag(context, KEY_INCLUDE_REFERENCE)

    fun referenceOnFirstLine(context: Context) = flag(context, KEY_REFERENCE_FIRST_LINE)

    fun dynamicReference(context: Context) = flag(context, KEY_DYNAMIC_REFERENCE)

    fun includeVerseNumbers(context: Context) = flag(context, KEY_VERSE_NUMBERS)

    fun newLineBetweenChapters(context: Context) = flag(context, KEY_NEW_LINE_CHAPTERS)

    fun newLineBetweenBooks(context: Context) = flag(context, KEY_NEW_LINE_BOOKS)

    fun formatterOptions(context: Context): FormatterOptions = FormatterOptions(
        includeReferenceInReplacement = includeReferenceInReplacement(context),
        referenceOnFirstLine = referenceOnFirstLine(context),
        dynamicReference = dynamicReference(context),
        includeVerseNumbers = includeVerseNumbers(context),
        newLineBetweenChapters = newLineBetweenChapters(context),
        newLineBetweenBooks = newLineBetweenBooks(context)
    )
}

/**
 * Process-wide engine holder. The classifier service and the IME run in this
 * app's main process, so a single cached instance serves both; the 4.7 MB KJV
 * parse happens once instead of per-component.
 *
 * The first get() triggers XML parsing (~1-2 s), so callers doing UI work
 * should invoke it off the main thread (see VerseLinkActionActivity).
 */
object EngineProvider {

    @Volatile
    private var repository: AssetBibleRepository? = null

    @Volatile
    private var engine: VerselinkBibleEngine? = null

    fun repository(context: Context): AssetBibleRepository {
        val existing = repository
        if (existing != null) return existing
        synchronized(this) {
            repository?.let { return it }
            val repo = AssetBibleRepository(context.applicationContext).apply {
                selectedVersion = VerselinkPrefs.translation(context)
            }
            repository = repo
            return repo
        }
    }

    /**
     * NOTE: parsing is deferred until the first verse lookup, not here -
     * tryParseReference is pure regex work and must stay instant because
     * onClassifyText blocks the selection menu.
     */
    fun get(context: Context): VerselinkBibleEngine {
        val existing = engine
        if (existing != null) return existing
        synchronized(this) {
            engine?.let { return it }
            val e = VerselinkBibleEngine(repository(context), VerselinkPrefs.formatterOptions(context))
            engine = e
            return e
        }
    }

    /**
     * Called when settings change so they take effect immediately.
     *
     * Dropping the engine alone is not enough: the repository is cached
     * separately and snapshots the chosen translation at construction time, so
     * without pushing the current preference into it a translation change was
     * invisible until the process died.
     */
    fun invalidate(context: Context) {
        synchronized(this) {
            repository?.selectedVersion = VerselinkPrefs.translation(context)
            engine = null
        }
    }
}
