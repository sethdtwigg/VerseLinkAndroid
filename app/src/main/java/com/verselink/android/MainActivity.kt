package com.verselink.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.verselink.android.util.KeyboardSwitcher
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter

/**
 * Launcher / onboarding screen: shows whether the optional VerseLink IME is
 * enabled, links to system keyboard settings, and explains the basic flow.
 * Fully crash-proofed.
 */
class MainActivity : Activity() {

    private lateinit var imeStatus: TextView
    private lateinit var btnEnableIme: Button
    private val TAG = "VerseLink"

    override fun onCreate(savedInstanceState: Bundle?) {
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            logCrash(e)
        }

        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)
            Log.d(TAG, "Main layout inflated")

            imeStatus = findViewById(R.id.ime_status)
            btnEnableIme = findViewById(R.id.btn_enable_ime)
            val btnSettings = findViewById<Button>(R.id.btn_settings)

            btnEnableIme.setOnClickListener {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
            btnSettings.setOnClickListener {
                try {
                    startActivity(Intent(this, SettingsActivity::class.java))
                } catch (e: Exception) {
                    logCrash(e)
                    Toast.makeText(this, "Settings failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

            refreshStatus()
        } catch (e: Exception) {
            logCrash(e)
            Toast.makeText(this, "App error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        try { refreshStatus() } catch (e: Exception) { logCrash(e) }
    }

    private fun refreshStatus() {
        try {
            val enabled = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_INPUT_METHODS
            ).orEmpty()

            val ours = KeyboardSwitcher.verselinkImeId(this)
            val isEnabled = enabled.split(":").any { it.equals(ours, ignoreCase = true) }

            imeStatus.setText(
                if (isEnabled) R.string.ime_enabled_status else R.string.ime_disabled_status
            )
            btnEnableIme.visibility = View.VISIBLE
        } catch (e: Exception) {
            logCrash(e)
        }
    }

    private fun logCrash(e: Throwable) {
        Log.e(TAG, "Main crash", e)
        try {
            val file = File(filesDir, "main_crash.log")
            FileWriter(file, true).use { fw ->
                PrintWriter(fw).use { pw ->
                    pw.println("=== ${java.util.Date()} ===")
                    e.printStackTrace(pw)
                }
            }
        } catch (_: Exception) { /* best effort */ }
    }
}