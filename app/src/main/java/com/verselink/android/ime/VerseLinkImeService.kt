package com.verselink.android.ime

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.verselink.android.R
import com.verselink.android.engine.ReferenceParser
import com.verselink.android.handoff.PendingReplacementStore
import com.verselink.android.util.KeyboardSwitcher
import java.util.concurrent.Executors

/**
 * Minimal single-purpose IME. It is NOT a typing keyboard. Its job is to
 * replace a Bible reference with the full verse text and hand control back to
 * the user's normal keyboard.
 *
 * Three trigger paths, in priority order:
 *
 *  1. HAND-OFF: share-sheet/"VerseLink" trampoline stored a resolved verse in
 *     PendingReplacementStore and activated us. Show preview + Insert.
 *
 *  2. SELECTION SCAN (Windows-hotkey style): the field has selected text that
 *     parses as one or more references -> one tap replaces the selection.
 *
 *  3. CURSOR SCAN: no selection. Read the text immediately BEFORE the cursor
 *     (getTextBeforeCursor) and look for a reference the user just typed -
 *     e.g. type "John 3:16" then tap the VerseLink keyboard: one tap replaces
 *     the typed reference in place.
 *
 * After any insertion we restore the previous keyboard (WRITE_SECURE_SETTINGS
 * path) or fall back to switchToNextInputMethod().
 */
class VerseLinkImeService : InputMethodService() {

    private enum class Mode { IDLE, SCANNING, READY_SELECTION, READY_TOKEN, READY_HANDOFF }

    /** What the Insert action will do when tapped. */
    private data class Action(
        val mode: Mode,
        val replacement: String,
        val matchedRaw: String,
        /** For READY_TOKEN: characters to delete before the cursor. */
        val deleteBefore: Int = 0
    )

    private var action: Action? = null
    private var statusView: TextView? = null
    private var insertButton: Button? = null
    private val main = Handler(Looper.getMainLooper())
    private val scanner = Executors.newSingleThreadExecutor { r ->
        Thread(r, "verselink-ime-scan")
    }

    /**
     * Bumped every time the focused field changes. A scan can take seconds
     * (first-use XML parse), and its result is only meaningful for the field
     * it was started for: applying a stale deleteBefore to a different field
     * eats characters the user never typed.
     */
    @Volatile
    private var sessionId = 0

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
            setBackgroundColor(0xFFF5F5F5.toInt())
        }
        val status = TextView(this).apply { textSize = 14f }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
        }
        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        }
        val insert = Button(this).apply {
            visibility = View.GONE
            setOnClickListener { performCurrentAction() }
        }
        val cancel = Button(this).apply {
            text = context.getString(R.string.cancel)
            setOnClickListener { restoreUserKeyboard() }
        }
        row.addView(spacer)
        row.addView(insert)
        row.addView(cancel)
        root.addView(status)
        root.addView(row)

        statusView = status
        insertButton = insert
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val session = ++sessionId

        // Path 1: explicit hand-off wins - never second-guess it.
        val handedOff = PendingReplacementStore.take(this)
        if (handedOff != null) {
            showReady(Action(Mode.READY_HANDOFF, handedOff, handedOff))
            return
        }

        showScanning()
        // Field inspection + first-time XML parse can take seconds: off-thread.
        scanner.execute {
            val found = findReferenceAtField()
            if (found == null) {
                postForSession(session) { showIdle(getString(R.string.ime_no_reference)) }
                return@execute
            }
            val (raw, deleteBefore) = found
            val engine = com.verselink.android.EngineProvider.get(applicationContext)
            val refs = engine.tryParseReferences(raw)
            val replacement = engine.getReplacementText(refs)
            postForSession(session) {
                if (replacement == null) {
                    showIdle(getString(R.string.verse_not_found, raw))
                } else {
                    showReady(
                        Action(
                            mode = if (deleteBefore > 0) Mode.READY_TOKEN else Mode.READY_SELECTION,
                            replacement = replacement,
                            matchedRaw = raw,
                            deleteBefore = deleteBefore
                        )
                    )
                }
            }
        }
    }

    /** Runs [block] on the main thread only if [session] is still the current one. */
    private fun postForSession(session: Int, block: () -> Unit) {
        main.post { if (session == sessionId) block() }
    }

    /**
     * Inspects the focused field for a usable reference.
     * Returns (referenceText, charsToDeleteBeforeCursor); deleteBefore == 0
     * means "replace the current selection".
     */
    private fun findReferenceAtField(): Pair<String, Int>? {
        val ic = currentInputConnection ?: return null

        // 1) Selected text? Parse it the same way the PROCESS_TEXT path does,
        //    so multi-reference selections ("John 3:16; Romans 8:28") work
        //    here too.
        val selected = ic.getSelectedText(0)?.toString()?.trim()
        if (!selected.isNullOrEmpty()) {
            // A selection means the user has told us what to replace. If it
            // does not parse we stop: falling through to the cursor scan would
            // measure deleteBefore from the SELECTION START and then delete
            // text outside the selection.
            return if (ReferenceParser.parseReferences(selected).isNotEmpty()) selected to 0 else null
        }

        // 2) Text just typed before the cursor. Prefer getTextBeforeCursor
        //    (works in more editors than getExtractedText, incl. many webviews).
        val before = ic.getTextBeforeCursor(CURSOR_WINDOW, 0)?.toString()?.takeIf { it.isNotEmpty() }
            ?: extractedTextBeforeCursor(ic)
            ?: return null

        // Walk backwards from the cursor: the LONGEST suffix that parses as a
        // complete reference wins, so "Romans 8:28-9:1" beats "9:1".
        for (cut in 0..before.length - MIN_REF_LEN) {
            val suffix = before.substring(cut)
            val candidate = suffix.trim()
            if (candidate.length < MIN_REF_LEN) break
            if (ReferenceParser.parseSingleReference(candidate) != null) {
                // Delete from the first non-space character of the candidate up
                // to the cursor. Leading text (and the space separating it) is
                // left alone; the replacement supplies its own reference label.
                val leading = suffix.indexOfFirst { !it.isWhitespace() }
                val start = cut + (if (leading < 0) 0 else leading)
                return candidate to (before.length - start)
            }
        }
        return null
    }

    private fun extractedTextBeforeCursor(ic: android.view.inputmethod.InputConnection): String? {
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return null
        val full = extracted.text ?: return null
        val end = extracted.selectionStart
        return if (end in 1..full.length) full.substring(0, end).toString() else null
    }

    // ---- UI state helpers ----

    private fun showScanning() {
        action = null
        statusView?.text = getString(R.string.ime_scanning)
        insertButton?.visibility = View.GONE
    }

    private fun showIdle(message: String) {
        action = null
        statusView?.text = message
        insertButton?.visibility = View.GONE
    }

    private fun showReady(newAction: Action) {
        action = newAction
        statusView?.text =
            newAction.replacement.take(PREVIEW_LEN) + if (newAction.replacement.length > PREVIEW_LEN) "…" else ""
        insertButton?.apply {
            visibility = View.VISIBLE
            text = getString(R.string.ime_replace_fmt, shorten(newAction.matchedRaw))
        }
    }

    /** Button labels stay short: "John 3:16-17" not the whole selection. */
    private fun shorten(raw: String): String {
        val engine = com.verselink.android.EngineProvider.get(applicationContext)
        return engine.tryParseReference(raw)?.let {
            "${it.book} ${it.chapter}:${it.verseStart}" +
                (it.verseEnd?.let { e -> "-$e" } ?: "")
        } ?: raw.take(20)
    }

    // ---- Actions ----

    private fun performCurrentAction() {
        val a = action ?: return
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        try {
            when (a.mode) {
                Mode.READY_TOKEN -> {
                    ic.deleteSurroundingText(a.deleteBefore, 0)
                    ic.commitText(a.replacement, 1)
                }
                else -> ic.commitText(a.replacement, 1) // replaces any active selection
            }
        } finally {
            ic.endBatchEdit()
        }
        restoreUserKeyboard()
    }

    /**
     * Return control to the user's normal keyboard.
     * Tier 1: write back the snapshotted default IME (needs WRITE_SECURE_SETTINGS).
     * Tier 2: cycle to the next enabled keyboard from inside the IME, where
     *         the system permits switching without extra permissions.
     */
    private fun restoreUserKeyboard() {
        action = null
        requestHideSelf(0)

        when {
            KeyboardSwitcher.restorePreviousIme(this) ->
                toast(R.string.inserted_and_switching_back, Toast.LENGTH_SHORT)

            switchToNextInputMethod(false) ->
                toast(R.string.inserted_and_switching_back, Toast.LENGTH_SHORT)

            else ->
                toast(R.string.switch_back_manually, Toast.LENGTH_LONG)
        }
    }

    private fun toast(resId: Int, duration: Int) {
        Toast.makeText(applicationContext, resId, duration).show()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        sessionId++ // any in-flight scan result is now stale
        action = null
        // Don't leave an unconsumed hand-off for some unrelated future field.
        PendingReplacementStore.clear(this)
    }

    override fun onDestroy() {
        scanner.shutdownNow()
        super.onDestroy()
    }

    companion object {
        /** How far back from the cursor we look for a typed reference. */
        private const val CURSOR_WINDOW = 60
        /** Shortest plausible reference, e.g. "Jn 1:1". */
        private const val MIN_REF_LEN = 5
        private const val PREVIEW_LEN = 120
    }
}
