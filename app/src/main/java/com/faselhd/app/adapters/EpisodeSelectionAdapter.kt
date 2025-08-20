package com.faselhd.app.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.myapplication.R
import com.faselhd.app.models.SEpisode

// Data class to hold the episode and its selection state
data class SelectableEpisode(val episode: SEpisode, var isSelected: Boolean = false)

class EpisodeSelectionAdapter : ListAdapter<SelectableEpisode, EpisodeSelectionAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_episode_selectable, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    fun getSelectedEpisodes(): List<SEpisode> {
        return currentList.filter { it.isSelected }.map { it.episode }
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumbnail: ImageView = itemView.findViewById(R.id.episode_thumbnail)
        private val name: TextView = itemView.findViewById(R.id.episode_name)
        private val overlay: View = itemView.findViewById(R.id.selection_overlay)
        private val checkmark: ImageView = itemView.findViewById(R.id.selection_checkmark)

        init {
            itemView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val item = getItem(position)
                    item.isSelected = !item.isSelected
                    notifyItemChanged(position)
                }
            }
        }

        fun bind(item: SelectableEpisode) {
            name.text = item.episode.name?.substringAfter(":")?.trim()
            Glide.with(itemView.context).load(item.episode.thumbnailUrl).into(thumbnail)

            if (item.isSelected) {
                overlay.visibility = View.VISIBLE
                checkmark.visibility = View.VISIBLE
            } else {
                overlay.visibility = View.GONE
                checkmark.visibility = View.GONE
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<SelectableEpisode>() {
        override fun areItemsTheSame(old: SelectableEpisode, new: SelectableEpisode): Boolean = old.episode.url == new.episode.url
        override fun areContentsTheSame(old: SelectableEpisode, new: SelectableEpisode): Boolean = old.isSelected == new.isSelected
    }
}