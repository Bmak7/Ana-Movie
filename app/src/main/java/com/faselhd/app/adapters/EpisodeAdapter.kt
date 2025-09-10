package com.faselhd.app.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.faselhd.app.models.EpisodeWithHistory
import com.faselhd.app.models.SEpisode

class EpisodeAdapter(
    private val onClick: (SEpisode) -> Unit,
    private val onDownloadClick: (SEpisode) -> Unit
) : ListAdapter<EpisodeWithHistory, EpisodeAdapter.ViewHolder>(EpisodeDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_episode, parent, false)
        return ViewHolder(view, onClick, onDownloadClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        view: View,
        private val onClick: (SEpisode) -> Unit,
        private val onDownloadClick: (SEpisode) -> Unit
    ) : RecyclerView.ViewHolder(view) {

        private val titleTextView: TextView = view.findViewById(R.id.episode_title)
        private val numberTextView: TextView = view.findViewById(R.id.episode_number)
        private val iconImageView: ImageView = view.findViewById(R.id.episode_icon)
        private val progressBar: ProgressBar = view.findViewById(R.id.episode_progress_bar)
        private val infoContainer: LinearLayout = view.findViewById(R.id.episode_info_container)
        private val downloadButton: ImageButton = view.findViewById(R.id.btn_download)
        private val downloadProgress: ProgressBar = view.findViewById(R.id.download_progress_indicator)
        private var currentItem: EpisodeWithHistory? = null

        init {
            itemView.setOnClickListener {
                currentItem?.let { onClick(it.episode) }
            }

            downloadButton.setOnClickListener {
                currentItem?.let {
                    setDownloadingState(true)
                    onDownloadClick(it.episode)
                }
            }
        }

        fun bind(item: EpisodeWithHistory) {
            currentItem = item
            val episode = item.episode
            titleTextView.text = episode.name ?: "Episode"
            numberTextView.text = "Episode ${episode.episode_number.toInt()}"
            val history = item.history
            if (history == null || history.duration <= 0) {
                progressBar.visibility = View.GONE
                iconImageView.setImageResource(R.drawable.ic_play_arrow)
                infoContainer.alpha = 1.0f
            } else {
                val progressPercentage = (history.lastWatchedPosition * 100) / history.duration
                progressBar.visibility = View.VISIBLE
                progressBar.progress = progressPercentage.toInt()
                if (progressPercentage > 90) {
                    iconImageView.setImageResource(R.drawable.done_all_24px)
                    infoContainer.alpha = 0.6f
                } else {
                    iconImageView.setImageResource(R.drawable.ic_play_arrow)
                    infoContainer.alpha = 1.0f
                }
            }
            setDownloadingState(item.isFetchingDownload)
        }

        fun setDownloadingState(isLoading: Boolean) {
            currentItem?.isFetchingDownload = isLoading
            downloadProgress.visibility = if (isLoading) View.VISIBLE else View.GONE
            downloadButton.visibility = if (isLoading) View.INVISIBLE else View.VISIBLE
        }
    }
}

object EpisodeDiffCallback : DiffUtil.ItemCallback<EpisodeWithHistory>() {
    override fun areItemsTheSame(oldItem: EpisodeWithHistory, newItem: EpisodeWithHistory): Boolean {
        return oldItem.episode.url == newItem.episode.url
    }

    override fun areContentsTheSame(oldItem: EpisodeWithHistory, newItem: EpisodeWithHistory): Boolean {
        return oldItem == newItem
    }
}