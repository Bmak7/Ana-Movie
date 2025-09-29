package com.faselhd.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.*
import com.example.myapplication.R
import com.faselhd.app.network.AnimeSource
import com.faselhd.app.network.SourceManager
import com.faselhd.app.utils.DatabaseHelper // Import the helper
import com.faselhd.app.utils.VideoCacheManager
import android.text.InputType
import androidx.appcompat.app.AlertDialog

class SettingsFragment : PreferenceFragmentCompat() {

    private val sourceManager by lazy { SourceManager(requireContext().applicationContext) }
    private val databaseHelper by lazy { DatabaseHelper(requireContext()) } // Instantiate the helper

    // Launcher for exporting the database
    private val exportDbLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument()) { uri ->
        uri?.let {
            if (databaseHelper.performExport(it)) {
                Toast.makeText(requireContext(), "Database exported successfully.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Database export failed.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Launcher for importing the database
    private val importDbLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            if (databaseHelper.performImport(it)) {
                Toast.makeText(requireContext(), "Database imported successfully. App will restart.", Toast.LENGTH_LONG).show()
                // Restart the app to load the new database
                Handler(Looper.getMainLooper()).postDelayed({
                    val packageManager = requireActivity().packageManager
                    val intent = packageManager.getLaunchIntentForPackage(requireActivity().packageName)
                    intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    startActivity(intent!!)
                    requireActivity().finishAffinity()
                }, 1000)
            } else {
                Toast.makeText(requireContext(), "Database import failed. Please restart the app.", Toast.LENGTH_LONG).show()
            }
        }
    }


    private fun showSecretCodeDialog() {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle("Enter Secret Code")

        val input = EditText(requireContext())
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        builder.setView(input)

        builder.setPositiveButton("OK") { dialog, _ ->
            val code = input.text.toString()
            if (code == "******") {
                SourceManager.setAdultContentUnlocked(requireContext(), true)
                Toast.makeText(requireContext(), "Adult content unlocked. Please restart the app.", Toast.LENGTH_LONG).show()
                // Restart the app to apply changes
                Handler(Looper.getMainLooper()).postDelayed({
                    val packageManager = requireActivity().packageManager
                    val intent = packageManager.getLaunchIntentForPackage(requireActivity().packageName)
                    intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    startActivity(intent!!)
                    requireActivity().finishAffinity()
                }, 800)
            } else {
                Toast.makeText(requireContext(), "Invalid code", Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }
        builder.setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }

        builder.show()
    }
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey)

        setupSourcePreference()
        setupThemePreference()
        setupCachePreferences()
        setupPlayerPreferences()
        setupAdvancedPreferences() // Add this call
        setupSecretPreference()

    }

    // New function to handle advanced settings like import/export
    private fun setupAdvancedPreferences() {
        val exportPref: Preference? = findPreference("export_settings")
        exportPref?.setOnPreferenceClickListener {
            databaseHelper.exportDatabase(exportDbLauncher)
            true
        }

        val importPref: Preference? = findPreference("import_settings")
        importPref?.setOnPreferenceClickListener {
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Import Database")
                .setMessage("Are you sure? Importing a database will overwrite all your current history, favorites, and downloads. This action cannot be undone.")
                .setPositiveButton("Import") { _, _ ->
                    databaseHelper.importDatabase(importDbLauncher)
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }
    }

    private fun setupSecretPreference() {
        val secretPreference: Preference? = findPreference("secret_code_preference")
        secretPreference?.setOnPreferenceClickListener {
            showSecretCodeDialog()
            true
        }
    }

    private fun setupSourcePreference() {
        val sourcePreference: ListPreference? = findPreference("source_preference")
        sourcePreference?.let {
            val sources = sourceManager.getAllSources()
            val sourceDisplayNames = sources.map { it.displayName }.toTypedArray()
            val sourceKeys = sources.map { it.name }.toTypedArray()

            it.entries = sourceDisplayNames
            it.entryValues = sourceKeys
            it.value = SourceManager.getSelectedSource(requireContext()).name

            it.setOnPreferenceChangeListener { _, newValue ->
                val selectedSourceKey = newValue as String

                val selectedSource = try {
                    AnimeSource.valueOf(selectedSourceKey)
                } catch (e: IllegalArgumentException) {
                    null
                }

                if (selectedSource != null) {
                    SourceManager.setSelectedSource(requireContext(), selectedSource)

                    Toast.makeText(
                        requireContext(),
                        "Source changed to ${selectedSource.displayName}. App will restart.",
                        Toast.LENGTH_LONG
                    ).show()

                    Handler(Looper.getMainLooper()).postDelayed({
                        val packageManager = requireActivity().packageManager
                        val intent = packageManager.getLaunchIntentForPackage(requireActivity().packageName)
                        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        startActivity(intent!!)
                        requireActivity().finishAffinity()
                    }, 800)
                }
                true
            }
        }
    }

    private fun setupThemePreference() {
        val themePreference: ListPreference? = findPreference("theme_preference")
        themePreference?.setOnPreferenceChangeListener { _, newValue ->
            val themeValue = newValue as String
            when (themeValue) {
                "LIGHT" -> {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                }
                "DARK" -> {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                }
                "SYSTEM" -> {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                }
            }
            true
        }
    }

    private fun setupCachePreferences() {
        // Cache enabled/disabled toggle
        val cacheEnabledPref: SwitchPreferenceCompat? = findPreference("cache_enabled")
        cacheEnabledPref?.let { pref ->
            pref.isChecked = VideoCacheManager.isCacheEnabled(requireContext())
            pref.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                VideoCacheManager.setCacheEnabled(requireContext(), enabled)
                updateCachePreferencesVisibility(enabled)

                if (enabled) {
                    Toast.makeText(requireContext(), "Video caching enabled", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Video caching disabled and cache cleared", Toast.LENGTH_SHORT).show()
                }
                true
            }
        }

        // Cache size setting
        val cacheSizePref: ListPreference? = findPreference("cache_size")
        cacheSizePref?.let { pref ->
            val currentSize = VideoCacheManager.getCacheSizeMB(requireContext())
            pref.value = currentSize.toString()
            updateCacheSizeSummary(pref, currentSize)

            pref.setOnPreferenceChangeListener { _, newValue ->
                val sizeMB = (newValue as String).toLong()
                VideoCacheManager.setCacheSizeMB(requireContext(), sizeMB)
                updateCacheSizeSummary(pref, sizeMB)
                Toast.makeText(requireContext(), "Cache size updated to ${sizeMB}MB", Toast.LENGTH_SHORT).show()
                true
            }
        }

        // WiFi-only caching
        val wifiOnlyPref: SwitchPreferenceCompat? = findPreference("cache_wifi_only")
        wifiOnlyPref?.let { pref ->
            pref.isChecked = VideoCacheManager.isCacheWifiOnly(requireContext())
            pref.setOnPreferenceChangeListener { _, newValue ->
                VideoCacheManager.setCacheWifiOnly(requireContext(), newValue as Boolean)
                true
            }
        }

        // Auto-cache next episode
        val autoCachePref: SwitchPreferenceCompat? = findPreference("auto_cache_next_episode")
        autoCachePref?.let { pref ->
            pref.isChecked = VideoCacheManager.isAutoCacheNextEpisode(requireContext())
            pref.setOnPreferenceChangeListener { _, newValue ->
                VideoCacheManager.setAutoCacheNextEpisode(requireContext(), newValue as Boolean)
                true
            }
        }

        // Cache info display
        val cacheInfoPref: Preference? = findPreference("cache_info")
        cacheInfoPref?.let { pref ->
            updateCacheInfoSummary(pref)
            pref.setOnPreferenceClickListener {
                updateCacheInfoSummary(pref)
                true
            }
        }

        // Clear cache button
        val clearCachePref: Preference? = findPreference("clear_cache")
        clearCachePref?.setOnPreferenceClickListener {
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Clear Cache")
                .setMessage("Are you sure you want to clear all cached video data? This will free up storage space but videos may take longer to load.")
                .setPositiveButton("Clear") { _, _ ->
                    VideoCacheManager.clearCache(requireContext())
                    updateCacheInfoSummary(cacheInfoPref)
                    Toast.makeText(requireContext(), "Cache cleared successfully", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }

        // Update initial visibility
        updateCachePreferencesVisibility(VideoCacheManager.isCacheEnabled(requireContext()))
    }

    private fun setupPlayerPreferences() {
        // Auto-play next episode
        val autoPlayPref: SwitchPreferenceCompat? = findPreference("auto_play_next_episode")
        autoPlayPref?.let { pref ->
            // Load saved preference (you'll need to implement this in your preferences)
            pref.setOnPreferenceChangeListener { _, newValue ->
                // Save the preference
                true
            }
        }

        // Skip intro/outro automatically
        val autoSkipPref: SwitchPreferenceCompat? = findPreference("auto_skip_intro")
        autoSkipPref?.let { pref ->
            pref.setOnPreferenceChangeListener { _, newValue ->
                // Save the preference
                true
            }
        }

        // Default playback speed
        val playbackSpeedPref: ListPreference? = findPreference("default_playback_speed")
        playbackSpeedPref?.let { pref ->
            pref.setOnPreferenceChangeListener { _, newValue ->
                val speed = newValue as String
                Toast.makeText(requireContext(), "Default playback speed set to ${speed}x", Toast.LENGTH_SHORT).show()
                true
            }
        }

        // Default video quality
        val videoQualityPref: ListPreference? = findPreference("default_video_quality")
        videoQualityPref?.let { pref ->
            pref.setOnPreferenceChangeListener { _, newValue ->
                val quality = newValue as String
                Toast.makeText(requireContext(), "Default video quality set to $quality", Toast.LENGTH_SHORT).show()
                true
            }
        }

        // Hardware acceleration
        val hardwareAccelPref: SwitchPreferenceCompat? = findPreference("hardware_acceleration")
        hardwareAccelPref?.let { pref ->
            pref.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                Toast.makeText(
                    requireContext(),
                    if (enabled) "Hardware acceleration enabled" else "Hardware acceleration disabled",
                    Toast.LENGTH_SHORT
                ).show()
                true
            }
        }
    }

    private fun updateCachePreferencesVisibility(enabled: Boolean) {
        val cacheCategory = findPreference<PreferenceCategory>("cache_category")
        cacheCategory?.let { category ->
            for (i in 0 until category.preferenceCount) {
                val pref = category.getPreference(i)
                if (pref.key != "cache_enabled") {
                    pref.isEnabled = enabled
                }
            }
        }
    }

    private fun updateCacheSizeSummary(pref: ListPreference, sizeMB: Long) {
        val usageInfo = VideoCacheManager.getFormattedCacheSize(requireContext())
        pref.summary = "Current: $usageInfo"
    }

    private fun updateCacheInfoSummary(pref: Preference?) {
        pref?.let {
            val formattedSize = VideoCacheManager.getFormattedCacheSize(requireContext())
            val usagePercentage = VideoCacheManager.getCacheUsagePercentage(requireContext())
            val isHealthy = VideoCacheManager.isCacheDirectoryHealthy(requireContext())

            val status = if (isHealthy) "Healthy" else "Error"
            it.summary = "$formattedSize ($usagePercentage%) - Status: $status"
        }
    }

    override fun onResume() {
        super.onResume()
        // Update cache info when returning to settings
        val cacheInfoPref: Preference? = findPreference("cache_info")
        updateCacheInfoSummary(cacheInfoPref)
    }
}