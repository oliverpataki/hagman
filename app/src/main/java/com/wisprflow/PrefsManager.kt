package com.wisprflow

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

class PrefsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("wispr_prefs", Context.MODE_PRIVATE)

    var claudeApiKey: String
        get() = prefs.getString("claude_api_key", "") ?: ""
        set(value) = prefs.edit().putString("claude_api_key", value).apply()

    var aiCleanupEnabled: Boolean
        get() = prefs.getBoolean("ai_cleanup_enabled", false)
        set(value) = prefs.edit().putBoolean("ai_cleanup_enabled", value).apply()

    var languageCode: String
        get() = prefs.getString("language_code", "cs-CZ") ?: "cs-CZ"
        set(value) = prefs.edit().putString("language_code", value).apply()

    fun addHistoryEntry(text: String) {
        val history = getHistory().toMutableList()
        val timestamp = System.currentTimeMillis()
        history.add(0, "$timestamp|$text")
        if (history.size > 50) history.subList(50, history.size).clear()
        val arr = JSONArray()
        history.forEach { arr.put(it) }
        prefs.edit().putString("history", arr.toString()).apply()
    }

    fun getHistory(): List<String> {
        val raw = prefs.getString("history", "[]") ?: "[]"
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { arr.getString(it) }
    }

    fun clearHistory() {
        prefs.edit().remove("history").apply()
    }
}
