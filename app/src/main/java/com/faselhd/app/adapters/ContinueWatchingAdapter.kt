


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
import com.faselhd.app.models.WatchHistory

class ContinueWatchingAdapter(
    private val onClick: (WatchHistory) -> Unit
) : ListAdapter<WatchHistory, ContinueWatchingAdapter.ViewHolder>(WatchHistoryDiffCallback) {

    class ViewHolder(view: View, val onClick: (WatchHistory) -> Unit) : RecyclerView.ViewHolder(view) {
        private val titleTextView: TextView = view.findViewById(R.id.anime_title_text)
        private val episodeTextView: TextView = view.findViewById(R.id.episode_title)
        private val imageView: ImageView = view.findViewById(R.id.episode_icon)
        private val progressBar: ProgressBar = view.findViewById(R.id.episode_progress_bar)
        private var currentItem: WatchHistory? = null

        init {
            itemView.setOnClickListener {
                currentItem?.let {
                    onClick(it)
                }
            }
        }

        fun bind(item: WatchHistory) {
            currentItem = item
            titleTextView.text = item.animeTitle
            episodeTextView.text = item.episodeName
            Glide.with(itemView.context)
                .load(item.animeThumbnailUrl)
                .placeholder(R.drawable.placeholder_anime)
                .into(imageView)

            if (item.duration > 0) {
                progressBar.visibility = View.VISIBLE
                progressBar.progress = ((item.lastWatchedPosition * 100) / item.duration).toInt()
            } else {
                progressBar.visibility = View.GONE
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_continue_watching, parent, false)
        return ViewHolder(view, onClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item)
    }
}

object WatchHistoryDiffCallback : DiffUtil.ItemCallback<WatchHistory>() {
    override fun areItemsTheSame(oldItem: WatchHistory, newItem: WatchHistory): Boolean {
        return oldItem.episodeUrl == newItem.episodeUrl
    }

    override fun areContentsTheSame(oldItem: WatchHistory, newItem: WatchHistory): Boolean {
        return oldItem == newItem
    }
}