package com.faselhd.app.utils

import android.content.Context
import androidx.preference.PreferenceManager

object PlayerSettingsManager {

    private const val KEY_AUTO_PLAY = "auto_play_next_episode"
    private const val KEY_AUTO_SKIP = "auto_skip_intro"
    private const val KEY_DEFAULT_SPEED = "default_playback_speed"
    private const val KEY_DEFAULT_QUALITY = "default_video_quality"
    private const val PREF_REMEMBER_QUALITY = "remember_quality_choice"
    private const val PREF_LAST_SELECTED_QUALITY = "last_selected_quality"

    fun shouldRememberQualityChoice(context: Context): Boolean {
        val prefs = context.getSharedPreferences("player_settings", Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_REMEMBER_QUALITY, false)
    }

    fun setRememberQualityChoice(context: Context, remember: Boolean) {
        val prefs = context.getSharedPreferences("player_settings", Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_REMEMBER_QUALITY, remember).apply()
    }

    fun getLastSelectedQuality(context: Context): String {
        val prefs = context.getSharedPreferences("player_settings", Context.MODE_PRIVATE)
        return prefs.getString(PREF_LAST_SELECTED_QUALITY, "auto") ?: "auto"
    }

    fun setLastSelectedQuality(context: Context, quality: String) {
        val prefs = context.getSharedPreferences("player_settings", Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_LAST_SELECTED_QUALITY, quality).apply()
    }
    fun isAutoPlayEnabled(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean(KEY_AUTO_PLAY, true) // Default to true
    }

    fun isAutoSkipEnabled(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean(KEY_AUTO_SKIP, false) // Default to false
    }

    fun getDefaultPlaybackSpeed(context: Context): Float {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val speedString = prefs.getString(KEY_DEFAULT_SPEED, "1.0") ?: "1.0"
        return speedString.toFloatOrNull() ?: 1.0f
    }

    fun getDefaultVideoQuality(context: Context): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        // "auto" is a good default so the player can choose the best quality
        return prefs.getString(KEY_DEFAULT_QUALITY, "auto") ?: "auto"
    }
}