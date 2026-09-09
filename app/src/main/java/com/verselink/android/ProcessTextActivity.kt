package com.verselink.android

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
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
 * all. This is the reliable path; the IME is the fallback for editors that
 * ignore the returned text.
 *
 * Read-only sources (EXTRA_PROCESS_TEXT_READONLY) cannot be written back to,
 * so there the verse goes to the clipboard instead of being silently dropped.
 */
class ProcessTextActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val selected: String? = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        val readOnly = intent?.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false) ?: false

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
                when {
                    replacement == null -> {
                        Toast.makeText(
                            this,
                            getString(R.string.verse_not_found, selected),
                            Toast.LENGTH_SHORT
                        ).show()
                        finishWithText(selected) // unchanged
                    }
                    readOnly -> {
                        // Nothing else can happen here, so say what we did.
                        copyToClipboard(replacement)
                        Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    else -> {
                        // Opt-in safety net for editors that drop the result.
                        // No toast: the replacement itself is the feedback, and
                        // Android 13+ already confirms clipboard writes.
                        if (VerselinkPrefs.alsoCopyToClipboard(this)) copyToClipboard(replacement)
                        finishWithText(replacement)
                    }
                }
            }
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
    }

    /** RESULT_OK + EXTRA_PROCESS_TEXT makes TextView/EditText swap the selection. */
    private fun finishWithText(text: String) {
        setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, text))
        finish()
    }
}
