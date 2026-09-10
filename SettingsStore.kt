package com.peter.minimal

import android.content.Context
import android.content.SharedPreferences

/**
 * Minimal, unencrypted storage for the Gemini API key.
 *
 * This is intentionally simple: it's a free-tier API key from
 * aistudio.google.com, not a payment credential. If you want stronger
 * protection later, this is the place to swap in
 * EncryptedSharedPreferences (androidx.security:security-crypto), but that
 * adds a dependency and setup complexity not needed for a first working
 * version.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("peter_settings", Context.MODE_PRIVATE)

    fun getGeminiApiKey(): String? =
        prefs.getString(KEY_GEMINI_API_KEY, null)

    fun setGeminiApiKey(key: String) {
        prefs.edit().putString(KEY_GEMINI_API_KEY, key).apply()
    }

    fun hasGeminiApiKey(): Boolean =
        !getGeminiApiKey().isNullOrBlank()

    companion object {
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
    }
}
