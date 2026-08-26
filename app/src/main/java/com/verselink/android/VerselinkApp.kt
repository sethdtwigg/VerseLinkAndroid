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

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)

    /** Generic flag access used by the formatting checkboxes in settings. */
    fun getFlag(context: Context, key: String, def: Boolean) = prefs(context).getBoolean(key, def)
    fun setFlag(context: Context, key: String, value: Boolean) {
        prefs(context).edit().putBoolean(key, value).apply()
        EngineProvider.invalidate()
    }

    /** Master switch for all VerseLink UI entry points. */
    fun isEnabled(context: Context) = prefs(context).getBoolean("enabled", true)
    fun setEnabled(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean("enabled", value).apply()

    /** Bible XML file name, e.g. "KJV.xml". Must exist in assets/bibles or filesDir/bibles. */
    fun translation(context: Context) = prefs(context).getString("translation", AssetBibleRepository.DEFAULT_VERSION)!!
    fun setTranslation(context: Context, value: String) {
        prefs(context).edit().putString("translation", value).apply()
        EngineProvider.invalidate() // force reload with the new file
    }

    // Formatting flags (same names/defaults as config.json on Windows).
    fun includeReferenceInReplacement(context: Context) =
        prefs(context).getBoolean("includeReferenceInReplacement", true)

    fun referenceOnFirstLine(context: Context) =
        prefs(context).getBoolean("referenceOnFirstLine", false)

    fun dynamicReference(context: Context) =
        prefs(context).getBoolean("dynamicReference", false)

    fun includeVerseNumbers(context: Context) =
        prefs(context).getBoolean("includeVerseNumbers", false)

    fun newLineBetweenChapters(context: Context) =
        prefs(context).getBoolean("newLineBetweenChapters", false)

    fun newLineBetweenBooks(context: Context) =
        prefs(context).getBoolean("newLineBetweenBooks", false)

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

    /** Called when formatting settings change so new flags take effect immediately. */
    fun invalidate() {
        synchronized(this) { engine = null }
    }
}
