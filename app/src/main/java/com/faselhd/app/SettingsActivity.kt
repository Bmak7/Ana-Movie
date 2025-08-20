package com.faselhd.app // Use your actual package name

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.myapplication.R // Use your actual R file import
import com.google.android.material.appbar.MaterialToolbar

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    // Handle the back button in the toolbar
    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    // Static function to easily launch this activity from anywhere
    companion object {
        fun newIntent(context: Context): Intent {
            return Intent(context, SettingsActivity::class.java)
        }
    }
}