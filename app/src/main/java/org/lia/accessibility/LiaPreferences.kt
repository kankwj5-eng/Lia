package org.lia.accessibility

import android.content.Context

class LiaPreferences(context: Context) {
    private val preferences =
        context.getSharedPreferences("lia_preferences", Context.MODE_PRIVATE)

    var voiceProtectionEnabled: Boolean
        get() = preferences.getBoolean(KEY_VOICE_PROTECTION, false)
        set(value) {
            preferences.edit().putBoolean(KEY_VOICE_PROTECTION, value).apply()
        }

    var bubbleEnabled: Boolean
        get() = preferences.getBoolean(KEY_BUBBLE_ENABLED, true)
        set(value) {
            preferences.edit().putBoolean(KEY_BUBBLE_ENABLED, value).apply()
        }

    var alwaysListeningEnabled: Boolean
        get() = preferences.getBoolean(KEY_ALWAYS_LISTENING, false)
        set(value) {
            preferences.edit().putBoolean(KEY_ALWAYS_LISTENING, value).apply()
        }

    companion object {
        private const val KEY_VOICE_PROTECTION = "voice_protection_enabled"
        private const val KEY_BUBBLE_ENABLED = "bubble_enabled"
        private const val KEY_ALWAYS_LISTENING = "always_listening_enabled"
    }
}
