package com.faselhd.app.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.ImageButton // <-- Make sure this is imported
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.faselhd.app.models.EpisodeWithHistory
import com.faselhd.app.models.SEpisode

class EpisodeAdapter(
    private val onClick: (SEpisode) -> Unit,
    private val onDownloadClick: (SEpisode) -> Unit // <-- ADD THIS NEW ACTION
) : ListAdapter<EpisodeWithHistory, EpisodeAdapter.ViewHolder>(EpisodeDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_episode, parent, false)
        return ViewHolder(view, onClick, onDownloadClick) // <-- PASS IT HERE
    }
    class ViewHolder(
        view: View,
        val onClick: (SEpisode) -> Unit,
        val onDownloadClick: (SEpisode) -> Unit,
    ) : RecyclerView.ViewHolder(view) {
        private val titleTextView: TextView = view.findViewById(R.id.episode_title)
        private val numberTextView: TextView = view.findViewById(R.id.episode_number)
        private val iconImageView: ImageView = view.findViewById(R.id.episode_icon)
        private val progressBar: ProgressBar = view.findViewById(R.id.episode_progress_bar)
        private val infoContainer: LinearLayout = view.findViewById(R.id.episode_info_container)
        private val downloadButton: ImageButton = view.findViewById(R.id.btn_download) // <-- Get the button
        private val downloadProgress: ProgressBar = view.findViewById(R.id.download_progress_indicator) // <-- Get the progress bar
        private var currentItem: EpisodeWithHistory? = null

        init {
            itemView.setOnClickListener {
                currentItem?.let {
                    onClick(it.episode)
                }
            }


            // *** THIS IS THE CRUCIAL FIX ***
            // This handles clicks on ONLY the download button.
            downloadButton.setOnClickListener {
                currentItem?.let {
                    // When clicked, immediately update the UI and then call the activity
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
                // Tidak ada riwayat tontonan
                progressBar.visibility = View.GONE
                iconImageView.setImageResource(R.drawable.ic_play_arrow)
                infoContainer.alpha = 1.0f
            } else {
                // Ada riwayat tontonan
                val progressPercentage = (history.lastWatchedPosition * 100) / history.duration

                progressBar.visibility = View.VISIBLE
                progressBar.progress = progressPercentage.toInt()

                if (progressPercentage > 80) {
                    // Dianggap sudah ditonton
                    iconImageView.setImageResource(R.drawable.done_all_24px) // Ikon centang
                    infoContainer.alpha = 0.6f // Redupkan item
                } else {
                    // Belum selesai
                    iconImageView.setImageResource(R.drawable.ic_play_arrow)
                    infoContainer.alpha = 1.0f
                }
            }

            setDownloadingState(item.isFetchingDownload)

        }

        fun setDownloadingState(isLoading: Boolean) {
            currentItem?.isFetchingDownload = isLoading // Update the state in the item
            if (isLoading) {
                downloadProgress.visibility = View.VISIBLE
                downloadButton.visibility = View.INVISIBLE
            } else {
                downloadProgress.visibility = View.GONE
                downloadButton.visibility = View.VISIBLE
            }
        }
    }




    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item)
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


//package com.faselhd.app.adapters
//
//import android.view.LayoutInflater
//import android.view.View
//import android.view.ViewGroup
//import android.widget.TextView
//import androidx.recyclerview.widget.DiffUtil
//import androidx.recyclerview.widget.ListAdapter
//import androidx.recyclerview.widget.RecyclerView
//import com.example.myapplication.R
//import com.faselhd.app.models.SEpisode
//
//class EpisodeAdapter(
//    private val onItemClick: (SEpisode) -> Unit
//) : ListAdapter<SEpisode, EpisodeAdapter.EpisodeViewHolder>(EpisodeDiffCallback()) {
//
//    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpisodeViewHolder {
//        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_episode, parent, false)
//        return EpisodeViewHolder(view)
//    }
//
//    override fun onBindViewHolder(holder: EpisodeViewHolder, position: Int) {
//        holder.bind(getItem(position))
//    }
//
//    inner class EpisodeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
//        private val episodeTitle: TextView = itemView.findViewById(R.id.episode_title)
//        private val episodeNumber: TextView = itemView.findViewById(R.id.episode_number)
//
//        init {
//            itemView.setOnClickListener {
//                val position = adapterPosition
//                if (position != RecyclerView.NO_POSITION) {
//                    onItemClick(getItem(position))
//                }
//            }
//        }
//
//        fun bind(episode: SEpisode) {
//            episodeTitle.text = episode.name ?: "حلقة غير محددة"
//
//            if (episode.episode_number > 0) {
//                episodeNumber.text = "الحلقة ${episode.episode_number.toInt()}"
//                episodeNumber.visibility = View.VISIBLE
//            } else {
//                episodeNumber.visibility = View.GONE
//            }
//        }
//    }
//
//    private class EpisodeDiffCallback : DiffUtil.ItemCallback<SEpisode>() {
//        override fun areItemsTheSame(oldItem: SEpisode, newItem: SEpisode): Boolean {
//            return oldItem.url == newItem.url
//        }
//
//        override fun areContentsTheSame(oldItem: SEpisode, newItem: SEpisode): Boolean {
//            return oldItem == newItem
//        }
//    }
//}
//
