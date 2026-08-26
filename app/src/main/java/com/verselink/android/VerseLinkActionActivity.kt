package com.verselink.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import com.verselink.android.handoff.PendingReplacementStore
import com.verselink.android.util.KeyboardSwitcher
import kotlin.concurrent.thread

/**
 * Invisible trampoline that feeds the IME insertion pipeline.
 *
 * Entry points:
 *  - Share sheet ("VerseLink"): registered for ACTION_SEND text/plain in the
 *    manifest. Useful inside apps whose editors ignore PROCESS_TEXT results
 *    (some webviews/browsers): resolve -> store -> activate VerseLink IME.
 *  - Explicit intents from our own UI (settings test button etc.) using
 *    [EXTRA_SELECTION].
 *
 * Sequence once launched:
 *  1. Resolve the verse text (may parse 4.7 MB XML on very first use, hence
 *     the worker thread).
 *  2. Snapshot the user's current keyboard so we can restore it later.
 *  3. Store the replacement text for the IME (PendingReplacementStore).
 *  4. Activate the VerseLink IME directly if WRITE_SECURE_SETTINGS was
 *     granted via adb; otherwise open the system IME picker and tell the
 *     user what to tap.
 */
class VerseLinkActionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val selection = intent?.getStringExtra(EXTRA_SELECTION)
            ?: intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            ?: intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            ?: ""

        if (!VerselinkPrefs.isEnabled(this) || selection.isBlank()) {
            finish()
            return
        }

        Toast.makeText(this, R.string.resolving_reference, Toast.LENGTH_SHORT).show()

        thread(name = "verselink-resolve") {
            val engine = EngineProvider.get(this)
            val refs = engine.tryParseReferences(selection)
            val replacement = engine.getReplacementText(refs)

            runOnUiThread {
                when {
                    refs.isEmpty() || replacement == null -> Toast.makeText(
                        this,
                        getString(R.string.verse_not_found, selection),
                        Toast.LENGTH_LONG
                    ).show()

                    else -> handOffToIme(replacement)
                }
                finish()
            }
        }
    }

    private fun handOffToIme(replacementText: String) {
        // Snapshot BEFORE switching away from the user's normal keyboard.
        KeyboardSwitcher.recordPreviousIme(this)

        PendingReplacementStore.put(this, replacementText)

        val activated = KeyboardSwitcher.activateVerselinkIme(this)
        if (!activated) {
            // Fallback: let the user pick VerseLink from the system picker.
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
            Toast.makeText(
                this,
                R.string.pick_verselink_keyboard,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    companion object {
        const val EXTRA_SELECTION = "com.verselink.android.EXTRA_SELECTION"
    }
}
