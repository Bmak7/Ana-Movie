// app/src/main/java/com/faselhd/app/adapters/EpisodeSourceSelectionAdapter.kt
package com.faselhd.app.adapters

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.faselhd.app.models.SEpisode
import com.faselhd.app.models.Video

// Data class to hold the state for each item
data class EpisodeDownloadSelection(
    val episode: SEpisode,
    val sources: List<Video>,
    var selectedSourceIndex: Int = 0
)

class EpisodeSourceSelectionAdapter(
    private val context: Context,
    private val selections: MutableList<EpisodeDownloadSelection>
) : RecyclerView.Adapter<EpisodeSourceSelectionAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_episode_source_selection, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val selection = selections[position]
        holder.bind(selection)
    }

    override fun getItemCount(): Int = selections.size

    fun getFinalSelections(): List<Pair<SEpisode, Video>> {
        return selections.map {
            Pair(it.episode, it.sources[it.selectedSourceIndex])
        }
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleTextView: TextView = itemView.findViewById(R.id.episode_title_text_view)
        private val sourceSpinner: Spinner = itemView.findViewById(R.id.source_spinner)

        fun bind(selection: EpisodeDownloadSelection) {
            titleTextView.text = selection.episode.name

            // Create an ArrayAdapter for the spinner
            val qualityOptions = selection.sources.map { it.quality }
            val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, qualityOptions)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            sourceSpinner.adapter = adapter

            // Set the current selection and listener
            sourceSpinner.setSelection(selection.selectedSourceIndex)
            sourceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    // Update the model when user selects a different source
                    selection.selectedSourceIndex = pos
                }
                override fun onNothingSelected(p0: AdapterView<*>?) {}
            }
        }
    }
}