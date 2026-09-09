package com.verselink.android.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.util.Date

/**
 * Persistent crash diagnostics for a side-loaded app, where Play Console
 * reports are not available.
 *
 * The important property is that [install] CHAINS to the handler it replaces.
 * An earlier version installed a handler that only logged and returned: the
 * default handler never ran, so the process was never killed, and a crash on
 * the main thread left the app on screen but frozen, with no crash dialog and
 * no way to restart it short of a force-stop. Logging must never swallow the
 * crash.
 */
object CrashLog {

    private const val TAG = "VerseLink"
    private const val FILE = "verselink_crash.log"

    @Volatile
    private var installed = false

    /** Idempotent; safe to call from every entry point. */
    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val appContext = context.applicationContext
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                log(appContext, error)
                // Hand back to the platform so the process still dies and the
                // system still shows/records the crash.
                previous?.uncaughtException(thread, error)
            }
            installed = true
        }
    }

    /** Appends one throwable to the log file; best effort, never throws. */
    fun log(context: Context, error: Throwable) {
        Log.e(TAG, "VerseLink error", error)
        try {
            val file = File(context.applicationContext.filesDir, FILE)
            FileWriter(file, true).use { fw ->
                PrintWriter(fw).use { pw ->
                    pw.println("=== ${Date()} ===")
                    error.printStackTrace(pw)
                }
            }
        } catch (_: Exception) { /* best effort */ }
    }
}
