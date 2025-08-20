package com.faselhd.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import com.example.myapplication.R
import com.faselhd.app.network.AnimeSource
import com.faselhd.app.network.SourceManager
class SettingsFragment : PreferenceFragmentCompat() {

    private val sourceManager by lazy { SourceManager(requireContext().applicationContext) }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey)

        // Find the ListPreference we defined in the XML
        // --- Handle Source Preference (No Changes) ---
        val sourcePreference: ListPreference? = findPreference("source_preference")
        setupSourcePreference(sourcePreference)


        val themePreference: ListPreference? = findPreference("theme_preference")
        setupThemePreference(themePreference)
    }

    private fun setupSourcePreference(preference: ListPreference?) {
        preference?.let {
            val sources = sourceManager.getAllSources()
            val sourceDisplayNames = sources.map { it.displayName }.toTypedArray()
            val sourceKeys = sources.map { it.name }.toTypedArray()

            it.entries = sourceDisplayNames
            it.entryValues = sourceKeys
            it.value = SourceManager.getSelectedSource(requireContext()).name

            it.setOnPreferenceChangeListener { _, newValue ->
                val selectedSourceKey = newValue as String

                // Convert the selected key (e.g., "FASEL_HD") back into an AnimeSource enum object
                val selectedSource = try {
                    AnimeSource.valueOf(selectedSourceKey)
                } catch (e: IllegalArgumentException) {
                    null // Handle case where the key might be invalid
                }

                if (selectedSource != null) {
                    // Save the newly selected source
                    SourceManager.setSelectedSource(requireContext(), selectedSource)

                    Toast.makeText(
                        requireContext(),
                        "Source changed to ${selectedSource.displayName}. App will restart.",
                        Toast.LENGTH_LONG
                    ).show()

                    // Restart the app to apply the changes.
                    // This logic is more robust than `recreate()` as it restarts the entire app task.
                    Handler(Looper.getMainLooper()).postDelayed({
                        val packageManager = requireActivity().packageManager
                        val intent = packageManager.getLaunchIntentForPackage(requireActivity().packageName)
                        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        startActivity(intent!!)
                        requireActivity().finishAffinity() // Ensures all old activities are closed
                    }, 800)
                }
                true // Return true to indicate the change has been handled and should be saved
            }
        }
    }


    // --- THIS IS THE NEW FUNCTION ---
    private fun setupThemePreference(preference: ListPreference?) {
        preference?.setOnPreferenceChangeListener { _, newValue ->
            val themeValue = newValue as String
            when (themeValue) {
                "LIGHT" -> {
                    // Set theme to Light Mode
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                }
                "DARK" -> {
                    // Set theme to Dark Mode
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                }
                "SYSTEM" -> {
                    // Set theme to follow System Settings
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                }
            }
            true // Return true to save the new value
        }
    }
}