package com.verselink.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * Standard ACTION_PROCESS_TEXT handler: shows "VerseLink" in the selection
 * overflow menu on EVERY Android 6+ device with zero special setup.
 *
 * The platform replaces the selected text automatically when this activity
 * returns RESULT_OK with Intent.EXTRA_PROCESS_TEXT - no IME involvement at
 * all. This is the reliable path; the classifier+IME hybrid is the premium
 * path for devices where the system classifier can be replaced.
 */
class ProcessTextActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val selected: String? = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()

        if (intent?.action != Intent.ACTION_PROCESS_TEXT ||
            !VerselinkPrefs.isEnabled(this) || selected.isNullOrBlank()
        ) {
            // Nothing to do: return the input unchanged so the field keeps it.
            finishWithText(selected.orEmpty())
            return
        }

        thread(name = "verselink-processtext") {
            val engine = EngineProvider.get(this)
            val refs = engine.tryParseReferences(selected)
            val replacement = engine.getReplacementText(refs)

            runOnUiThread {
                if (replacement == null) {
                    Toast.makeText(
                        this,
                        getString(R.string.verse_not_found, selected),
                        Toast.LENGTH_SHORT
                    ).show()
                    finishWithText(selected) // unchanged
                } else {
                    finishWithText(replacement)
                }
            }
        }
    }

    /** RESULT_OK + EXTRA_PROCESS_TEXT makes TextView/EditText swap the selection. */
    private fun finishWithText(text: String) {
        setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, text))
        finish()
    }
}
