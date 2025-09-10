package com.faselhd.app.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.myapplication.R
import com.faselhd.app.models.EpisodeWithHistory
import com.faselhd.app.models.SEpisode

class EpisodeDetailsAdapter(
    private val onItemClick: (SEpisode) -> Unit
) : ListAdapter<EpisodeWithHistory, EpisodeDetailsAdapter.ViewHolder>(EpisodeDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_episode_horizontal, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumbnail: ImageView = itemView.findViewById(R.id.episode_thumbnail)
        private val name: TextView = itemView.findViewById(R.id.episode_name)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.episode_progress_bar)

        init {
            itemView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(getItem(position).episode)
                }
            }
        }

        fun bind(item: EpisodeWithHistory) {
            val episode = item.episode
            name.text = episode.name?.substringAfter(":")?.trim() ?: "Episode"

            Glide.with(itemView.context)
                .load(episode.thumbnailUrl)
                .placeholder(R.drawable.placeholder_anime)
                .error(R.drawable.placeholder_anime)
                .into(thumbnail)

            val history = item.history
            if (history != null && history.duration > 0) {
                val progressPercentage = (history.lastWatchedPosition * 100) / history.duration
                progressBar.visibility = View.VISIBLE
                progressBar.progress = progressPercentage.toInt()
                itemView.alpha = if (progressPercentage > 90) 0.6f else 1.0f
            } else {
                progressBar.visibility = View.GONE
                itemView.alpha = 1.0f
            }
        }
    }
}