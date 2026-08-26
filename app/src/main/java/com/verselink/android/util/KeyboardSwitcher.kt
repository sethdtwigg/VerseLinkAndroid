package com.verselink.android.util

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import com.verselink.android.ime.VerseLinkImeService

/**
 * Keyboard activation/restore helpers.
 *
 * Android's security model does NOT let an ordinary app silently switch the
 * active IME or discover which keyboard was previously in use. Two-tier
 * strategy:
 *
 *  Tier 1 (best): if the user granted WRITE_SECURE_SETTINGS once via adb
 *      (documented in the README + settings screen), we can read/write
 *      Settings.Secure.DEFAULT_INPUT_METHOD directly:
 *        - before activating VerseLink we snapshot the current default IME,
 *        - after insertion we write it back, restoring Gboard etc.
 *
 *  Tier 2 (always available fallback): showInputMethodPicker() lets the user
 *      tap "VerseLink" manually; and from inside our IME we can call
 *      switchToNextInputMethod() to hop back.
 */
object KeyboardSwitcher {

    private const val PREFS = "verselink_keyboard"
    private const val KEY_PREVIOUS_IME = "previous_ime"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun verselinkImeId(context: Context): String =
        ComponentName(context, VerseLinkImeService::class.java).flattenToString()

    fun currentImeId(context: Context): String? =
        Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD
        )

    /** True when tier-1 direct switching is possible on this device. */
    fun canDirectSwitch(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Snapshot the user's normal keyboard before we hijack input. Called by
     * VerseLinkActionActivity right before activating our IME; ignored when
     * the current default is already VerseLink.
     */
    fun recordPreviousIme(context: Context) {
        val current = currentImeId(context) ?: return
        if (current != verselinkImeId(context)) {
            prefs(context).edit().putString(KEY_PREVIOUS_IME, current).apply()
        }
    }

    fun previousImeId(context: Context): String? {
        val saved = prefs(context).getString(KEY_PREVIOUS_IME, null)
        return saved?.takeIf { it != verselinkImeId(context) }
    }

    /** Activates the VerseLink IME directly when permitted. Returns success. */
    fun activateVerselinkIme(context: Context): Boolean {
        if (!canDirectSwitch(context)) return false
        return try {
            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD,
                verselinkImeId(context)
            )
            true
        } catch (e: SecurityException) {
            false
        }
    }

    /** Restores the snapshotted keyboard. Returns success. */
    fun restorePreviousIme(context: Context): Boolean {
        val target = previousImeId(context) ?: return false
        if (!canDirectSwitch(context)) return false
        return try {
            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD,
                target
            )
            true
        } catch (e: SecurityException) {
            false
        }
    }
}
