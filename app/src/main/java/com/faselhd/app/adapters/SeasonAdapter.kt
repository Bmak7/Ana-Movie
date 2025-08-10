package com.faselhd.app.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.google.android.material.button.MaterialButton

class SeasonAdapter(
    private val onSeasonClicked: (seasonName: String) -> Unit
) : RecyclerView.Adapter<SeasonAdapter.SeasonViewHolder>() {

    private var seasons: List<String> = emptyList()
    private var selectedPosition = 0

    fun submitList(newSeasons: List<String>) {
        seasons = newSeasons
        selectedPosition = 0
        notifyDataSetChanged()
    }

    fun getSelectedSeason(): String? {
        return if (seasons.isNotEmpty() && selectedPosition < seasons.size) {
            seasons[selectedPosition]
        } else {
            null
        }
    }

    fun setSelectedSeason(seasonName: String) {
        val newPosition = seasons.indexOf(seasonName)
        if (newPosition != -1 && newPosition != selectedPosition) {
            val previousPosition = selectedPosition
            selectedPosition = newPosition
            notifyItemChanged(previousPosition)
            notifyItemChanged(newPosition)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SeasonViewHolder {
        val button = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_season_chip, parent, false) as MaterialButton
        return SeasonViewHolder(button)
    }

    override fun onBindViewHolder(holder: SeasonViewHolder, position: Int) {
        val seasonName = seasons[position]
        holder.bind(seasonName, position == selectedPosition)

        holder.itemView.setOnClickListener {
            val currentPosition = holder.adapterPosition
            if (currentPosition != RecyclerView.NO_POSITION && currentPosition != selectedPosition) {
                val previousPosition = selectedPosition
                selectedPosition = currentPosition

                notifyItemChanged(previousPosition)
                notifyItemChanged(selectedPosition)

                onSeasonClicked(seasons[currentPosition])
            }
        }
    }

    override fun getItemCount(): Int = seasons.size

    inner class SeasonViewHolder(private val button: MaterialButton) : RecyclerView.ViewHolder(button) {
        fun bind(seasonName: String, isSelected: Boolean) {
            button.text = seasonName
            // Manually change the style for selection since buttons don't have a 'checked' state
            if (isSelected) {
                // Use a "contained" style for the selected button
                button.setBackgroundColor(ContextCompat.getColor(itemView.context, R.color.md_theme_light_primary))
                button.setTextColor(ContextCompat.getColor(itemView.context, R.color.md_theme_light_onPrimary))
            } else {
                // Use a "text" or "outlined" style for unselected buttons
                button.setBackgroundColor(ContextCompat.getColor(itemView.context, R.color.md_theme_light_surface))
                button.setTextColor(ContextCompat.getColor(itemView.context, R.color.md_theme_light_onSurface))
            }
        }
    }
}