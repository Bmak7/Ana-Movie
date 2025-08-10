package com.faselhd.app.utils

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import com.example.myapplication.R

class SubtitleCustomizationDialog(
    private val context: Context,
    private val onSettingsChanged: (SubtitleSettings) -> Unit
) {

    data class SubtitleSettings(
        val fontSize: Int = 18,
        val fontColor: Int = Color.WHITE,
        val backgroundColor: Int = Color.TRANSPARENT,
        val position: SubtitlePosition = SubtitlePosition.BOTTOM,
        val fontStyle: FontStyle = FontStyle.NORMAL
    )

    enum class SubtitlePosition {
        TOP, MIDDLE, BOTTOM
    }

    enum class FontStyle {
        NORMAL, BOLD, ITALIC
    }

    private var currentSettings = SubtitleSettings()

    fun show() {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_subtitle_customization, null)
        
        val fontSizeSeekBar = dialogView.findViewById<SeekBar>(R.id.seekbar_font_size)
        val fontSizeText = dialogView.findViewById<TextView>(R.id.tv_font_size_value)
        val colorSpinner = dialogView.findViewById<Spinner>(R.id.spinner_font_color)
        val backgroundSpinner = dialogView.findViewById<Spinner>(R.id.spinner_background_color)
        val positionSpinner = dialogView.findViewById<Spinner>(R.id.spinner_position)
        val styleSpinner = dialogView.findViewById<Spinner>(R.id.spinner_font_style)
        val previewText = dialogView.findViewById<TextView>(R.id.tv_subtitle_preview)

        // Setup font size
        fontSizeSeekBar.max = 30
        fontSizeSeekBar.min = 10
        fontSizeSeekBar.progress = currentSettings.fontSize
        fontSizeText.text = "${currentSettings.fontSize}sp"
        
        fontSizeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    currentSettings = currentSettings.copy(fontSize = progress)
                    fontSizeText.text = "${progress}sp"
                    updatePreview(previewText)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Setup color spinners
        setupColorSpinner(colorSpinner, "Font Color") { color ->
            currentSettings = currentSettings.copy(fontColor = color)
            updatePreview(previewText)
        }
        
        setupColorSpinner(backgroundSpinner, "Background") { color ->
            currentSettings = currentSettings.copy(backgroundColor = color)
            updatePreview(previewText)
        }

        // Setup position spinner
        val positions = SubtitlePosition.values().map { it.name.lowercase().capitalize() }
        val positionAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, positions)
        positionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        positionSpinner.adapter = positionAdapter
        positionSpinner.setSelection(currentSettings.position.ordinal)
        
        positionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                currentSettings = currentSettings.copy(position = SubtitlePosition.values()[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Setup style spinner
        val styles = FontStyle.values().map { it.name.lowercase().capitalize() }
        val styleAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, styles)
        styleAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        styleSpinner.adapter = styleAdapter
        styleSpinner.setSelection(currentSettings.fontStyle.ordinal)
        
        styleSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                currentSettings = currentSettings.copy(fontStyle = FontStyle.values()[position])
                updatePreview(previewText)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        updatePreview(previewText)

        AlertDialog.Builder(context)
            .setTitle("Subtitle Settings")
            .setView(dialogView)
            .setPositiveButton("Apply") { _, _ ->
                onSettingsChanged(currentSettings)
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Reset") { _, _ ->
                currentSettings = SubtitleSettings()
                onSettingsChanged(currentSettings)
            }
            .show()
    }

    private fun setupColorSpinner(spinner: Spinner, title: String, onColorSelected: (Int) -> Unit) {
        val colors = mapOf(
            "White" to Color.WHITE,
            "Black" to Color.BLACK,
            "Yellow" to Color.YELLOW,
            "Red" to Color.RED,
            "Green" to Color.GREEN,
            "Blue" to Color.BLUE,
            "Transparent" to Color.TRANSPARENT
        )
        
        val colorNames = colors.keys.toList()
        val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, colorNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedColor = colors[colorNames[position]] ?: Color.WHITE
                onColorSelected(selectedColor)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun updatePreview(previewText: TextView) {
        previewText.textSize = currentSettings.fontSize.toFloat()
        previewText.setTextColor(currentSettings.fontColor)
        previewText.setBackgroundColor(currentSettings.backgroundColor)
        
        when (currentSettings.fontStyle) {
            FontStyle.BOLD -> previewText.setTypeface(null, android.graphics.Typeface.BOLD)
            FontStyle.ITALIC -> previewText.setTypeface(null, android.graphics.Typeface.ITALIC)
            else -> previewText.setTypeface(null, android.graphics.Typeface.NORMAL)
        }
    }
}

