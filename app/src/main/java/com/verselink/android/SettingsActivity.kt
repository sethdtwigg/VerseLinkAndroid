package com.verselink.android

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.verselink.android.engine.AssetBibleRepository
import com.verselink.android.engine.VerselinkBibleEngine
import com.verselink.android.util.CrashLog
import java.io.File
import kotlin.concurrent.thread

/**
 * Minimal but functional settings screen. Every binding step is guarded: a
 * failure in one section is logged and shown as a Toast rather than taking the
 * whole screen down, but genuine crashes still reach the platform handler (see
 * [CrashLog]).
 */
class SettingsActivity : Activity() {

    private lateinit var repository: AssetBibleRepository
    private lateinit var spinner: Spinner
    private val TAG = "VerseLink"

    /**
     * Spinner fires onItemSelected(0) for the adapter's initial selection on
     * the layout pass after setAdapter. Without this latch, that synthetic
     * callback switches the user's translation to whatever sorts first.
     */
    private var spinnerReady = false

    /** Guards against out-of-order preview results when toggles fly. */
    private var previewToken = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashLog.install(this)

        super.onCreate(savedInstanceState)

        try {
            setContentView(R.layout.activity_settings)
            Log.d(TAG, "Settings layout inflated")

            repository = EngineProvider.repository(this)
            Log.d(TAG, "Repository ready")

            bindMasterSwitch()
            bindTranslationSpinner()
            bindImportButton()
            bindDeleteButton()
            bindFormattingChecks()
            bindSwitchingStatus()
            refreshPreview()

            Log.d(TAG, "All bindings complete")
        } catch (e: Exception) {
            CrashLog.log(this, e)
            Toast.makeText(this, "Settings error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun bindMasterSwitch() {
        try {
            val sw = findViewById<Switch>(R.id.sw_enabled)
            sw.isChecked = VerselinkPrefs.isEnabled(this)
            sw.setOnCheckedChangeListener { _, checked ->
                VerselinkPrefs.setEnabled(this, checked)
            }
        } catch (e: Exception) {
            CrashLog.log(this, e)
        }
    }

    private fun bindTranslationSpinner() {
        try {
            spinner = findViewById(R.id.spinner_translation)
            spinnerReady = false

            val versions = repository.availableVersions()
            val current = VerselinkPrefs.translation(this)
            // Keep the saved translation visible even if its file has gone
            // missing, so rebinding cannot quietly adopt a different one.
            val display = (versions + current).distinct().sorted()

            spinner.adapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_dropdown_item, display
            )
            val idx = display.indexOf(current)
            if (idx >= 0) spinner.setSelection(idx)

            spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    if (!spinnerReady) {
                        spinnerReady = true // swallow the adapter's initial callback
                        return
                    }
                    val chosen = spinner.adapter.getItem(position)?.toString() ?: return
                    if (chosen != VerselinkPrefs.translation(this@SettingsActivity)) {
                        thread {
                            VerselinkPrefs.setTranslation(this@SettingsActivity, chosen)
                            runOnUiThread {
                                Toast.makeText(this@SettingsActivity,
                                    getString(R.string.translation_loaded, chosen), Toast.LENGTH_SHORT).show()
                                refreshPreview()
                            }
                        }
                    }
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        } catch (e: Exception) {
            CrashLog.log(this, e)
        }
    }

    private fun bindImportButton() {
        try {
            findViewById<Button>(R.id.btn_import).setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                startActivityForResult(intent, REQUEST_IMPORT)
            }
        } catch (e: Exception) {
            CrashLog.log(this, e)
        }
    }

    /**
     * Imported translations are otherwise permanent - short of clearing app
     * data there was no way to remove one. Bundled assets are not offered.
     */
    private fun bindDeleteButton() {
        try {
            findViewById<Button>(R.id.btn_delete_translation).setOnClickListener {
                val imported = repository.importedVersions()
                if (imported.isEmpty()) {
                    Toast.makeText(this, R.string.no_imported_translations, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                android.app.AlertDialog.Builder(this)
                    .setTitle(R.string.delete_translation_title)
                    .setItems(imported.toTypedArray()) { _, which ->
                        deleteTranslation(imported[which])
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        } catch (e: Exception) {
            CrashLog.log(this, e)
        }
    }

    private fun deleteTranslation(name: String) {
        val deleted = repository.deleteImported(name)
        if (deleted && VerselinkPrefs.translation(this) == name) {
            // The active translation just went away; fall back to the bundled
            // default rather than leaving a dangling selection.
            VerselinkPrefs.setTranslation(this, AssetBibleRepository.DEFAULT_VERSION)
        }
        Toast.makeText(
            this,
            getString(if (deleted) R.string.translation_deleted else R.string.delete_failed, name),
            Toast.LENGTH_SHORT
        ).show()
        if (deleted) {
            bindTranslationSpinner()
            refreshPreview()
        }
    }

    /**
     * Renders a sample reference with the flags as they stand, so the six
     * checkboxes are self-explanatory. Resolution can touch the bible file, so
     * it runs off the main thread; [previewToken] drops out-of-order results
     * when several toggles are flipped quickly.
     */
    private fun refreshPreview() {
        val token = ++previewToken
        val options = VerselinkPrefs.formatterOptions(this)
        thread(name = "verselink-preview") {
            val engine = VerselinkBibleEngine(repository, options)
            val text = runCatching {
                engine.getReplacementText(engine.tryParseReferences(PREVIEW_REFERENCE))
            }.getOrNull()
            runOnUiThread {
                if (token != previewToken) return@runOnUiThread
                findViewById<TextView>(R.id.txt_preview)?.text =
                    text ?: getString(R.string.preview_unavailable)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_IMPORT && resultCode == RESULT_OK) {
            val uri: Uri? = data?.data
            if (uri != null) importTranslation(uri)
        }
    }

    /**
     * Copies the picked document into filesDir/bibles.
     *
     * The name comes from a content provider, i.e. from another app, so it is
     * treated as untrusted input: reduced to a bare filename and whitelisted
     * before it is ever used as a path component. The copy also lands in a
     * ".part" file that is only renamed into place once it parses, so a bad
     * import can never overwrite - or delete - a translation that works.
     */
    private fun importTranslation(uri: Uri) {
        thread(name = "verselink-import") {
            var staged: File? = null
            val ok = runCatching {
                val safeName = disambiguate(sanitiseName(queryDisplayName(uri)))
                val destDir = File(filesDir, "bibles").apply { mkdirs() }
                val temp = File(destDir, "$safeName.part")
                staged = temp
                contentResolver.openInputStream(uri)!!.use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                }

                val probe = AssetBibleRepository(this).apply { selectedVersion = temp.name }
                val valid = probe.verseText("Genesis", 1, 1) != null ||
                    probe.verseText("John", 3, 16) != null ||
                    probe.maxVerse("Psalms", 119) != null
                if (!valid) return@runCatching false

                val dest = File(destDir, safeName)
                if (dest.exists()) dest.delete()
                if (!temp.renameTo(dest)) return@runCatching false
                staged = null
                // The file on disk changed; drop any cached parse of that name.
                EngineProvider.repository(this).invalidate()
                EngineProvider.invalidate(this)
                true
            }.getOrElse { e ->
                CrashLog.log(this, e)
                false
            }
            staged?.delete()

            runOnUiThread {
                Toast.makeText(
                    this,
                    if (ok) R.string.import_ok else R.string.import_failed,
                    Toast.LENGTH_LONG
                ).show()
                if (ok) {
                    bindTranslationSpinner()
                    refreshPreview()
                }
            }
        }
    }

    /**
     * Keeps an import off a bundled asset name. open() resolves assets before
     * filesDir, so an imported "KJV.xml" would be listed but never loaded;
     * importing it as "KJV-2.xml" keeps both reachable.
     */
    private fun disambiguate(name: String): String {
        val bundled = repository.bundledVersions()
        if (name !in bundled) return name
        val stem = name.removeSuffix(".xml")
        var n = 2
        while ("$stem-$n.xml" in bundled) n++
        return "$stem-$n.xml"
    }

    /** Bare, whitelisted, .xml-suffixed filename - never a path. */
    private fun sanitiseName(displayName: String?): String {
        val base = displayName
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.filter { it.isLetterOrDigit() || it in ALLOWED_NAME_CHARS }
            ?.trim()
            ?.trimStart('.')
            .orEmpty()
        val name = base.ifEmpty { "IMPORTED_${System.currentTimeMillis()}" }
        return if (name.endsWith(".xml", ignoreCase = true)) name else "$name.xml"
    }

    private fun queryDisplayName(uri: Uri): String? =
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }

    private fun bindFormattingChecks() {
        try {
            VerselinkPrefs.FLAGS.forEach { flag ->
                val cb = findViewById<CheckBox>(checkboxIdFor(flag.key) ?: return@forEach)
                cb.isChecked = VerselinkPrefs.getFlag(this, flag.key, flag.default)
                cb.setOnCheckedChangeListener { _, checked ->
                    VerselinkPrefs.setFlag(this, flag.key, checked)
                    refreshPreview()
                }
            }
        } catch (e: Exception) {
            CrashLog.log(this, e)
        }
    }

    private fun checkboxIdFor(key: String): Int? = when (key) {
        VerselinkPrefs.KEY_INCLUDE_REFERENCE -> R.id.cb_include_reference
        VerselinkPrefs.KEY_REFERENCE_FIRST_LINE -> R.id.cb_reference_first_line
        VerselinkPrefs.KEY_DYNAMIC_REFERENCE -> R.id.cb_dynamic_reference
        VerselinkPrefs.KEY_VERSE_NUMBERS -> R.id.cb_verse_numbers
        VerselinkPrefs.KEY_NEW_LINE_CHAPTERS -> R.id.cb_new_line_chapters
        VerselinkPrefs.KEY_NEW_LINE_BOOKS -> R.id.cb_new_line_books
        else -> null
    }

    private fun bindSwitchingStatus() {
        try {
            val txt = findViewById<TextView>(R.id.txt_switch_permission_status)
            val granted = checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED
            txt.setText(
                if (granted) R.string.switch_permission_granted
                else R.string.switch_permission_missing
            )
        } catch (e: Exception) {
            CrashLog.log(this, e)
        }
    }

    companion object {
        private const val REQUEST_IMPORT = 41
        /** Sample rendered in the formatting preview. */
        private const val PREVIEW_REFERENCE = "John 3:16-17"
        private const val ALLOWED_NAME_CHARS = "._- "
    }
}
