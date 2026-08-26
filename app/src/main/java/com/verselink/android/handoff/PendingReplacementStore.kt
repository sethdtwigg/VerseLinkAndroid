package com.verselink.android.handoff

import android.content.Context
import android.os.SystemClock

/**
 * Hand-off between the classifier side ("VerseLink" tapped) and the IME side
 * ("insert this verse").
 *
 * Why SharedPreferences rather than just a static field:
 *  - Either component may be killed and recreated independently by the system;
 *    statics die with the process, SharedPreferences survive.
 *  - Both components live in the same process by default, so reads are served
 *    from the framework's in-memory cache - effectively free.
 *
 * The timestamp guards against stale inserts: if the IME never opens (user
 * dismissed the picker) or opens much later for unrelated typing, we do NOT
 * want a surprise verse insertion.
 */
object PendingReplacementStore {

    private const val PREFS = "verselink_handoff"
    private const val KEY_TEXT = "pending_text"
    private const val KEY_TIME = "pending_time"

    /** Ignore pending text older than this (ms) to avoid stale inserts. */
    const val MAX_AGE_MS = 30_000L

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun put(context: Context, text: String) {
        prefs(context).edit()
            .putString(KEY_TEXT, text)
            .putLong(KEY_TIME, SystemClock.elapsedRealtime())
            .apply()
    }

    /** Returns the pending verse text if fresh, without consuming it. */
    fun peek(context: Context): String? {
        val p = prefs(context)
        val text = p.getString(KEY_TEXT, null) ?: return null
        val age = SystemClock.elapsedRealtime() - p.getLong(KEY_TIME, 0L)
        return if (age in 0..MAX_AGE_MS) text else null
    }

    /** Returns the pending verse text if fresh and clears it. */
    fun take(context: Context): String? {
        val text = peek(context) ?: return null
        clear(context)
        return text
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_TEXT).remove(KEY_TIME).apply()
    }
}
