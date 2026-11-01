package com.rustvnt.remote

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/** Apply appearance before inflation; changing it recreates views while retaining the session model. */
object Appearance {
    private const val PREFS = "vnt_appearance"
    fun mode(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("theme", "system") ?: "system"
    fun apply(context: Context) { applyMode(mode(context)) }
    fun set(context: Context, mode: String) {
        require(mode in listOf("light", "dark", "system"))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("theme", mode).apply()
        applyMode(mode)
    }
    private fun applyMode(mode: String) {
        val value = when (mode) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        if (AppCompatDelegate.getDefaultNightMode() != value) AppCompatDelegate.setDefaultNightMode(value)
    }
}
