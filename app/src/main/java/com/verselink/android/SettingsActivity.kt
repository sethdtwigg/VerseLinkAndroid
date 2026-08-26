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
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import kotlin.concurrent.thread

/**
 * Minimal but functional settings screen - fully crash-proofed.
 * All initialization wrapped; any exception is logged to a persistent file
 * and shown as a Toast instead of crashing the process.
 */
class SettingsActivity : Activity() {

    private lateinit var repository: AssetBibleRepository
    private lateinit var spinner: Spinner
    private val TAG = "VerseLink"

    override fun onCreate(savedInstanceState: Bundle?) {
        // Global safety net - catch ANY exception before super.onCreate completes
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            logCrash(e)
        }

        super.onCreate(savedInstanceState)

        // Fail-safe: if ANYTHING goes wrong, we stay alive and show the error
        try {
            setContentView(R.layout.activity_settings)
            Log.d(TAG, "Settings layout inflated")

            repository = EngineProvider.repository(this)
            Log.d(TAG, "Repository ready")

            bindMasterSwitch()
            bindTranslationSpinner()
            bindImportButton()
            bindFormattingChecks()
            bindSwitchingStatus()

            Log.d(TAG, "All bindings complete")
        } catch (e: Exception) {
            logCrash(e)
            Toast.makeText(this, "Settings error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun logCrash(e: Throwable) {
        Log.e(TAG, "Settings crash", e)
        try {
            val file = File(filesDir, "settings_crash.log")
            FileWriter(file, true).use { fw ->
                PrintWriter(fw).use { pw ->
                    pw.println("=== ${java.util.Date()} ===")
                    e.printStackTrace(pw)
                }
            }
        } catch (_: Exception) { /* best effort */ }
    }

    private fun bindMasterSwitch() {
        try {
            val sw = findViewById<Switch>(R.id.sw_enabled)
            sw.isChecked = VerselinkPrefs.isEnabled(this)
            sw.setOnCheckedChangeListener { _, checked ->
                VerselinkPrefs.setEnabled(this, checked)
            }
        } catch (e: Exception) {
            logCrash(e)
        }
    }

    private fun bindTranslationSpinner() {
        try {
            spinner = findViewById(R.id.spinner_translation)
            val versions = repository.availableVersions()
            val display = versions.ifEmpty { listOf(AssetBibleRepository.DEFAULT_VERSION) }
            spinner.adapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_dropdown_item, display
            )
            val current = VerselinkPrefs.translation(this)
            val idx = display.indexOf(current)
            if (idx >= 0) spinner.setSelection(idx)

            spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    val chosen = spinner.adapter.getItem(position)?.toString() ?: return
                    if (chosen != VerselinkPrefs.translation(this@SettingsActivity)) {
                        thread {
                            VerselinkPrefs.setTranslation(this@SettingsActivity, chosen)
                            runOnUiThread {
                                Toast.makeText(this@SettingsActivity,
                                    getString(R.string.translation_loaded, chosen), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        } catch (e: Exception) {
            logCrash(e)
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
            logCrash(e)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_IMPORT && resultCode == RESULT_OK) {
            val uri: Uri? = data?.data
            if (uri != null) importTranslation(uri)
        }
    }

    private fun importTranslation(uri: Uri) {
        thread(name = "verselink-import") {
            val ok = runCatching {
                val name = queryDisplayName(uri) ?: "IMPORTED_${System.currentTimeMillis()}.xml"
                val safeName = if (name.endsWith(".xml", true)) name else "$name.xml"
                val destDir = File(filesDir, "bibles").apply { mkdirs() }
                contentResolver.openInputStream(uri)!!.use { input ->
                    File(destDir, safeName).outputStream().use { output -> input.copyTo(output) }
                }
                EngineProvider.invalidate()
                val probe = AssetBibleRepository(this).apply { selectedVersion = safeName }
                val valid = probe.verseText("Genesis", 1, 1) != null ||
                    probe.verseText("John", 3, 16) != null ||
                    probe.maxVerse("Psalms", 119) != null
                if (!valid) {
                    File(destDir, safeName).delete()
                    false
                } else true
            }.getOrDefault(false)

            runOnUiThread {
                Toast.makeText(
                    this,
                    if (ok) R.string.import_ok else R.string.import_failed,
                    Toast.LENGTH_LONG
                ).show()
                if (ok) bindTranslationSpinner()
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? =
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }

    private fun bindFormattingChecks() {
        try {
            mapOf(
                R.id.cb_include_reference to "includeReferenceInReplacement",
                R.id.cb_reference_first_line to "referenceOnFirstLine",
                R.id.cb_dynamic_reference to "dynamicReference",
                R.id.cb_verse_numbers to "includeVerseNumbers",
                R.id.cb_new_line_chapters to "newLineBetweenChapters",
                R.id.cb_new_line_books to "newLineBetweenBooks"
            ).forEach { (id, key) ->
                val cb = findViewById<CheckBox>(id)
                cb.isChecked = VerselinkPrefs.getFlag(this, key, key == "includeReferenceInReplacement")
                cb.setOnCheckedChangeListener { _, checked ->
                    VerselinkPrefs.setFlag(this, key, checked)
                }
            }
        } catch (e: Exception) {
            logCrash(e)
        }
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
            logCrash(e)
        }
    }

    companion object {
        private const val REQUEST_IMPORT = 41
    }
}